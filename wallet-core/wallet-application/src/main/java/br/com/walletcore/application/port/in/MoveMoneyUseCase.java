package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
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
