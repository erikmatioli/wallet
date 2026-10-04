package br.com.walletcore.application;

import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.AccountRepository.AccountHolder;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.application.port.out.LedgerRepository;
import br.com.walletcore.application.port.out.MetricsRecorder;
import br.com.walletcore.application.port.out.OutboxRepository;
import br.com.walletcore.application.port.out.StoredTransaction;
import br.com.walletcore.application.port.out.TransactionJournal;
import br.com.walletcore.application.port.out.TransactionRunner;
import br.com.walletcore.application.service.AuditLedgerService;
import br.com.walletcore.application.service.MoveMoneyService;
import br.com.walletcore.application.service.QueryAccountService;
import br.com.walletcore.application.service.SettlementRouter;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.customer.Customer;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.event.DomainEvent;
import br.com.walletcore.domain.ledger.LedgerEntry;
import br.com.walletcore.domain.ledger.LedgerTransaction;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
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

    final Map<AccountId, TaxId> taxIdsByAccount = new HashMap<>();
    final Map<AccountId, Customer.Status> customerStatusByAccount = new HashMap<>();
    private long accountSequence = 0;

    final Clock clock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);

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
        public List<AccountId> findSettlementAccountIds(TenantId tenantId) {
            return accounts.values().stream().filter(a -> a.kind() == Account.Kind.SETTLEMENT)
                    .map(Account::id).sorted(Comparator.comparing(AccountId::value)).toList();
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
        public BalanceUpdateResult applyDelta(TenantId tenantId, AccountId accountId, Account.Kind kind, long delta) {
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
            return new BalanceUpdateResult.Applied(Money.ofCents(updated), a.version() + 1);
        }
    };

    final LedgerRepository ledgerRepository = new LedgerRepository() {
        @Override
        public void append(List<LedgerEntry> newEntries) {
            entries.addAll(newEntries);
        }

        @Override
        public List<LedgerEntry> findPage(TenantId tenantId, AccountId accountId, Long before, int limit) {
            return entries.stream()
                    .filter(e -> e.accountId().equals(accountId) && (before == null || e.sequence() < before))
                    .sorted(Comparator.comparingLong(LedgerEntry::sequence).reversed())
                    .limit(limit).toList();
        }

        @Override
        public List<LedgerEntry> findByTransaction(TenantId tenantId, br.com.walletcore.domain.shared.TransactionId id) {
            return entries.stream().filter(e -> e.transactionId().equals(id)).toList();
        }

        @Override
        public void forEachInSequence(TenantId tenantId, AccountId accountId, Consumer<LedgerEntry> consumer) {
            entries.stream().filter(e -> e.accountId().equals(accountId))
                    .sorted(Comparator.comparingLong(LedgerEntry::sequence)).forEach(consumer);
        }
    };

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
            transactionJournal, outbox, new SettlementRouter(accountRepository), metrics, clock);
    final QueryAccountService query = new QueryAccountService(runner, accountRepository, ledgerRepository);
    final AuditLedgerService audit = new AuditLedgerService(runner, accountRepository, ledgerRepository, metrics);

    InMemoryFixture() {
        for (int i = 0; i < 3; i++) {
            accountRepository.insert(Account.openSettlement(tenant, clock.instant()));
        }
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
