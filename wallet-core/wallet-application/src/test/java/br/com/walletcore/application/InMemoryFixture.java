package br.com.walletcore.application;

import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.AccountRepository.AccountHolder;
import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.PixDetailRepository;
import br.com.walletcore.application.port.out.StoredTransaction;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.application.service.AuditLedgerService;
import br.com.walletcore.application.service.BalanceHistoryService;
import br.com.walletcore.application.service.MoveMoneyService;
import br.com.walletcore.application.service.QueryAccountService;
import br.com.walletcore.application.service.BackingRouter;
import br.com.walletcore.application.service.ClosingBalances;
import br.com.walletcore.application.service.TreasuryService;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.event.DomainEvent;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.ledger.TransactionType;
import br.com.walletcore.domain.pix.PixDetail;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.util.Collection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Single-threaded in-memory implementation of the outbound ports, for fast use-case tests. */
final class InMemoryFixture {

    final TenantId tenant = TenantId.newId();
    final Map<AccountId, Account> accounts = new HashMap<>();
    final List<LedgerEntry> entries = new ArrayList<>();
    final Map<String, StoredTransaction> journal = new HashMap<>();
    final List<DomainEvent> events = new ArrayList<>();
    final Map<TransactionId, PixDetail> pixDetails = new LinkedHashMap<>();

    final Map<AccountId, TaxId> taxIdsByAccount = new HashMap<>();
    final Map<AccountId, Customer.Status> customerStatusByAccount = new HashMap<>();
    private long accountSequence = 0;

    final MutableClock clock = new MutableClock(Instant.parse("2026-09-21T12:00:00Z"));

    /** A clock the test moves by hand - forward, or back, as another instance's late clock would be. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    final TransactionRunner runner = new TransactionRunner() {
        @Override
        public <T> T inTransaction(TenantId tenantId, Supplier<T> work) {
            return work.get();
        }

        @Override
        public <T> T readOnly(TenantId tenantId, Supplier<T> work) {
            return work.get();
        }
    };

    final AccountRepository accountRepository = new AccountRepository() {
        @Override
        public void insert(Account account) {
            accounts.put(account.id(), account);
        }

        @Override
        public Optional<Account> findById(TenantId tenantId, AccountId accountId) {
            return Optional.ofNullable(accounts.get(accountId));
        }

        @Override
        public Optional<Account> findByTaxId(TenantId tenantId, TaxId taxId) {
            return taxIdsByAccount.entrySet().stream()
                    .filter(e -> e.getValue().equals(taxId))
                    .map(e -> accounts.get(e.getKey()))
                    .findFirst();
        }

        @Override
        public Optional<Account> findByNumber(TenantId tenantId, String branch, String number, String checkDigit) {
            return accounts.values().stream()
                    .filter(a -> a.number() != null && a.number().branch().equals(branch)
                            && a.number().number().equals(number) && a.number().checkDigit().equals(checkDigit))
                    .findFirst();
        }

        @Override
        public Optional<AccountHolder> findHolderByNumber(TenantId tenantId, String branch, String number,
                                                          String checkDigit) {
            return findByNumber(tenantId, branch, number, checkDigit)
                    .filter(a -> taxIdsByAccount.containsKey(a.id())) // same as the JOIN customer
                    .map(a -> new AccountHolder(a, taxIdsByAccount.get(a.id()),
                            customerStatusByAccount.getOrDefault(a.id(), Customer.Status.ACTIVE)));
        }

        @Override
        public long nextAccountSequence() {
            return ++accountSequence;
        }

        @Override
        public List<AccountId> findRecentlyActiveCustomerAccountIds(TenantId tenantId, int limit) {
            return accounts.values().stream().filter(a -> a.kind() == Account.Kind.CUSTOMER)
                    .map(Account::id).limit(limit).toList();
        }

        @Override
        public List<AccountDirectoryItem> findAccountDirectory(TenantId tenantId, AccountId cursorExclusive, int limit) {
            // No customer name lookup here: these tests never assert on it, and the fixture has
            // no CustomerRepository reference to join against. Real behaviour is exercised by
            // JdbcAccountRepository against a live Postgres (WalletCoreConcurrencyTest).
            return accounts.values().stream().filter(a -> a.kind() == Account.Kind.CUSTOMER)
                    .sorted(Comparator.comparing((Account a) -> a.id().value()).reversed())
                    .filter(a -> cursorExclusive == null || a.id().value().compareTo(cursorExclusive.value()) < 0)
                    .limit(limit)
                    .map(a -> new AccountDirectoryItem(a, "Test Customer", "***0000"))
                    .toList();
        }

        @Override
        public List<AccountDirectoryItem> findByIds(TenantId tenantId, Set<AccountId> ids) {
            return accounts.values().stream()
                    .filter(a -> ids.contains(a.id()))
                    .map(a -> new AccountDirectoryItem(a, "Test Customer", "***0000"))
                    .toList();
        }

        @Override
        public BalanceUpdateResult applyDelta(TenantId tenantId, AccountId accountId, Account.Kind kind, long delta,
                                              Instant occurredAt) {
            Account a = accounts.get(accountId);
            if (a == null || a.kind() != kind) {
                return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.ACCOUNT_NOT_FOUND);
            }
            if (!a.isActive()) {
                return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.ACCOUNT_NOT_ACTIVE);
            }
            long updated = a.balance().cents() + delta;
            if (!a.allowNegativeBalance() && updated < 0) {
                return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.INSUFFICIENT_FUNDS);
            }
            accounts.put(accountId, new Account(a.id(), a.tenantId(), a.kind(), a.customerId(), a.number(), a.status(),
                    a.allowNegativeBalance(), Money.ofCents(updated), a.version() + 1, a.createdAt()));
            // Same rule as the SQL: GREATEST(last_occurred_at, occurredAt) - dates never go back (ADR-012).
            Instant effective = lastOccurredAt.merge(accountId, occurredAt, (old, now) -> old.isAfter(now) ? old : now);
            return new BalanceUpdateResult.Applied(Money.ofCents(updated), a.version() + 1, effective);
        }
    };

    /** How many single-entry lookups the closing-balance search made (ADR-012: about log2 n). */
    int sequenceReads;

