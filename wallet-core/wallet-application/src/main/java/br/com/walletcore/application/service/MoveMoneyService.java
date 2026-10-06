package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.MoveMoneyUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.PixDetailRepository;
import br.com.walletcore.application.port.out.StoredTransaction;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
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
    private final SettlementRouter settlement;
    private final MetricsRecorder metrics;
    private final Clock clock;

    public MoveMoneyService(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                            TransactionJournal journal, OutboxRepository outbox, PixDetailRepository pixDetails,
                            SettlementRouter settlement, MetricsRecorder metrics, Clock clock) {
        this.tx = tx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.journal = journal;
        this.outbox = outbox;
        this.pixDetails = pixDetails;
        this.settlement = settlement;
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
            AccountId settlementId = settlement.pick(c.tenantId(), id);
            LedgerTransaction lt = LedgerTransaction.deposit(c.tenantId(), id, c.accountId(), settlementId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, settlementId);
        });
    }

    @Override
    public TransactionResult withdraw(WithdrawCommand c) {
        String key = validateKey(c.idempotencyKey());
        requirePositive(c.amount());
        String fp = fingerprint("WITHDRAWAL", c.accountId(), c.amount().cents(), description(c.description()));
        return tx.inTransaction(c.tenantId(), () -> {
            TransactionId id = TransactionId.newId();
            AccountId settlementId = settlement.pick(c.tenantId(), id);
            LedgerTransaction lt = LedgerTransaction.withdrawal(c.tenantId(), id, c.accountId(), settlementId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, settlementId);
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
            return post(lt, key, fp, null);
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
            AccountId settlementId = settlement.pick(c.tenantId(), id);
            if (debit.type() == TransactionType.WITHDRAWAL) {
                LedgerTransaction lt = LedgerTransaction.deposit(c.tenantId(), id, debit.accountId(), settlementId,
                        debit.amount(), description, clock.instant());
                return post(lt, key, fp, settlementId);
            }
            pixDetails.lockOriginal(c.tenantId(), c.withdrawalId());
            PixDetail refund = pixDetails.findByTransaction(c.tenantId(), c.withdrawalId())
                    .orElseThrow(() -> new IllegalStateException("PIX_OUT " + c.withdrawalId().value() + " has no detail"))
                    .refundOf(c.withdrawalId(), c.reasonCode());
            LedgerTransaction lt = LedgerTransaction.pix(c.tenantId(), id, TransactionType.PIX_REFUND,
                    debit.accountId(), settlementId, debit.amount(), description, clock.instant());
            return post(lt, key, fp, settlementId, refund, () -> {
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
            AccountId settlementId = settlement.pick(c.tenantId(), id);
            LedgerTransaction lt = LedgerTransaction.pix(c.tenantId(), id, detail.type(), c.accountId(), settlementId,
                    c.amount(), c.description(), clock.instant());
            return post(lt, key, fp, settlementId, detail, () -> checkPixRules(c, detail));
        });
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

    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint, AccountId settlementId) {
        return post(lt, key, fingerprint, settlementId, null, () -> { });
    }

    /**
     * @param pixDetail stored with the transaction when it is a Pix (null otherwise)
     * @param rules     checks that only apply to a new transaction: they run after the idempotency
     *                  lookup, so a replay is still answered when the rules would now refuse it
     *                  (a full return, replayed, would otherwise "exceed" the original)
     */
    private TransactionResult post(LedgerTransaction lt, String key, String fingerprint, AccountId settlementId,
                                   PixDetail pixDetail, Runnable rules) {
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
            Account.Kind kind = leg.accountId().equals(settlementId) ? Account.Kind.SETTLEMENT : Account.Kind.CUSTOMER;
            BalanceUpdateResult result = accounts.applyDelta(lt.tenantId(), leg.accountId(), kind, leg.signedCents());
            switch (result) {
                case BalanceUpdateResult.Applied applied -> entries.add(new LedgerEntry(
                        UuidV7.next(), lt.tenantId(), lt.id(), leg.accountId(), applied.version(), leg.direction(),
                        leg.amount(), applied.balance(), lt.type(), lt.description(), counterpartyOf(lt, leg),
                        lt.occurredAt()));
                case BalanceUpdateResult.Rejected rejected -> throw rejection(lt.tenantId(), lt.type(), rejected, leg.accountId());
            }
        }
        ledger.append(entries);
        if (pixDetail != null) {
            pixDetails.insert(lt.tenantId(), lt.id(), pixDetail);
        }
        outbox.enqueue(List.of(TransactionPosted.from(lt, entries)));
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
