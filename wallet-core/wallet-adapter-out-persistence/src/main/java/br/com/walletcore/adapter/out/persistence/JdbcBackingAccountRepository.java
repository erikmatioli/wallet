package br.com.walletcore.adapter.out.persistence;

import br.com.walletcore.application.port.out.BackingAccountRepository;
import br.com.walletcore.domain.account.Account;
import br.com.walletcore.domain.account.Actor;
import br.com.walletcore.domain.backing.BackingAccount;
import br.com.walletcore.domain.backing.BackingType;
import br.com.walletcore.domain.backing.RevenueWithdrawal;
import br.com.walletcore.domain.backing.TreasuryReference;
import br.com.walletcore.domain.shared.AccountId;
import br.com.walletcore.domain.shared.BackingAccountId;
import br.com.walletcore.domain.shared.Money;
import br.com.walletcore.domain.shared.TenantId;
import br.com.walletcore.domain.shared.TransactionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBackingAccountRepository implements BackingAccountRepository {

    private static final String COLUMNS = "id, tenant_id, type, name, external_ref, created_at";

    private final JdbcClient jdbc;

    JdbcBackingAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(BackingAccount b, List<Account> shards) {
        jdbc.sql("""
                INSERT INTO backing_account (id, tenant_id, type, name, external_ref, created_at)
                VALUES (:id, :tenant, :type, :name, :ref, :now)""")
                .param("id", b.id().value())
                .param("tenant", b.tenantId().value())
                .param("type", b.type().name())
                .param("name", b.name())
                .param("ref", b.externalRef(), Types.VARCHAR)
                .param("now", Sql.ts(b.createdAt()))
                .update();
        for (Account shard : shards) {
            if (shard.kind() != Account.Kind.BACKING) {
                throw new IllegalArgumentException("a backing account's shard must be of kind BACKING");
            }
            jdbc.sql("""
                    INSERT INTO account (id, tenant_id, kind, backing_account_id, status, allow_negative, balance_cents,
                                         version, created_at, updated_at)
                    VALUES (:id, :tenant, 'BACKING', :backing, :status, true, 0, 0, :now, :now)""")
                    .param("id", shard.id().value())
                    .param("tenant", shard.tenantId().value())
                    .param("backing", b.id().value())
                    .param("status", shard.status().name())
                    .param("now", Sql.ts(shard.createdAt()))
                    .update();
        }
    }

    @Override
    public List<BackingAccount> findAll(TenantId tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM backing_account WHERE tenant_id = :tenant"
                        + " ORDER BY CASE type WHEN 'PIX_PI' THEN 0 WHEN 'CASH' THEN 1 WHEN 'UNASSIGNED' THEN 2 ELSE 3 END,"
                        + " created_at, id")
                .param("tenant", tenantId.value())
                .query(JdbcBackingAccountRepository::map)
                .list();
    }

    @Override
    public Optional<BackingAccount> findById(TenantId tenantId, BackingAccountId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM backing_account WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId.value())
                .param("id", id.value())
                .query(JdbcBackingAccountRepository::map)
                .optional();
    }

    @Override
    public List<AccountId> findShardIds(TenantId tenantId, BackingAccountId id) {
        return jdbc.sql("""
                SELECT id FROM account
                 WHERE tenant_id = :tenant AND kind = 'BACKING' AND backing_account_id = :backing
                 ORDER BY id""")
                .param("tenant", tenantId.value())
                .param("backing", id.value())
                .query((rs, i) -> new AccountId(Sql.uuid(rs, "id")))
                .list();
    }

    @Override
    public Map<AccountId, BackingAccountId> findShardOwners(TenantId tenantId) {
        Map<AccountId, BackingAccountId> owners = new HashMap<>();
        jdbc.sql("SELECT id, backing_account_id FROM account WHERE tenant_id = :tenant AND kind = 'BACKING'")
                .param("tenant", tenantId.value())
                .query(rs -> {
                    owners.put(new AccountId(Sql.uuid(rs, "id")), new BackingAccountId(Sql.uuid(rs, "backing_account_id")));
                });
        return owners;
    }

    @Override
    public Map<BackingAccountId, Long> sumBalances(TenantId tenantId) {
        Map<BackingAccountId, Long> sums = new HashMap<>();
        jdbc.sql("""
                SELECT backing_account_id, SUM(balance_cents) AS total FROM account
                 WHERE tenant_id = :tenant AND kind = 'BACKING'
                 GROUP BY backing_account_id""")
                .param("tenant", tenantId.value())
                .query(rs -> {
                    sums.put(new BackingAccountId(Sql.uuid(rs, "backing_account_id")), rs.getLong("total"));
                });
        return sums;
    }

    @Override
    public long lockAndSumBalance(TenantId tenantId, BackingAccountId id) {
        // FOR UPDATE cannot sit next to an aggregate: lock the rows (in id order, as every posting locks), then add.
        return jdbc.sql("""
                SELECT balance_cents FROM account
                 WHERE tenant_id = :tenant AND kind = 'BACKING' AND backing_account_id = :backing
                 ORDER BY id
                   FOR UPDATE""")
                .param("tenant", tenantId.value())
                .param("backing", id.value())
                .query(Long.class)
                .list()
                .stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public void insertTreasuryReference(TenantId tenantId, TransactionId transactionId, TreasuryReference reference) {
        jdbc.sql("""
                INSERT INTO treasury_transfer_detail (transaction_id, tenant_id, document, note)
                VALUES (:tx, :tenant, :document, :note)""")
                .param("tx", transactionId.value())
                .param("tenant", tenantId.value())
                .param("document", reference.document(), Types.VARCHAR)
                .param("note", reference.note(), Types.VARCHAR)
                .update();
    }

    @Override
    public Map<TransactionId, TreasuryReference> findTreasuryReferences(TenantId tenantId,
                                                                        Collection<TransactionId> ids) {
        Map<TransactionId, TreasuryReference> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.sql("""
                SELECT transaction_id, document, note FROM treasury_transfer_detail
                 WHERE tenant_id = :tenant AND transaction_id IN (:ids)
                UNION ALL
                SELECT transaction_id, document, note FROM revenue_withdrawal_detail
                 WHERE tenant_id = :tenant AND transaction_id IN (:ids)""")
                .param("tenant", tenantId.value())
                .param("ids", ids.stream().map(TransactionId::value).toList())
                .query(rs -> {
                    found.put(new TransactionId(Sql.uuid(rs, "transaction_id")),
                            new TreasuryReference(rs.getString("document"), rs.getString("note")));
                });
        return found;
    }

    @Override
    public void insertRevenueWithdrawal(TenantId tenantId, RevenueWithdrawal w) {
        jdbc.sql("""
                INSERT INTO revenue_withdrawal_detail (transaction_id, tenant_id, backing_account_id, destination_bank,
                                                       destination_branch, destination_account, document, note,
                                                       operator, client_id)
                VALUES (:tx, :tenant, :backing, :bank, :branch, :account, :document, :note, :operator, :client)""")
                .param("tx", w.transactionId().value())
                .param("tenant", tenantId.value())
                .param("backing", w.from().value())
                .param("bank", w.destination().bank())
                .param("branch", w.destination().branch())
                .param("account", w.destination().account())
                .param("document", w.document())
                .param("note", w.note(), Types.VARCHAR)
                .param("operator", w.actor().operator())
                .param("client", w.actor().clientId())
                .update();
    }

    @Override
    public Map<TransactionId, RevenueWithdrawal> findRevenueWithdrawals(TenantId tenantId,
                                                                         Collection<TransactionId> ids) {
        Map<TransactionId, RevenueWithdrawal> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.sql("""
                SELECT * FROM revenue_withdrawal_detail
                 WHERE tenant_id = :tenant AND transaction_id IN (:ids)""")
                .param("tenant", tenantId.value())
                .param("ids", ids.stream().map(TransactionId::value).toList())
                .query(rs -> {
                    TransactionId id = new TransactionId(Sql.uuid(rs, "transaction_id"));
                    found.put(id, new RevenueWithdrawal(id, new BackingAccountId(Sql.uuid(rs, "backing_account_id")),
                            new RevenueWithdrawal.Destination(rs.getString("destination_bank"),
                                    rs.getString("destination_branch"), rs.getString("destination_account")),
                            rs.getString("document"), rs.getString("note"),
                            new Actor(rs.getString("operator"), rs.getString("client_id"))));
                });
        return found;
    }

    @Override
    public Money sumRevenueWithdrawnSince(TenantId tenantId, Instant since) {
        Long cents = jdbc.sql("""
                SELECT coalesce(sum(t.amount_cents), 0) FROM revenue_withdrawal_detail d
                  JOIN financial_transaction t ON t.id = d.transaction_id
                 WHERE d.tenant_id = :tenant AND t.occurred_at >= :since""")
                .param("tenant", tenantId.value())
                .param("since", Sql.ts(since))
                .query(Long.class).single();
        return Money.ofCents(cents);
    }

    private static BackingAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new BackingAccount(
                new BackingAccountId(Sql.uuid(rs, "id")),
                new TenantId(Sql.uuid(rs, "tenant_id")),
                BackingType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("external_ref"),
                Sql.instant(rs, "created_at"));
    }
}
