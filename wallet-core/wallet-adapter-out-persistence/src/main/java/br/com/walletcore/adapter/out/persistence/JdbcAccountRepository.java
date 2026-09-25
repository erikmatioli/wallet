package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS = """
            id, tenant_id, kind, customer_id, ispb, branch, account_number, check_digit, status,
            allow_negative, balance_cents, version, created_at""";

    private final JdbcClient jdbc;

    JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Account a) {
        PaymentAccountNumber n = a.number();
        jdbc.sql("""
                INSERT INTO account (id, tenant_id, kind, customer_id, ispb, branch, account_number, check_digit,
                                     account_type, status, allow_negative, balance_cents, version, created_at, updated_at)
                VALUES (:id, :tenant, :kind, :customer, :ispb, :branch, :number, :digit,
                        :type, :status, :allowNegative, :balance, :version, :now, :now)""")
                .param("id", a.id().value())
                .param("tenant", a.tenantId().value())
                .param("kind", a.kind().name())
                .param("customer", a.customerId() == null ? null : a.customerId().value(), Types.OTHER)
                .param("ispb", n == null ? null : n.ispb(), Types.VARCHAR)
                .param("branch", n == null ? null : n.branch(), Types.VARCHAR)
                .param("number", n == null ? null : n.number(), Types.VARCHAR)
                .param("digit", n == null ? null : n.checkDigit(), Types.VARCHAR)
                .param("type", n == null ? null : n.type().bcbCode(), Types.VARCHAR)
                .param("status", a.status().name())
                .param("allowNegative", a.allowNegativeBalance())
                .param("balance", a.balance().cents())
                .param("version", a.version())
                .param("now", Sql.ts(a.createdAt()))
                .update();
    }

    @Override
    public Optional<Account> findById(TenantId tenantId, AccountId accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM account WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId.value())
                .param("id", accountId.value())
                .query(JdbcAccountRepository::map)
                .optional();
    }

    @Override
    public Optional<Account> findByNumber(TenantId tenantId, String branch, String number, String checkDigit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM account
                WHERE tenant_id = :tenant AND kind = 'CUSTOMER'
                  AND branch = :branch AND account_number = :number AND check_digit = :digit""")
                .param("tenant", tenantId.value())
                .param("branch", branch)
                .param("number", number)
                .param("digit", checkDigit)
                .query(JdbcAccountRepository::map)
                .optional();
    }

    @Override
    public List<AccountId> findRecentlyActiveCustomerAccountIds(TenantId tenantId, int limit) {
        return jdbc.sql("""
                SELECT id FROM account
                 WHERE tenant_id = :tenant AND kind = 'CUSTOMER' AND status = 'ACTIVE'
                 ORDER BY updated_at DESC
                 LIMIT :limit""")
                .param("tenant", tenantId.value())
                .param("limit", limit)
                .query((rs, i) -> new AccountId(Sql.uuid(rs, "id")))
                .list();
    }

    @Override
    public long nextAccountSequence() {
        return jdbc.sql("SELECT nextval('account_number_seq')").query(Long.class).single();
    }

    @Override
    public List<AccountId> findSettlementAccountIds(TenantId tenantId) {
        return jdbc.sql("SELECT id FROM account WHERE tenant_id = :tenant AND kind = 'SETTLEMENT' ORDER BY id")
                .param("tenant", tenantId.value())
                .query((rs, i) -> new AccountId(Sql.uuid(rs, "id")))
                .list();
    }

    /**
     * The heart of concurrency control. A single statement checks the rules and changes the
     * balance; PostgreSQL takes a row lock, and a concurrent writer waiting on that lock
     * re-evaluates the WHERE clause against the freshly committed row (READ COMMITTED
     * "EvalPlanQual"). Result: no lost updates, no overdraft, no explicit SELECT ... FOR UPDATE.
     * The returned {@code version} becomes the account's gapless ledger sequence number.
     */
    @Override
    public BalanceUpdateResult applyDelta(TenantId tenantId, AccountId accountId, Account.Kind expectedKind,
                                          long deltaCents) {
        Optional<BalanceUpdateResult.Applied> applied = jdbc.sql("""
                UPDATE account
                   SET balance_cents = balance_cents + :delta,
                       version       = version + 1,
                       updated_at    = now()
                 WHERE tenant_id = :tenant
                   AND id        = :id
                   AND kind      = :kind
                   AND status    = 'ACTIVE'
                   AND (allow_negative OR balance_cents + :delta >= 0)
                RETURNING balance_cents, version""")
                .param("delta", deltaCents)
                .param("tenant", tenantId.value())
                .param("id", accountId.value())
                .param("kind", expectedKind.name())
                .query((rs, i) -> new BalanceUpdateResult.Applied(Money.ofCents(rs.getLong(1)), rs.getLong(2)))
                .optional();
        if (applied.isPresent()) {
            return applied.get();
        }
        // Nothing was updated: find out why (same transaction, so consistent enough for diagnosis).
        Optional<Account> current = findById(tenantId, accountId).filter(a -> a.kind() == expectedKind);
        if (current.isEmpty()) {
            return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.ACCOUNT_NOT_FOUND);
        }
        if (!current.get().isActive()) {
            return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.ACCOUNT_NOT_ACTIVE);
        }
        return new BalanceUpdateResult.Rejected(BalanceUpdateResult.Reason.INSUFFICIENT_FUNDS);
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        String accountNumber = rs.getString("account_number");
        PaymentAccountNumber number = accountNumber == null ? null : new PaymentAccountNumber(
                rs.getString("ispb"), rs.getString("branch"), accountNumber, rs.getString("check_digit"),
                AccountType.PAYMENT);
        UUID customer = rs.getObject("customer_id", UUID.class);
        return new Account(
                new AccountId(Sql.uuid(rs, "id")),
                new TenantId(Sql.uuid(rs, "tenant_id")),
                Account.Kind.valueOf(rs.getString("kind")),
                customer == null ? null : new CustomerId(customer),
                number,
                Account.Status.valueOf(rs.getString("status")),
                rs.getBoolean("allow_negative"),
                Money.ofCents(rs.getLong("balance_cents")),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"));
    }
}