    /** account.last_occurred_at of the SQL adapter. */
    final Map<AccountId, Instant> lastOccurredAt = new HashMap<>();

    final LedgerRepository ledgerRepository = new LedgerRepository() {
        @Override
        public void append(List<LedgerEntry> newEntries) {
            entries.addAll(newEntries);
        }

        @Override
        public List<LedgerEntry> findPage(TenantId tenantId, AccountId accountId, Long before, int limit,
                                          Set<TransactionType> types) {
            return entries.stream()
                    .filter(e -> e.accountId().equals(accountId) && (before == null || e.sequence() < before))
                    .filter(e -> types == null || types.isEmpty() || types.contains(e.type()))
                    .sorted(Comparator.comparingLong(LedgerEntry::sequence).reversed())
                    .limit(limit).toList();
        }

        @Override
        public Optional<LedgerEntry> findBySequence(TenantId tenantId, AccountId accountId, long sequence) {
            sequenceReads++;
            return entries.stream().filter(e -> e.accountId().equals(accountId) && e.sequence() == sequence).findFirst();
        }

        @Override
        public List<LedgerEntry> findByTransaction(TenantId tenantId, br.com.walletcore.domain.shared.TransactionId id) {
            return entries.stream().filter(e -> e.transactionId().equals(id)).toList();
        }

        @Override
        public List<DaySum> sumByDay(TenantId tenantId, AccountId accountId, long after, long upTo, ZoneId zone) {
            Map<LocalDate, long[]> days = new TreeMap<>();
            entries.stream()
                    .filter(e -> e.accountId().equals(accountId) && e.sequence() > after && e.sequence() <= upTo)
                    .forEach(e -> {
                        long[] d = days.computeIfAbsent(LocalDate.ofInstant(e.occurredAt(), zone), k -> new long[2]);
                        d[e.signedCents() < 0 ? 0 : 1] += e.amount().cents();
                    });
            return days.entrySet().stream().map(d -> new DaySum(d.getKey(), d.getValue()[0], d.getValue()[1])).toList();
        }

        @Override
        public void forEachInSequence(TenantId tenantId, AccountId accountId, Consumer<LedgerEntry> consumer) {
            entries.stream().filter(e -> e.accountId().equals(accountId))
                    .sorted(Comparator.comparingLong(LedgerEntry::sequence)).forEach(consumer);
        }
    };

