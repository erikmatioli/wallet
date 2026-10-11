package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.BlockId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Instant;

/**
 * Money movements. Every command carries an idempotency key: repeating a request with the same
 * key and payload returns the original result without moving money twice.
 */
public interface MoveMoneyUseCase {

    TransactionResult deposit(DepositCommand command);

    TransactionResult withdraw(WithdrawCommand command);

    TransactionResult transfer(TransferCommand command);

    /**
     * Credits back, in full, the account a WITHDRAWAL or a PIX_OUT debited. Idempotent by nature:
     * a given debit can be reversed once, whatever the caller does - repeating the call returns the
     * original reversal ({@code replayed = true}). The caller says which debit to undo, never
     * which account or amount, so this cannot be used to credit arbitrary money.
     *
     * <p>A WITHDRAWAL is reversed with a DEPOSIT. A PIX_OUT is reversed with a PIX_REFUND that
     * carries the original's Pix detail, and only while the Pix has no return (ADR-010).
     */
    TransactionResult reverseWithdrawal(ReversalCommand command);

    /**
     * Posts a Pix movement (ADR-010) with its detail, enforcing the rules that tie a return to the
     * original Pix: it must exist on the same account, must not have been refunded, and the returns
     * together cannot exceed it. PIX_REFUND is not accepted here: it is the reversal of a PIX_OUT
     * ({@link #reverseWithdrawal}).
     */
    TransactionResult postPix(PixCommand command);

    /**
     * The treasury moves money between two backing accounts (ADR-011): a coverage, when it leaves "a regularizar"
     * (the money a deposit created arrived somewhere), or a rebalancing between two places. Never touches a
     * customer: the tenant's position does not change. A coverage cannot exceed what is uncovered, and nothing
     * goes into "a regularizar".
     */
    TransactionResult treasuryTransfer(TreasuryTransferCommand command);

    /**
     * Carries out a court's order on a judicially blocked account (ADR-013, item 3): debits it, whatever its fraud or
     * administrative blocks say, and credits another customer of the tenant or the judicial transit (a court deposit
     * in another bank, whose TED the treasury sends and records with {@link #judicialTransitOut}). Up to the balance;
     * an order may take several transfers.
     */
    TransactionResult judicialTransfer(JudicialTransferCommand command);

    /**
     * Records the TED of a judicial transfer to the transit: out of the transit, from the backing account the TED was
     * sent from. The TEDs of a transfer cannot exceed it.
     */
    TransactionResult judicialTransitOut(JudicialTransitOutCommand command);

    /** Where the court sends the money: another customer account of the tenant, or a court deposit elsewhere. */
    sealed interface JudicialDestination {
        record ToAccount(Destination account) implements JudicialDestination {
        }

        record ToTransit() implements JudicialDestination {
        }
    }

    record JudicialTransferCommand(TenantId tenantId, AccountId accountId, BlockId blockId, Money amount,
                                   JudicialDestination destination, String note, Actor actor,
                                   String idempotencyKey) {
    }

    /** @param document the TED's receipt (required) */
    record JudicialTransitOutCommand(TenantId tenantId, TransactionId transferId, BackingAccountId from, Money amount,
                                     String document, String note, Actor actor, String idempotencyKey) {
    }

    record TreasuryTransferCommand(TenantId tenantId, BackingAccountId from, BackingAccountId to, Money amount,
                                   String description, TreasuryReference reference, String idempotencyKey) {
    }

    /** @param reasonCode SPI reason of the refund, only used when the reversed debit is a PIX_OUT */
    record ReversalCommand(TenantId tenantId, TransactionId withdrawalId, String description, String reasonCode) {

        public ReversalCommand(TenantId tenantId, TransactionId withdrawalId, String description) {
            this(tenantId, withdrawalId, description, null);
        }
    }

    /**
     * The type is the detail's: one source of truth for what this Pix is. For a PIX_OUT the
     * EndToEndId is not part of the idempotency fingerprint: the payer's PSP mints it per attempt,
     * so a retry may carry a new one - it replays the original debit, and the result carries the
     * EndToEndId stored by the first attempt, which the caller must then use.
     */
    record PixCommand(TenantId tenantId, AccountId accountId, Money amount, String description,
                      String idempotencyKey, PixDetail detail) {
    }

    record DepositCommand(TenantId tenantId, AccountId accountId, Money amount, String description,
                          String idempotencyKey) {
    }

    record WithdrawCommand(TenantId tenantId, AccountId accountId, Money amount, String description,
                           String idempotencyKey) {
    }

    sealed interface Destination {
        record ById(AccountId id) implements Destination {
        }

        record ByNumber(String branch, String number, String checkDigit) implements Destination {
        }
    }

    record TransferCommand(TenantId tenantId, AccountId source, Destination destination, Money amount,
                           String description, String idempotencyKey) {
    }

    /** {@code endToEndId}: the Pix's EndToEndId for a PIX_* transaction (as stored, also on a replay), else null. */
    record TransactionResult(TransactionId id, TransactionType type, Money amount, Instant occurredAt,
                             String description, boolean replayed, String endToEndId) {
    }
}
