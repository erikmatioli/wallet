package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.application.port.out.AccountBlockRepository;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.application.port.out.FeeDetailRepository;
import br.com.walletcore.application.port.out.JudicialTransferRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.PixDetailRepository;
import br.com.walletcore.application.port.out.StoredTransaction;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountBlock;
import br.com.walletcore.domain.account.AccountPolicy;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.RevenueWithdrawal;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.event.TransactionPosted;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.ConflictException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.EntryDirection;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.ledger.Leg;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import br.com.walletcore.domain.shared.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts balanced double-entry transactions. Everything happens in ONE database transaction:
 * <ol>
 *   <li>register the transaction header (unique idempotency key -> replay protection);</li>
 *   <li>for each leg, in a deterministic account order, atomically apply the balance change
 *       (row lock + overdraft check in a single UPDATE) and take the new version as the
 *       gapless per-account sequence number;</li>
 *   <li>append the ledger entries and enqueue the outbox event.</li>
 * </ol>
 * Any failure rolls the whole thing back, so partial postings cannot exist.
 */
public final class MoveMoneyService implements MoveMoneyUseCase {

    private static final int MAX_KEY_LENGTH = 128;

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final TransactionJournal journal;
    private final OutboxRepository outbox;
    private final PixDetailRepository pixDetails;
    private final BackingAccountRepository backing;
    private final BackingRouter router;
    private final AccountBlockRepository blocks;
    private final JudicialTransferRepository judicial;
    private final FeeDetailRepository feeDetails;
    private final MetricsRecorder metrics;
    private final Clock clock;