    final Map<BackingAccountId, BackingAccount> backingAccounts = new java.util.LinkedHashMap<>();
    final Map<AccountId, BackingAccountId> shardOwners = new java.util.LinkedHashMap<>();
    final Map<TransactionId, TreasuryReference> treasuryReferences = new HashMap<>();

    final BackingAccountRepository backingRepository = new BackingAccountRepository() {
        @Override
        public void insert(BackingAccount account, List<Account> shards) {
            backingAccounts.put(account.id(), account);
            shards.forEach(s -> {
                accounts.put(s.id(), s);
                shardOwners.put(s.id(), account.id());
            });
        }

        @Override
        public List<BackingAccount> findAll(TenantId tenantId) {
            return List.copyOf(backingAccounts.values());
        }

        @Override
        public Optional<BackingAccount> findById(TenantId tenantId, BackingAccountId id) {
            return Optional.ofNullable(backingAccounts.get(id));
        }

        @Override
        public List<AccountId> findShardIds(TenantId tenantId, BackingAccountId id) {
            return shardOwners.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey)
                    .sorted(Comparator.comparing(AccountId::value)).toList();
        }

        @Override
        public Map<AccountId, BackingAccountId> findShardOwners(TenantId tenantId) {
            return Map.copyOf(shardOwners);
        }

        @Override
        public Map<BackingAccountId, Long> sumBalances(TenantId tenantId) {
            Map<BackingAccountId, Long> sums = new HashMap<>();
            shardOwners.forEach((shard, owner) -> sums.merge(owner, accounts.get(shard).balance().cents(), Long::sum));
            return sums;
        }

        @Override
        public long lockAndSumBalance(TenantId tenantId, BackingAccountId id) {
            return sumBalances(tenantId).getOrDefault(id, 0L);
        }

        @Override
        public void insertTreasuryReference(TenantId tenantId, TransactionId transactionId, TreasuryReference reference) {
            treasuryReferences.put(transactionId, reference);
        }

