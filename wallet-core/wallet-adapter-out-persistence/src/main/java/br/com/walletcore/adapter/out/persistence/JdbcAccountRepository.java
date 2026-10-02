package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.AccountRepository;
import br.com.walletcore.application.port.out.AccountRepository.AccountDirectoryItem;
import br.com.walletcore.application.port.out.BalanceUpdateResult;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.AccountType;
import br.com.walletcore.domain.account.PaymentAccountNumber;
import br.com.walletcore.domain.customer.TaxId;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.CustomerId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    public List<AccountDirectoryItem> findAccountDirectory(TenantId tenantId, AccountId cursorExclusive, int limit) {
        // AccountId is a UUIDv7 (time-ordered, see UuidV7), so "id < cursor, order by id desc"
        // is "created strictly before the last seen account" - newest first, no extra column.
        String sql = """
                SELECT a.id, a.customer_id, a.ispb, a.branch, a.account_number, a.check_digit, a.status,
                       a.allow_negative, a.balance_cents, a.version, a.created_at,
                       c.name AS customer_name, c.tax_id, c.tax_id_type
                  FROM account a
                  JOIN customer c ON c.id = a.customer_id
                 WHERE a.tenant_id = :tenant AND a.kind = 'CUSTOMER'
                """ + (cursorExclusive != null ? " AND a.id < :cursor" : "") + """
                 ORDER BY a.id DESC
                 LIMIT :limit""";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("tenant", tenantId.value()).param("limit", limit);
        if (cursorExclusive != null) {
            spec = spec.param("cursor", cursorExclusive.value());
        }
        return spec.query((rs, i) -> mapDirectoryRow(rs, tenantId)).list();
    }

    @Override
    public List<AccountDirectoryItem> findByIds(TenantId tenantId, Set<AccountId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // Same shape as findAccountDirectory's query, filtered by id instead of cursor-paginated -
        // kept as its own method rather than a shared helper because the two callers' WHERE
        // clauses differ enough (cursor range vs. IN-list) that sharing would add more
        // indirection than it would save.
        String sql = """
                SELECT a.id, a.customer_id, a.ispb, a.branch, a.account_number, a.check_digit, a.status,
                       a.allow_negative, a.balance_cents, a.version, a.created_at,
                       c.name AS customer_name, c.tax_id, c.tax_id_type
                  FROM account a
                  JOIN customer c ON c.id = a.customer_id
                 WHERE a.tenant_id = :tenant AND a.kind = 'CUSTOMER' AND a.id IN (:ids)""";
        return jdbc.sql(sql)
                .param("tenant", tenantId.value())
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query((rs, i) -> mapDirectoryRow(rs, tenantId))
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

    /**
     * Row mapper for {@link #findAccountDirectory}. A separate mapper (not {@link #map}) because
     * the directory query selects a different column set (no tenant_id, kind or account_type -
     * the WHERE clause already fixes kind='CUSTOMER', so account_type is always TRAN) and also
     * carries the joined customer display fields that {@code Account} itself has no business
     * knowing about.
     */
    private static AccountDirectoryItem mapDirectoryRow(ResultSet rs, TenantId tenantId) throws SQLException {
        PaymentAccountNumber number = new PaymentAccountNumber(
                rs.getString("ispb"), rs.getString("branch"), rs.getString("account_number"),
                rs.getString("check_digit"), AccountType.PAYMENT);
        Account account = new Account(
                new AccountId(Sql.uuid(rs, "id")),
                tenantId,
                Account.Kind.CUSTOMER,
                new CustomerId(Sql.uuid(rs, "customer_id")),
                number,
                Account.Status.valueOf(rs.getString("status")),
                rs.getBoolean("allow_negative"),
                Money.ofCents(rs.getLong("balance_cents")),
                rs.getLong("version"),
                Sql.instant(rs, "created_at"));
        TaxId taxId = new TaxId(rs.getString("tax_id"), TaxId.DocumentType.valueOf(rs.getString("tax_id_type")));
        return new AccountDirectoryItem(account, rs.getString("customer_name"), taxId.masked());
    }
}