    public MoveMoneyService(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                            TransactionJournal journal, OutboxRepository outbox, PixDetailRepository pixDetails,
                            BackingAccountRepository backing, BackingRouter router, AccountBlockRepository blocks,
                            JudicialTransferRepository judicial, FeeDetailRepository feeDetails,
                            MetricsRecorder metrics, Clock clock) {
        this.tx = tx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.journal = journal;
        this.outbox = outbox;
        this.pixDetails = pixDetails;
        this.backing = backing;
        this.router = router;
        this.blocks = blocks;
        this.judicial = judicial;
        this.feeDetails = feeDetails;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public TransactionResult deposit(DepositCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        String fp = fingerprint("DEPOSIT", c.accountId(), c.amount().cents(), description(c.description()));
        return tx.inTransaction(c.tenantId(), () -> {
            TransactionId id = TransactionId.newId();
            // A deposit creates money the tenant has nowhere yet: "a regularizar" until the treasury covers it.
            AccountId backingId = router.pick(c.tenantId(), BackingType.UNASSIGNED, id);
            LedgerTransaction lt = LedgerTransaction.deposit(c.tenantId(), id, c.accountId(), backingId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, backingLeg(backingId));
        });
    }

    @Override
    public TransactionResult withdraw(WithdrawCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        String fp = fingerprint("WITHDRAWAL", c.accountId(), c.amount().cents(), description(c.description()));
        return tx.inTransaction(c.tenantId(), () -> {
            TransactionId id = TransactionId.newId();
            // A withdrawal hands out bank notes: the cash (ATM) is where the money leaves from.
            AccountId backingId = router.pick(c.tenantId(), BackingType.CASH, id);
            LedgerTransaction lt = LedgerTransaction.withdrawal(c.tenantId(), id, c.accountId(), backingId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, backingLeg(backingId));
        });
    }

    @Override
    public TransactionResult transfer(TransferCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        String destinationRef = switch (c.destination()) {
            case Destination.ById d -> "id:" + d.id();
            case Destination.ByNumber n -> "number:" + n.branch() + "/" + n.number() + "/" + n.checkDigit();
        };
        String fp = fingerprint("TRANSFER", c.source(), destinationRef, c.amount().cents(), description(c.description()));
        return tx.inTransaction(c.tenantId(), () -> {
            AccountId destination = resolve(c.tenantId(), c.destination());
            LedgerTransaction lt = LedgerTransaction.transfer(c.tenantId(), TransactionId.newId(), c.source(),
                    destination, c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, Map.of());
        });
    }

    @Override
    public TransactionResult reverseWithdrawal(ReversalCommand c) {
        if (c.withdrawalId() == null) {
            throw new ValidationException("INVALID_TRANSACTION_ID", "transaction id is required");
        }
        // The key is derived from the withdrawal, not chosen by the caller: that is what makes
        // "reverse this debit" happen at most once, even across different callers or retries.
        String key = "reversal:" + c.withdrawalId().value();
        return tx.inTransaction(c.tenantId(), () -> {
            LedgerEntry debit = ledger.findByTransaction(c.tenantId(), c.withdrawalId()).stream()
                    .filter(e -> (e.type() == TransactionType.WITHDRAWAL || e.type() == TransactionType.PIX_OUT)
                            && e.direction() == EntryDirection.DEBIT)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("WITHDRAWAL_NOT_FOUND",
                            "no withdrawal " + c.withdrawalId().value() + " for this tenant"));
            String description = c.description() == null || c.description().isBlank()
                    ? "Estorno de " + c.withdrawalId().value() : c.description();
            // Fingerprint ignores the description on purpose: a retry with a different text is
            // still the same reversal, not a conflicting request.
            String fp = fingerprint("REVERSAL", c.withdrawalId().value());
            TransactionId id = TransactionId.newId();
            if (debit.type() == TransactionType.WITHDRAWAL) {
                // Undoes the withdrawal where it happened: the cash, not "a regularizar" - no money is created.
                AccountId backingId = router.pick(c.tenantId(), BackingType.CASH, id);
                LedgerTransaction lt = LedgerTransaction.deposit(c.tenantId(), id, debit.accountId(), backingId,
                        debit.amount(), description, clock.instant());
                // It undoes a debit: it comes in even under an administrative block (ADR-013).
                return post(lt, key, fp, backingLeg(backingId), null, () -> { }, () -> { }, true,
                        c.withdrawalId());
            }
            AccountId backingId = router.pick(c.tenantId(), BackingType.PIX_PI, id);
            pixDetails.lockOriginal(c.tenantId(), c.withdrawalId());
            PixDetail refund = pixDetails.findByTransaction(c.tenantId(), c.withdrawalId())
                    .orElseThrow(() -> new IllegalStateException("PIX_OUT " + c.withdrawalId().value() + " has no detail"))
                    .refundOf(c.withdrawalId(), c.reasonCode());
            LedgerTransaction lt = LedgerTransaction.pix(c.tenantId(), id, TransactionType.PIX_REFUND,
                    debit.accountId(), backingId, debit.amount(), description, clock.instant());
            return post(lt, key, fp, backingLeg(backingId), refund, () -> {
                if (pixDetails.sumRelated(c.tenantId(), c.withdrawalId(), TransactionType.PIX_RETURN_IN) > 0) {
                    throw new BusinessRuleException("PIX_ALREADY_RETURNED",
                            "this Pix was settled and returned; it cannot be refunded");
                }
            });
        });
    }

    @Override
    public TransactionResult postPix(PixCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        PixDetail detail = c.detail();
        if (detail == null) {
            throw new ValidationException("INVALID_PIX_DETAIL", "Pix detail is required");
        }
        if (detail.type() == TransactionType.PIX_REFUND) {
            throw new ValidationException("INVALID_PIX_TYPE",
                    "a PIX_REFUND is posted by reversing the PIX_OUT (POST /v1/transactions/{id}/reversals)");
        }
        TransactionId related = detail.relatedTransactionId();
        // PIX_OUT: the EndToEndId is minted by the caller per attempt, see PixCommand.
        String e2e = detail.type() == TransactionType.PIX_OUT ? null : detail.endToEndId();
        String fp = fingerprint(detail.type(), c.accountId(), c.amount().cents(), description(c.description()),
                e2e, detail.returnId(), related == null ? null : related.value(), counterpartyKey(detail));
        return tx.inTransaction(c.tenantId(), () -> {
            if (related != null) {
                pixDetails.lockOriginal(c.tenantId(), related);
            }
            TransactionId id = TransactionId.newId();
            AccountId backingId = router.pick(c.tenantId(), BackingType.PIX_PI, id);
            LedgerTransaction lt = LedgerTransaction.pix(c.tenantId(), id, detail.type(), c.accountId(), backingId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, backingLeg(backingId), detail, () -> checkPixRules(c, detail));
        });
    }

