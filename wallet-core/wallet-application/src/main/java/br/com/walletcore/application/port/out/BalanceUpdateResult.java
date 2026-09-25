package br.com.walletcore.application.port.out;

import br.com.walletcore.domain.shared.Money;

/** Outcome of an atomic balance change. */
public sealed interface BalanceUpdateResult {

    /** The change was applied; {@code version} is the new per-account ledger sequence. */
    record Applied(Money balance, long version) implements BalanceUpdateResult {
    }

    record Rejected(Reason reason) implements BalanceUpdateResult {
    }

    enum Reason { ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE, INSUFFICIENT_FUNDS }
}