        @Override
        public Map<TransactionId, TreasuryReference> findTreasuryReferences(TenantId tenantId,
                                                                            Collection<TransactionId> ids) {
            Map<TransactionId, TreasuryReference> found = new HashMap<>();
            ids.forEach(id -> {
                if (treasuryReferences.containsKey(id)) {
                    found.put(id, treasuryReferences.get(id));
                }
            });
            return found;
        }
    };

    final BackingRouter router = new BackingRouter(backingRepository);

    final TransactionJournal transactionJournal = new TransactionJournal() {
        @Override
        public Optional<StoredTransaction> insertIfAbsent(LedgerTransaction tx, String key, String fingerprint) {
            StoredTransaction existing = journal.get(key);
            if (existing != null) {
                return Optional.of(existing);
            }
            journal.put(key, new StoredTransaction(tx.id(), fingerprint, tx.type(), tx.amount(), tx.description(),
                    tx.occurredAt()));
            return Optional.empty();
        }
    };

    final OutboxRepository outbox = newEvents -> events.addAll(newEvents);

    final PixDetailRepository pixDetailRepository = new PixDetailRepository() {
        @Override
        public void insert(TenantId tenantId, TransactionId transactionId, PixDetail detail) {
            if (exists(tenantId, detail.type(), detail.endToEndId(), detail.returnId())) {
                throw new br.com.walletcore.domain.exception.ConflictException("PIX_ALREADY_POSTED", "duplicate");
            }
            pixDetails.put(transactionId, detail);
        }

        @Override
        public Optional<PixDetail> findByTransaction(TenantId tenantId, TransactionId transactionId) {
            return Optional.ofNullable(pixDetails.get(transactionId));
        }

        @Override
        public Map<TransactionId, PixDetail> findByTransactions(TenantId tenantId, Collection<TransactionId> ids) {
            Map<TransactionId, PixDetail> found = new HashMap<>();
            ids.forEach(id -> findByTransaction(tenantId, id).ifPresent(d -> found.put(id, d)));
            return found;
        }

        @Override
        public boolean exists(TenantId tenantId, TransactionType type, String endToEndId, String returnId) {
            return pixDetails.values().stream().anyMatch(d -> d.type() == type && d.endToEndId().equals(endToEndId)
                    && Objects.equals(d.returnId(), returnId));
        }

        @Override
        public long sumRelated(TenantId tenantId, TransactionId related, TransactionType type) {
            return pixDetails.entrySet().stream()
                    .filter(e -> e.getValue().type() == type && related.equals(e.getValue().relatedTransactionId()))
                    .mapToLong(e -> journal.values().stream().filter(t -> t.id().equals(e.getKey()))
                            .mapToLong(t -> t.amount().cents()).sum())
                    .sum();
        }

        @Override
        public void lockOriginal(TenantId tenantId, TransactionId original) {
            // single-threaded fixture: nothing to serialize
        }
    };

    /** No-op: these tests assert on domain/ledger state, not on what gets reported to metrics. */
    final MetricsRecorder metrics = new MetricsRecorder() {
        @Override
        public void transactionPosted(TenantId tenantId, br.com.walletcore.domain.ledger.TransactionType type,
                                      boolean replayed) {
        }

        @Override
        public void transactionRejected(TenantId tenantId, br.com.walletcore.domain.ledger.TransactionType type,
                                        String reasonCode) {
        }

        @Override
        public void customerOnboarded(TenantId tenantId) {
        }

        @Override
        public void auditCompleted(TenantId tenantId, boolean consistent, long findingsCount) {
        }
    };

    final MoveMoneyService moveMoney = new MoveMoneyService(runner, accountRepository, ledgerRepository,
            transactionJournal, outbox, pixDetailRepository, backingRepository, router, metrics, clock);
    final QueryAccountService query = new QueryAccountService(runner, accountRepository, ledgerRepository,
            pixDetailRepository);
    final AuditLedgerService audit = new AuditLedgerService(runner, accountRepository, ledgerRepository, metrics);
    final ClosingBalances closings = new ClosingBalances(ledgerRepository, clock, java.time.Duration.ofMinutes(1));
    final BalanceHistoryService balanceHistory = new BalanceHistoryService(runner, accountRepository, closings);
    final TreasuryService treasury = new TreasuryService(runner, accountRepository, backingRepository,
            ledgerRepository, router, closings, clock);

    /** The tenant's three default backing accounts (ADR-011), three shards each, as provisioning creates them. */
    InMemoryFixture() {
        for (BackingType type : BackingType.values()) {
            if (type.isDefault()) {
                List<Account> shards = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    shards.add(Account.openBackingShard(tenant, clock.instant()));
                }
                backingRepository.insert(BackingAccount.openDefault(tenant, type, clock.instant()), shards);
            }
        }
    }

    BackingAccountId backing(BackingType type) {
        return router.defaultAccount(tenant, type);
    }

    /** The expected balance of a backing account: its shards summed, with the sign the treasury reads. */
    long expectedOf(BackingAccountId id) {
        return -backingRepository.sumBalances(tenant).getOrDefault(id, 0L);
    }

    Account openCustomerAccount() {
        PaymentAccountNumber number = PaymentAccountNumber.generate("12345678", "0001",
                accountRepository.nextAccountSequence(), AccountType.PAYMENT);
        Account account = Account.openPayment(tenant, CustomerId.newId(), number, clock.instant());
        accountRepository.insert(account);
        return account;
    }

    Account openCustomerAccount(String rawTaxId) {
        Account account = openCustomerAccount(); // reaproveita o que já existe
        taxIdsByAccount.put(account.id(), TaxId.parse(rawTaxId));
        return account;
    }

    /** Replaces the stored account with the same one in another status (BLOCKED, CLOSED). */
    Account withStatus(Account a, Account.Status status) {
        Account changed = new Account(a.id(), a.tenantId(), a.kind(), a.customerId(), a.number(), status,
                a.allowNegativeBalance(), a.balance(), a.version(), a.createdAt());
        accounts.put(a.id(), changed);
        return changed;
    }

    long balanceOf(Account account) {
        return accounts.get(account.id()).balance().cents();
    }

    long sumOfAllBalances() {
        return accounts.values().stream().mapToLong(a -> a.balance().cents()).sum();
    }
}