    @Override
    public TransactionResult treasuryTransfer(TreasuryTransferCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        if (c.from() == null || c.to() == null) {
            throw new ValidationException("INVALID_BACKING_ACCOUNT", "from and to are required");
        }
        if (c.from().equals(c.to())) {
            throw new BusinessRuleException("SAME_ACCOUNT", "source and destination must be different accounts");
        }
        TreasuryReference reference = c.reference() == null ? new TreasuryReference(null, null) : c.reference();
        String fp = fingerprint("TREASURY_TRANSFER", c.from(), c.to(), c.amount().cents(), description(c.description()),
                reference.document(), reference.note());
        return tx.inTransaction(c.tenantId(), () -> {
            BackingAccount from = loadBacking(c.tenantId(), c.from());
            BackingAccount to = loadBacking(c.tenantId(), c.to());
            if (to.type() == BackingType.UNASSIGNED) {
                throw new BusinessRuleException("INVALID_TREASURY_DESTINATION",
                        "nothing goes into \"a regularizar\": it only receives the money deposits create");
            }
            TransactionId id = TransactionId.newId();
            AccountId fromShard = router.pick(c.tenantId(), from.id(), id);
            AccountId toShard = router.pick(c.tenantId(), to.id(), id);
            String description = c.description() == null || c.description().isBlank()
                    ? (from.type() == BackingType.UNASSIGNED ? "Cobertura: " : "Recomposição: ")
                            + from.name() + " → " + to.name()
                    : c.description();
            LedgerTransaction lt = LedgerTransaction.treasuryTransfer(c.tenantId(), id, fromShard, toShard,
                    c.amount(), description, clock.instant());
            return post(lt, key, fp, Map.of(fromShard, Account.Kind.BACKING, toShard, Account.Kind.BACKING), null, () -> {
                if (from.type() == BackingType.UNASSIGNED) {
                    // Locks every shard of "a regularizar": two coverages at once cannot both pass the check.
                    long uncovered = -backing.lockAndSumBalance(c.tenantId(), from.id());
                    if (c.amount().cents() > uncovered) {
                        throw new BusinessRuleException("COVERAGE_EXCEEDS_UNCOVERED",
                                "the coverage is larger than the uncovered money (" + Money.ofCents(Math.max(0, uncovered))
                                        .toDecimal() + ")");
                    }
                }
            }, () -> backing.insertTreasuryReference(c.tenantId(), id, reference));
        });
    }

