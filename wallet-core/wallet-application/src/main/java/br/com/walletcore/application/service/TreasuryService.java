package br.com.walletcore.application.service;

import br.com.walletcore.application.port.in.BalanceHistoryUseCase.ClosingBalance;
import br.com.walletcore.application.port.in.TreasuryUseCase;
import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.LedgerRepository.DaySum;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.exception.NotFoundException;
import br.com.walletcore.domain.exception.ValidationException;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads the backing accounts for the treasury (ADR-011). Nothing here scans the ledger: an expected balance is the
 * sum of a few shard rows, a day's closing is ADR-012's binary search on each shard, the statement merges the
 * shards' newest entries, and the uncovered money per day sums one sequence range of "a regularizar", which only
 * receives deposits and coverages.
 */
public final class TreasuryService implements TreasuryUseCase {

    private final TransactionRunner tx;
    private final AccountRepository accounts;
    private final BackingAccountRepository backing;
    private final LedgerRepository ledger;
    private final BackingRouter router;
    private final ClosingBalances closings;
    private final Clock clock;

    public TreasuryService(TransactionRunner tx, AccountRepository accounts, BackingAccountRepository backing,
                           LedgerRepository ledger, BackingRouter router, ClosingBalances closings, Clock clock) {
        this.tx = tx;
        this.accounts = accounts;
        this.backing = backing;
        this.ledger = ledger;
        this.router = router;
        this.closings = closings;
        this.clock = clock;
    }

    @Override
    public List<Position> positions(TenantId tenantId) {
        LocalDate yesterday = closings.today().minusDays(1);
        boolean yesterdayClosed = closings.isClosed(yesterday);
        return tx.readOnly(tenantId, () -> {
            Map<BackingAccountId, Long> sums = backing.sumBalances(tenantId);
            return backing.findAll(tenantId).stream().map(b -> {
                Money expected = Money.ofCents(-sums.getOrDefault(b.id(), 0L));
                Money endOfYesterday = null;
                if (yesterdayClosed) {
                    long cents = 0;
                    for (AccountId shard : backing.findShardIds(tenantId, b.id())) {
                        cents += closings.closing(tenantId, shard, yesterday, version(tenantId, shard)).balance().cents();
                    }
                    endOfYesterday = Money.ofCents(-cents);
                }
                return new Position(b, expected, endOfYesterday);
            }).toList();
        });
    }

    @Override
    public BackingAccount register(RegisterCommand c) {
        BackingAccount account = BackingAccount.register(c.tenantId(), c.type(), c.name(), c.externalRef(),
                clock.instant());
        // Only the treasury moves money in or out of these: one shard is enough.
        return tx.inTransaction(c.tenantId(), () -> {
            backing.insert(account, List.of(Account.openBackingShard(c.tenantId(), account.createdAt())));
            return account;
        });
    }

