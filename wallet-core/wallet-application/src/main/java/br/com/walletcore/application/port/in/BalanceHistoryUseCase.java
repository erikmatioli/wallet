package br.com.walletcore.application.port.in;

import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The balance an account had at the end of a day (midnight in Brasília), read from the ledger when asked:
 * nothing is stored (ADR-012). Only days that are over can be asked for.
 */
public interface BalanceHistoryUseCase {

    /** Longest period {@link #dailyBalances} answers: a year, leap or not. */
    int MAX_DAYS = 366;

    /**
     * @param balance      the balance after the account's last entry before {@code cutoff}; zero when there was none
     * @param lastSequence that entry's sequence (0 when none): anyone can check the value against the ledger
     * @param cutoff       the instant the day ended: midnight of the next day in America/Sao_Paulo
     */
    record ClosingBalance(LocalDate date, Money balance, long lastSequence, Instant cutoff) {
    }

    /** DAY_NOT_CLOSED for today or a later day; ACCOUNT_NOT_FOUND for another tenant's or an internal account. */
    ClosingBalance closingBalance(TenantId tenantId, AccountId accountId, LocalDate date);

    /** One closing balance per day from {@code from} to {@code to}, both included, oldest first. */
    List<ClosingBalance> dailyBalances(TenantId tenantId, AccountId accountId, LocalDate from, LocalDate to);
}