    @Override
    public TransactionResult judicialTransfer(JudicialTransferCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        if (c.blockId() == null || c.actor() == null) {
            throw new ValidationException("INVALID_JUDICIAL_TRANSFER", "the judicial block and the operator are required");
        }
        if (c.destination() == null) {
            throw new ValidationException("INVALID_DESTINATION", "destination is required");
        }
        String note = note(c.note());
        String destinationRef = switch (c.destination()) {
            case JudicialDestination.ToTransit t -> "transit";
            case JudicialDestination.ToAccount(Destination.ById d) -> "id:" + d.id();
            case JudicialDestination.ToAccount(Destination.ByNumber n) ->
                    "number:" + n.branch() + "/" + n.number() + "/" + n.checkDigit();
        };
        String fp = fingerprint("JUDICIAL_TRANSFER", c.accountId(), c.blockId(), destinationRef, c.amount().cents(),
                note, c.actor().operator());
        return tx.inTransaction(c.tenantId(), () -> {
            AccountBlock block = blocks.findById(c.tenantId(), c.blockId())
                    .filter(b -> b.accountId().equals(c.accountId()) && b.type() == AccountBlock.Type.JUDICIAL)
                    .orElseThrow(() -> new NotFoundException("JUDICIAL_BLOCK_NOT_FOUND",
                            "no judicial block " + c.blockId() + " on this account"));
            boolean toTransit = c.destination() instanceof JudicialDestination.ToTransit;
            AccountId destination = switch (c.destination()) {
                case JudicialDestination.ToTransit t -> accounts.findJudicialTransit(c.tenantId())
                        .orElseThrow(() -> new IllegalStateException("tenant has no judicial transit account"));
                case JudicialDestination.ToAccount a -> resolve(c.tenantId(), a.account());
            };
            TransactionId id = TransactionId.newId();
            LedgerTransaction lt = LedgerTransaction.judicialTransfer(c.tenantId(), id, c.accountId(), destination,
                    c.amount(), "Ordem judicial, processo " + block.court().process(), clock.instant());
            Map<AccountId, Account.Kind> internal = toTransit
                    ? Map.of(destination, Account.Kind.JUDICIAL_TRANSIT) : Map.of();
            return post(lt, key, fp, internal, null, () -> {
                // Checked only for a new transfer: a replay is still answered after the block was released.
                if (!block.isActive()) {
                    throw new BusinessRuleException("JUDICIAL_BLOCK_NOT_ACTIVE",
                            "the judicial block was released; a released order transfers nothing");
                }
            }, () -> judicial.insert(c.tenantId(), new JudicialTransferRepository.Detail(id,
                    JudicialTransferRepository.Kind.TRANSFER, c.accountId(), block.id(), block.court().process(),
                    block.court().protocol(), toTransit, null, c.actor(), note, null)));
        });
    }

    @Override
    public TransactionResult judicialTransitOut(JudicialTransitOutCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        if (c.transferId() == null || c.from() == null || c.actor() == null) {
            throw new ValidationException("INVALID_JUDICIAL_TRANSIT_OUT",
                    "the judicial transfer, the backing account and the operator are required");
        }
        String document = c.document() == null ? "" : c.document().strip();
        if (document.isEmpty() || document.length() > 140) {
            throw new ValidationException("DOCUMENT_REQUIRED", "the TED's receipt is required, at most 140 characters");
        }
        String note = note(c.note());
        String fp = fingerprint("JUDICIAL_TRANSIT_OUT", c.transferId().value(), c.from(), c.amount().cents(), document,
                note, c.actor().operator());
        return tx.inTransaction(c.tenantId(), () -> {
            JudicialTransferRepository.Detail transfer = judicial.findByTransaction(c.tenantId(), c.transferId())
                    .filter(d -> d.kind() == JudicialTransferRepository.Kind.TRANSFER && d.toTransit())
                    .orElseThrow(() -> new NotFoundException("JUDICIAL_TRANSFER_NOT_FOUND",
                            "no judicial transfer to the transit " + c.transferId().value()));
            BackingAccount from = loadBacking(c.tenantId(), c.from());
            if (from.type() == BackingType.UNASSIGNED) {
                throw new BusinessRuleException("INVALID_TREASURY_SOURCE",
                        "a TED leaves from a real place, never from \"a regularizar\"");
            }
            AccountId transit = accounts.findJudicialTransit(c.tenantId())
                    .orElseThrow(() -> new IllegalStateException("tenant has no judicial transit account"));
            TransactionId id = TransactionId.newId();
            AccountId shard = router.pick(c.tenantId(), from.id(), id);
            LedgerTransaction lt = LedgerTransaction.judicialTransitOut(c.tenantId(), id, transit, shard, c.amount(),
                    "Depósito judicial, processo " + transfer.process(), clock.instant());
            return post(lt, key, fp, Map.of(transit, Account.Kind.JUDICIAL_TRANSIT, shard, Account.Kind.BACKING), null,
                    () -> {
                        // Two TEDs of one transfer at once cannot both fit in what is left.
                        judicial.lock(c.tenantId(), c.transferId());
                        long ordered = ledger.findByTransaction(c.tenantId(), c.transferId()).stream()
                                .filter(e -> e.direction() == EntryDirection.DEBIT)
                                .mapToLong(e -> e.amount().cents()).sum();
                        long sent = judicial.sumSent(c.tenantId(), c.transferId());
                        if (sent + c.amount().cents() > ordered) {
                            throw new BusinessRuleException("TRANSIT_OUT_EXCEEDS_TRANSFER",
                                    "the TEDs would exceed the judicial transfer (" + Money.ofCents(ordered - sent)
                                            .toDecimal() + " left)");
                        }
                    }, () -> judicial.insert(c.tenantId(), new JudicialTransferRepository.Detail(id,
                            JudicialTransferRepository.Kind.TRANSIT_OUT, null, transfer.blockId(), transfer.process(),
                            transfer.protocol(), false, c.transferId(), c.actor(), note, document)));
        });
    }

