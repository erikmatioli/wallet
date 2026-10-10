package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.BalanceHistoryUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.exception.BusinessRuleException;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Closing balances by binary search over the account's sequence (ADR-012). The ledger is immutable, every
 * entry carries the balance after it, and within an account the entries' dates never go back
 * ({@code applyDelta}), so "the last entry before midnight" is the largest sequence whose date is before it -
 * about log2(n) lookups in the unique (account_id, sequence_no) index, however busy the day was.
 */
public final class BalanceHistoryService implements BalanceHistoryUseCase {

    /** The day of the customer, the statement and the income-tax report. */
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final Clock clock;
    private final Duration dayCloseGrace;

    /**
     * @param dayCloseGrace how long after midnight a day counts as closed: an entry dated 23:59:59.9 may still
     *                      be committing after midnight. Must exceed the longest transaction the core runs.
     */
    public BalanceHistoryService(TransactionRunner tx, AccountRepository accounts, LedgerRepository ledger,
                                 Clock clock, Duration dayCloseGrace) {
        this.tx = tx;
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
        this.dayCloseGrace = dayCloseGrace;
    }

    @Override
    public ClosingBalance closingBalance(TenantId tenantId, AccountId accountId, LocalDate date) {
        requireClosed(date);
        return tx.readOnly(tenantId, () -> {
            Account account = load(tenantId, accountId);
            return search(tenantId, accountId, date, 1, account.version(), null);
        });
    }

    @Override
    public List<ClosingBalance> dailyBalances(TenantId tenantId, AccountId accountId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ValidationException("INVALID_PERIOD", "from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new ValidationException("INVALID_PERIOD", "at most " + MAX_DAYS + " days");
        }
        requireClosed(to);
        return tx.readOnly(tenantId, () -> {
            Account account = load(tenantId, accountId);
            List<ClosingBalance> days = new ArrayList<>();
            ClosingBalance previous = null;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                // The sequence only grows with the date: each day's search starts right after the previous day's
                // last entry, which (with everything before it) is already known to be before this day's end.
                long lo = previous == null ? 1 : previous.lastSequence() + 1;
                ClosingBalance day = search(tenantId, accountId, d, lo, account.version(), previous);
                days.add(day);
                previous = day;
            }
            return days;
        });
    }

    /**
     * The largest sequence in [lo, hi] whose entry is dated before the end of {@code date}. Every sequence below
     * {@code lo} is known to be before it already ({@code known} is the closing balance that says so).
     */
    private ClosingBalance search(TenantId tenantId, AccountId accountId, LocalDate date, long lo, long hi,
                                  ClosingBalance known) {
        Instant cutoff = endOf(date);
        long best = known == null ? 0 : known.lastSequence();
        Money balance = known == null ? Money.ZERO : known.balance();
        while (lo <= hi) {
            long mid = lo + (hi - lo) / 2;
            Optional<LedgerEntry> entry = ledger.findBySequence(tenantId, accountId, mid);
            if (entry.isEmpty()) {
                // A gap would mean a broken ledger (sequences are gapless); refuse rather than answer wrong.
                throw new IllegalStateException("account " + accountId + " has no ledger entry " + mid);
            }
            if (entry.get().occurredAt().isBefore(cutoff)) {
                best = mid;
                balance = entry.get().balanceAfter();
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return new ClosingBalance(date, balance, best, cutoff);
    }

    private void requireClosed(LocalDate date) {
        if (date == null) {
            throw new ValidationException("INVALID_DATE", "a date is required");
        }
        if (clock.instant().isBefore(endOf(date).plus(dayCloseGrace))) {
            throw new BusinessRuleException("DAY_NOT_CLOSED",
                    "the balance of " + date + " is only final after the day ends (midnight in Brasília)");
        }
    }

    /** Midnight at the start of the next day, in Brasília. */
    static Instant endOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(BRASILIA).toInstant();
    }

    private Account load(TenantId tenantId, AccountId accountId) {
        return accounts.findById(tenantId, accountId)
                .filter(a -> a.kind() == Account.Kind.CUSTOMER) // internal accounts are never exposed
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "account not found"));
    }
}
