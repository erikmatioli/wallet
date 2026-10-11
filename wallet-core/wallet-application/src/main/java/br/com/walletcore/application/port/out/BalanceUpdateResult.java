package br.com.walletcore.application.port.out;

import java.time.Instant;
import br.com.walletcore.domain.shared.Money;

/** Outcome of an atomic balance change. */
public sealed interface BalanceUpdateResult {

    /**
     * The change was applied; {@code version} is the new per-account ledger sequence and {@code occurredAt}
     * the date the entry must carry: the posting's clock, or the account's previous entry date if that is
     * later, so dates never go back within an account (ADR-012).
     */
    record Applied(Money balance, long version, Instant occurredAt) implements BalanceUpdateResult {
    }

    record Rejected(Reason reason) implements BalanceUpdateResult {
    }

    /** {@code ACCOUNT_NOT_ACTIVE} is a closed account; {@code ACCOUNT_BLOCKED}, one whose blocks refuse this movement. */
    /** {@code ACCOUNT_NOT_ACTIVE} is a closed account; {@code ACCOUNT_BLOCKED}, one whose blocks refuse the movement. */
    enum Reason { ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE, ACCOUNT_BLOCKED, INSUFFICIENT_FUNDS }
}