    @Override
    public TransactionResult revenueWithdrawal(RevenueWithdrawalCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        if (c.actor() == null) {
            throw new ValidationException("INVALID_OPERATOR", "operator is required, at most 80 characters");
        }
        TransactionId id = TransactionId.newId();
        // Validates the destination, the receipt and the note before anything is read.
        RevenueWithdrawal detail = new RevenueWithdrawal(id, c.from(), c.destination(), c.document(), c.note(),
                c.actor());
        String fp = fingerprint("REVENUE_WITHDRAWAL", c.from(), c.amount().cents(), detail.destination().describe(),
                detail.document(), detail.note(), c.actor().operator());
        return tx.inTransaction(c.tenantId(), () -> {
            BackingAccount from = loadBacking(c.tenantId(), c.from());
            if (from.type() == BackingType.UNASSIGNED) {
                throw new BusinessRuleException("INVALID_TREASURY_SOURCE",
                        "the revenue leaves from a real place, never from \"a regularizar\"");
            }
            AccountId revenue = accounts.findTenantRevenue(c.tenantId())
                    .orElseThrow(() -> new IllegalStateException("tenant has no revenue account"));
            AccountId shard = router.pick(c.tenantId(), from.id(), id);
            LedgerTransaction lt = LedgerTransaction.revenueWithdrawal(c.tenantId(), id, revenue, shard, c.amount(),
                    "Retirada de receita: " + detail.destination().describe(), clock.instant());
            return post(lt, key, fp, Map.of(revenue, Account.Kind.TENANT_REVENUE, shard, Account.Kind.BACKING), null,
                    () -> { }, () -> backing.insertRevenueWithdrawal(c.tenantId(), detail));
        });
    }

    @Override
    public TransactionResult fee(FeeCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        String operation = code(c.operation(), "INVALID_FEE_OPERATION", "operation");
        String description = c.description() == null ? "" : c.description().strip();
        if (description.isEmpty()) {
            throw new ValidationException("INVALID_DESCRIPTION", "a fee needs the description the customer will read");
        }
        String reference = c.reference() == null || c.reference().isBlank() ? null : c.reference().strip();
        if (reference != null && reference.length() > 40) {
            throw new ValidationException("INVALID_REFERENCE", "reference must have at most 40 characters");
        }
        String fp = fingerprint("FEE", c.accountId(), c.amount().cents(), operation, description,
                c.source() == null ? null : c.source().value(), reference);
        return tx.inTransaction(c.tenantId(), () -> {
            AccountId revenue = accounts.findTenantRevenue(c.tenantId())
                    .orElseThrow(() -> new IllegalStateException("tenant has no revenue account"));
            TransactionId id = TransactionId.newId();
            LedgerTransaction lt = LedgerTransaction.fee(c.tenantId(), id, c.accountId(), revenue, c.amount(),
                    description, clock.instant());
            return post(lt, key, fp, Map.of(revenue, Account.Kind.TENANT_REVENUE), null, () -> { },
                    () -> feeDetails.insert(c.tenantId(), id,
                            new FeeDetailRepository.Detail(operation, c.source(), reference)),
                    false, c.source());
        });
    }