    @Override
    public List<DayPosition> dailyPositions(TenantId tenantId, BackingAccountId id, LocalDate from, LocalDate to) {
        closings.requirePeriod(from, to);
        return tx.readOnly(tenantId, () -> {
            load(tenantId, id);
            long[] cents = null;
            List<LocalDate> dates = null;
            for (AccountId shard : backing.findShardIds(tenantId, id)) {
                List<ClosingBalance> days = closings.daily(tenantId, shard, from, to, version(tenantId, shard));
                if (cents == null) {
                    cents = new long[days.size()];
                    dates = days.stream().map(ClosingBalance::date).toList();
                }
                for (int i = 0; i < days.size(); i++) {
                    cents[i] += days.get(i).balance().cents();
                }
            }
            List<DayPosition> result = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                result.add(new DayPosition(dates.get(i), Money.ofCents(-cents[i])));
            }
            return result;
        });
    }

    /**
     * Merges the shards' newest entries, newest first. The cursor is, per shard (in id order), the sequence below
     * which the next page starts. Each shard is read one entry beyond the page, so the next entry of every shard is
     * always known: the backing account's balance right after an entry is that entry's own balance-after plus, for
     * each other shard, the balance-after of its next (older) entry - exact, from immutable rows, with no snapshot.
     */
    @Override
    public Statement statement(TenantId tenantId, BackingAccountId id, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new ValidationException("INVALID_LIMIT", "limit must be between 1 and " + MAX_PAGE);
        }
        return tx.readOnly(tenantId, () -> {
            BackingAccount account = load(tenantId, id);
            List<AccountId> shards = backing.findShardIds(tenantId, id);
            Long[] bounds = parseCursor(cursor, shards.size());
            List<List<LedgerEntry>> lists = new ArrayList<>();
            for (int i = 0; i < shards.size(); i++) {
                lists.add(bounds[i] != null && bounds[i] <= 1 ? List.of()
                        : ledger.findPage(tenantId, shards.get(i), bounds[i], limit + 1, null));
            }
            int[] pos = new int[shards.size()];
            List<LedgerEntry> merged = new ArrayList<>();
            List<Long> expectedAfter = new ArrayList<>();
            while (merged.size() < limit) {
                int pick = -1;
                for (int i = 0; i < lists.size(); i++) {
                    if (pos[i] < lists.get(i).size()
                            && (pick < 0 || NEWEST_FIRST.compare(lists.get(i).get(pos[i]), lists.get(pick).get(pos[pick])) < 0)) {
                        pick = i;
                    }
                }
                if (pick < 0) {
                    break;
                }
                LedgerEntry e = lists.get(pick).get(pos[pick]++);
                long balance = e.balanceAfter().cents();
                for (int i = 0; i < lists.size(); i++) {
                    if (i != pick && pos[i] < lists.get(i).size()) {
                        balance += lists.get(i).get(pos[i]).balanceAfter().cents();
                    }
                }
                merged.add(e);
                expectedAfter.add(-balance);
            }
            boolean more = false;
            StringBuilder next = new StringBuilder();
            for (int i = 0; i < lists.size(); i++) {
                boolean hasNext = pos[i] < lists.get(i).size();
                more |= hasNext;
                next.append(i == 0 ? "" : ".").append(hasNext ? lists.get(i).get(pos[i]).sequence() + 1 : 1);
            }
            return new Statement(account, enrich(tenantId, merged, expectedAfter), more ? next.toString() : null);
        });
    }

    /** Newest first; same instant: the later transaction (UUIDv7 ids grow with time) first. */
    private static final Comparator<LedgerEntry> NEWEST_FIRST = Comparator
            .comparing(LedgerEntry::occurredAt).reversed()
            .thenComparing(e -> e.transactionId().value(), Comparator.reverseOrder());

    private static Long[] parseCursor(String cursor, int shards) {
        Long[] bounds = new Long[shards];
        if (cursor == null || cursor.isBlank()) {
            return bounds;
        }
        String[] parts = cursor.split("\\.");
        if (parts.length != shards) {
            throw new ValidationException("INVALID_CURSOR", "cursor does not belong to this backing account");
        }
        try {
            for (int i = 0; i < shards; i++) {
                bounds[i] = Long.parseLong(parts[i]);
                if (bounds[i] < 1) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException e) {
            throw new ValidationException("INVALID_CURSOR", "malformed cursor");
        }
        return bounds;
    }

    private List<Entry> enrich(TenantId tenantId, List<LedgerEntry> entries, List<Long> expectedAfter) {
        Map<AccountId, BackingAccountId> owners = backing.findShardOwners(tenantId);
        Map<BackingAccountId, BackingAccount> backingById = backing.findAll(tenantId).stream()
                .collect(Collectors.toMap(BackingAccount::id, Function.identity()));
        Set<AccountId> customerIds = new HashSet<>();
        Set<TransactionId> treasury = new HashSet<>();
        for (LedgerEntry e : entries) {
            if (e.counterpartyAccountId() != null && !owners.containsKey(e.counterpartyAccountId())) {
                customerIds.add(e.counterpartyAccountId());
            }
            if (e.type() == TransactionType.TREASURY_TRANSFER || e.type() == TransactionType.REVENUE_WITHDRAWAL) {
                treasury.add(e.transactionId());
            }
        }
        Map<AccountId, AccountDirectoryItem> customers = customerIds.isEmpty() ? Map.of()
                : accounts.findByIds(tenantId, customerIds).stream()
                        .collect(Collectors.toMap(i -> i.account().id(), Function.identity()));
        Map<TransactionId, TreasuryReference> references = treasury.isEmpty() ? Map.of()
                : backing.findTreasuryReferences(tenantId, treasury);
        List<Entry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry e = entries.get(i);
            AccountId other = e.counterpartyAccountId();
            AccountDirectoryItem customer = other == null ? null : customers.get(other);
            BackingAccount otherBacking = other == null || !owners.containsKey(other) ? null
                    : backingById.get(owners.get(other));
            result.add(new Entry(e, Money.ofCents(expectedAfter.get(i)),
                    customer == null ? null : customer.customerName(),
                    customer == null || customer.account().number() == null ? null
                            : customer.account().number().formatted(),
                    otherBacking, references.get(e.transactionId())));
        }
        return result;
    }

    @Override
    public List<UncoveredDay> uncoveredDaily(TenantId tenantId, LocalDate from, LocalDate to) {
        closings.requirePeriod(from, to);
        return tx.readOnly(tenantId, () -> {
            BackingAccountId unassigned = router.defaultAccount(tenantId, BackingType.UNASSIGNED);
            Map<LocalDate, long[]> byDay = new TreeMap<>(); // created, covered, closing balance
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                byDay.put(d, new long[3]);
            }
            for (AccountId shard : backing.findShardIds(tenantId, unassigned)) {
                // The day before the period too: its last sequence is where the period's entries start.
                List<ClosingBalance> days = closings.daily(tenantId, shard, from.minusDays(1), to,
                        version(tenantId, shard));
                for (ClosingBalance day : days.subList(1, days.size())) {
                    byDay.get(day.date())[2] += day.balance().cents();
                }
                long after = days.getFirst().lastSequence();
                long upTo = days.getLast().lastSequence();
                if (upTo > after) {
                    for (DaySum sum : ledger.sumByDay(tenantId, shard, after, upTo, ClosingBalances.BRASILIA)) {
                        long[] day = byDay.get(sum.date());
                        if (day != null) {
                            day[0] += sum.debits();  // a deposit (or the reclassification) debits "a regularizar"
                            day[1] += sum.credits(); // a coverage credits it
                        }
                    }
                }
            }
            List<UncoveredDay> result = new ArrayList<>();
            byDay.forEach((date, v) -> result.add(new UncoveredDay(date, Money.ofCents(v[0]), Money.ofCents(v[1]),
                    Money.ofCents(-v[2]))));
            return result;
        });
    }

    private BackingAccount load(TenantId tenantId, BackingAccountId id) {
        return backing.findById(tenantId, id)
                .orElseThrow(() -> new NotFoundException("BACKING_ACCOUNT_NOT_FOUND", "backing account not found"));
    }

    private long version(TenantId tenantId, AccountId shard) {
        return accounts.findById(tenantId, shard)
                .orElseThrow(() -> new IllegalStateException("shard " + shard + " not found"))
                .version();
    }

    @Override
    public Revenue revenue(TenantId tenantId, Long before, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE);
        YearMonth month = YearMonth.from(closings.today());
        LocalDate endOfPrevious = month.atDay(1).minusDays(1);
        boolean previousClosed = closings.isClosed(endOfPrevious);
        return tx.readOnly(tenantId, () -> {
            AccountId revenue = accounts.findTenantRevenue(tenantId)
                    .orElseThrow(() -> new IllegalStateException("tenant has no revenue account"));
            Account account = accounts.findById(tenantId, revenue).orElseThrow();
            List<LedgerEntry> page = ledger.findPage(tenantId, revenue, before, pageSize, null);
            Long next = page.size() == pageSize ? page.get(page.size() - 1).sequence() : null;

            Set<TransactionId> withdrawalIds = page.stream()
                    .filter(e -> e.type() == TransactionType.REVENUE_WITHDRAWAL)
                    .map(LedgerEntry::transactionId).collect(Collectors.toSet());
            Map<TransactionId, Withdrawal> withdrawals = Map.of();
            if (!withdrawalIds.isEmpty()) {
                Map<BackingAccountId, String> names = backing.findAll(tenantId).stream()
                        .collect(Collectors.toMap(BackingAccount::id, BackingAccount::name));
                withdrawals = backing.findRevenueWithdrawals(tenantId, withdrawalIds).entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                w -> new Withdrawal(w.getValue(), names.get(w.getValue().from()))));
            }

            // In the first minute of the month the previous one may still be receiving entries: no total yet.
            RevenueMonth thisMonth = null;
            if (previousClosed) {
                long atStart = closings.closing(tenantId, revenue, endOfPrevious, account.version()).balance().cents();
                Money withdrawn = backing.sumRevenueWithdrawnSince(tenantId,
                        month.atDay(1).atStartOfDay(ClosingBalances.BRASILIA).toInstant());
                thisMonth = new RevenueMonth(month,
                        Money.ofCents(account.balance().cents() - atStart + withdrawn.cents()), withdrawn);
            }
            return new Revenue(account.balance(), page, next, withdrawals, thisMonth);
        });
    }
}