    @Override
    public TransactionResult feeRefund(FeeRefundCommand c) {
        if (c.feeTransactionId() == null) {
            throw new ValidationException("INVALID_TRANSACTION_ID", "fee transaction id is required");
        }
        // Derived from the fee, like a reversal's: a fee is given back at most once, whoever asks.
        String key = "fee-refund:" + c.feeTransactionId().value();
        String fp = fingerprint("FEE_REFUND", c.feeTransactionId().value());
        return tx.inTransaction(c.tenantId(), () -> {
            LedgerEntry charged = ledger.findByTransaction(c.tenantId(), c.feeTransactionId()).stream()
                    .filter(e -> e.type() == TransactionType.FEE && e.direction() == EntryDirection.DEBIT)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("FEE_NOT_FOUND",
                            "no fee " + c.feeTransactionId().value() + " for this tenant"));
            AccountId revenue = accounts.findTenantRevenue(c.tenantId())
                    .orElseThrow(() -> new IllegalStateException("tenant has no revenue account"));
            String operation = feeDetails.findByTransaction(c.tenantId(), c.feeTransactionId())
                    .map(FeeDetailRepository.Detail::operation).orElse("UNKNOWN");
            String description = c.description() == null || c.description().isBlank()
                    ? "Estorno de tarifa" : c.description().strip();
            TransactionId id = TransactionId.newId();
            LedgerTransaction lt = LedgerTransaction.feeRefund(c.tenantId(), id, charged.accountId(), revenue,
                    charged.amount(), description, clock.instant());
            return post(lt, key, fp, Map.of(revenue, Account.Kind.TENANT_REVENUE), null, () -> { },
                    () -> feeDetails.insert(c.tenantId(), id,
                            new FeeDetailRepository.Detail(operation, c.feeTransactionId(), null)),
                    true, c.feeTransactionId());
        });
    }

    /** An uppercase code of up to 40 letters, digits and underscores: what the fee basket names its operations. */
    private static String code(String value, String errorCode, String what) {
        String v = value == null ? "" : value.strip();
        if (!v.matches("[A-Z][A-Z0-9_]{0,39}")) {
            throw new ValidationException(errorCode, what + " must be an uppercase code of up to 40 characters");
        }
        return v;
    }

    private static String note(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > 280) {
            throw new ValidationException("INVALID_NOTE", "note must have at most 280 characters");
        }
        return v;
    }

    private BackingAccount loadBacking(TenantId tenantId, BackingAccountId id) {
        return backing.findById(tenantId, id)
                .orElseThrow(() -> new NotFoundException("BACKING_ACCOUNT_NOT_FOUND", "backing account " + id + " not found"));
    }

    /** Who the Pix is with, in the fingerprint: the same key for another payee is another request. */
    private static String counterpartyKey(PixDetail d) {
        return d.counterparty().ispb() + "/" + d.counterparty().branch() + "/" + d.counterparty().account();
    }

    /** Runs only for a new transaction (not on an idempotent replay), under the lock of the original. */
    private void checkPixRules(PixCommand c, PixDetail detail) {
        if (pixDetails.exists(c.tenantId(), detail.type(), detail.endToEndId(), detail.returnId())) {
            throw new ConflictException("PIX_ALREADY_POSTED",
                    "this Pix was already posted with another Idempotency-Key");
        }
        switch (detail.type()) {
            case PIX_RETURN_IN -> {
                LedgerEntry original = originalPix(c, detail.relatedTransactionId(), TransactionType.PIX_OUT);
                if (pixDetails.sumRelated(c.tenantId(), original.transactionId(), TransactionType.PIX_REFUND) > 0) {
                    throw new BusinessRuleException("PIX_ALREADY_REFUNDED",
                            "this Pix was refunded, so it was never settled and cannot be returned");
                }
                requireWithinOriginal(c, original, TransactionType.PIX_RETURN_IN);
            }
            case PIX_RETURN_OUT -> requireWithinOriginal(c,
                    originalPix(c, detail.relatedTransactionId(), TransactionType.PIX_IN), TransactionType.PIX_RETURN_OUT);
            default -> {
                // PIX_IN and PIX_OUT stand alone: nothing to check besides the duplicate above.
            }
        }
    }

    /** The customer leg of the original Pix, which must be of {@code type} and on the same account. */
    private LedgerEntry originalPix(PixCommand c, TransactionId related, TransactionType type) {
        return ledger.findByTransaction(c.tenantId(), related).stream()
                .filter(e -> e.type() == type && e.accountId().equals(c.accountId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("PIX_ORIGINAL_NOT_FOUND",
                        "no " + type + " " + related.value() + " on this account"));
    }

    private void requireWithinOriginal(PixCommand c, LedgerEntry original, TransactionType returnType) {
        long returned = pixDetails.sumRelated(c.tenantId(), original.transactionId(), returnType);
        if (returned + c.amount().cents() > original.amount().cents()) {
            throw new BusinessRuleException("PIX_RETURN_EXCEEDS_ORIGINAL",
                    "returns would exceed the original Pix amount");
        }
    }

    private AccountId resolve(TenantId tenantId, Destination destination) {
        return switch (destination) {
            case Destination.ById d -> d.id();
            case Destination.ByNumber n -> accounts.findByNumber(tenantId, n.branch(), n.number(), n.checkDigit())
                    .map(Account::id)
                    .orElseThrow(() -> new NotFoundException("DESTINATION_ACCOUNT_NOT_FOUND",
                            "destination account not found"));
        };
    }

    private static Map<AccountId, Account.Kind> backingLeg(AccountId shard) {
        return Map.of(shard, Account.Kind.BACKING);
    }

    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint,
                                   Map<AccountId, Account.Kind> internal) {
        return post(lt, key, fingerprint, internal, null, () -> { });
    }

    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint,
                                   Map<AccountId, Account.Kind> internal, PixDetail pixDetail, Runnable rules) {
        return post(lt, key, fingerprint, internal, pixDetail, rules, () -> { });
    }

    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint,
                                   Map<AccountId, Account.Kind> internal, PixDetail pixDetail, Runnable rules,
                                   Runnable onPosted) {
        return post(lt, key, fingerprint, internal, pixDetail, rules, onPosted, false);
    }

    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint,
                                   Map<AccountId, Account.Kind> internal, PixDetail pixDetail, Runnable rules,
                                   Runnable onPosted, boolean refund) {
        return post(lt, key, fingerprint, internal, pixDetail, rules, onPosted, refund, null);
    }

    /**
     * @param internal  the legs on internal accounts and their kind (every other leg is a customer account)
     * @param pixDetail stored with the transaction when it is a Pix (null otherwise)
     * @param rules     checks that only apply to a new transaction: they run after the idempotency
     *                  lookup, so a replay is still answered when the rules would now refuse it
     *                  (a full return, replayed, would otherwise "exceed" the original)
     * @param onPosted  writes what goes with a new transaction (the treasury reference), in the same transaction
     * @param refund    the customer's credit undoes a debit of the same account, so no block keeps it out (ADR-013)
     * @param related   the transaction this one undoes or charges for, for the event (ADR-014); a Pix's comes from its
     *                  detail
     */
    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint,
                                   Map<AccountId, Account.Kind> internal, PixDetail pixDetail, Runnable rules,
                                   Runnable onPosted, boolean refund, TransactionId related) {
        Optional<StoredTransaction> existing = journal.insertIfAbsent(lt, key, fingerprint);
        if (existing.isPresent()) {
            StoredTransaction s = existing.get();
            if (!s.fingerprint().equals(fingerprint)) {
                throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                        "this Idempotency-Key was already used with a different request");
            }
            metrics.transactionPosted(lt.tenantId(), s.type(), true);
            String storedE2e = s.type().isPix()
                    ? pixDetails.findByTransaction(lt.tenantId(), s.id()).map(PixDetail::endToEndId).orElse(null)
                    : null;
            return new TransactionResult(s.id(), s.type(), s.amount(), s.occurredAt(), s.description(), true,
                    storedE2e);
        }
        rules.run();

        List<LedgerEntry> entries = new ArrayList<>(lt.legs().size());
        for (Leg leg : lt.legsInLockOrder()) {
            Account.Kind kind = internal.getOrDefault(leg.accountId(), Account.Kind.CUSTOMER);
            AccountPolicy.Gate gate = kind == Account.Kind.CUSTOMER
                    ? AccountPolicy.gateFor(lt.type(), refund) : AccountPolicy.Gate.ANY;
            BalanceUpdateResult result = accounts.applyDelta(lt.tenantId(), leg.accountId(), kind, leg.signedCents(),
                    lt.occurredAt(), gate);
            switch (result) {
                case BalanceUpdateResult.Applied applied -> entries.add(new LedgerEntry(
                        UuidV7.next(), lt.tenantId(), lt.id(), leg.accountId(), applied.version(), leg.direction(),
                        leg.amount(), applied.balance(), lt.type(), lt.description(), counterpartyOf(lt, leg),
                        applied.occurredAt()));
                case BalanceUpdateResult.Rejected rejected -> throw rejection(lt.tenantId(), lt.type(), rejected, leg.accountId());
            }
        }
        ledger.append(entries);
        if (pixDetail != null) {
            pixDetails.insert(lt.tenantId(), lt.id(), pixDetail);
        }
        onPosted.run();
        TransactionId relatedId = related != null ? related : pixDetail == null ? null : pixDetail.relatedTransactionId();
        boolean reversal = refund || lt.type() == TransactionType.PIX_REFUND || lt.type() == TransactionType.FEE_REFUND;
        String counterpartyType = pixDetail == null ? null : pixDetail.counterparty().taxIdType();
        outbox.enqueue(List.of(TransactionPosted.from(lt, entries,
                new TransactionPosted.Facts(relatedId, reversal, counterpartyType))));
        metrics.transactionPosted(lt.tenantId(), lt.type(), false);
        return new TransactionResult(lt.id(), lt.type(), lt.amount(), lt.occurredAt(), lt.description(), false,
                pixDetail == null ? null : pixDetail.endToEndId());
    }

    /**
     * The account on the other leg of the same transaction. Every deposit/withdrawal/transfer
     * built by {@link LedgerTransaction} has exactly two legs today, so "the other one" is
     * unambiguous; this stops being well-defined if a transaction type with 3+ legs is ever
     * introduced, at which point this method (and what "counterparty" even means) needs revisiting.
     */
    private static AccountId counterpartyOf(LedgerTransaction lt, Leg leg) {
        return lt.legs().stream()
                .map(Leg::accountId)
                .filter(id -> !id.equals(leg.accountId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("transaction leg has no counterparty: " + leg));
    }

    private RuntimeException rejection(TenantId tenantId, TransactionType type, BalanceUpdateResult.Rejected rejected,
                                       AccountId accountId) {
        metrics.transactionRejected(tenantId, type, rejected.reason().name());
        return switch (rejected.reason()) {
            case ACCOUNT_NOT_FOUND -> new NotFoundException("ACCOUNT_NOT_FOUND", "account " + accountId + " not found");
            case ACCOUNT_NOT_ACTIVE -> new BusinessRuleException("ACCOUNT_NOT_ACTIVE",
                    "account " + accountId + " is not active");
            case ACCOUNT_BLOCKED -> new BusinessRuleException("ACCOUNT_BLOCKED",
                    "account " + accountId + " is blocked for this movement");
            case INSUFFICIENT_FUNDS -> new BusinessRuleException("INSUFFICIENT_FUNDS",
                    "account " + accountId + " has insufficient funds");
        };
    }

    private static String validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        }
        String k = key.strip();
        if (k.length() > MAX_KEY_LENGTH) {
            throw new ValidationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must have at most " + MAX_KEY_LENGTH + " characters");
        }
        return k;
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new ValidationException("INVALID_AMOUNT", "amount must be greater than zero");
        }
    }

    private static String description(String description) {
        return description == null ? "" : description.strip();
    }

    static String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part).append('\u001f');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
