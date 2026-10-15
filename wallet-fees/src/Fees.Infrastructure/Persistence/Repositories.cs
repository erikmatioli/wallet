using Dapper;
using Fees.Application;
using Fees.Domain;

namespace Fees.Infrastructure.Persistence;

public sealed class PackageRepository(DbSession db) : IPackageRepository
{
    private const string Columns = "id, tenant_id, code, name, customer_type, is_default, created_at";

    public Task LockTenantAsync(Guid tenantId, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition(
            "SELECT pg_advisory_xact_lock(hashtextextended('wallet-fees:tenant:' || @tenantId::text, 0))",
            new { tenantId }, db.Transaction, cancellationToken: ct));

    public async Task<IReadOnlyList<Package>> ListAsync(Guid tenantId, CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<PackageRow>(new CommandDefinition(
            $"SELECT {Columns} FROM package WHERE tenant_id = @tenantId ORDER BY customer_type, is_default DESC, created_at, code",
            new { tenantId }, db.Transaction, cancellationToken: ct))).Select(r => r.ToDomain()).ToList();

    public async Task<Package?> FindAsync(Guid tenantId, Guid packageId, CancellationToken ct = default) =>
        (await db.Connection.QuerySingleOrDefaultAsync<PackageRow>(new CommandDefinition(
            $"SELECT {Columns} FROM package WHERE tenant_id = @tenantId AND id = @packageId",
            new { tenantId, packageId }, db.Transaction, cancellationToken: ct)))?.ToDomain();

    public async Task<Package?> FindDefaultAsync(Guid tenantId, CustomerType type, CancellationToken ct = default) =>
        (await db.Connection.QuerySingleOrDefaultAsync<PackageRow>(new CommandDefinition(
            $"SELECT {Columns} FROM package WHERE tenant_id = @tenantId AND customer_type = @type AND is_default",
            new { tenantId, type = type.ToString() }, db.Transaction, cancellationToken: ct)))?.ToDomain();

    public async Task<IReadOnlyList<Guid>> TenantsAsync(CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<Guid>(new CommandDefinition(
            "SELECT DISTINCT tenant_id FROM package ORDER BY tenant_id", null, db.Transaction,
            cancellationToken: ct))).ToList();

    public Task InsertAsync(Package p, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO package (id, tenant_id, code, name, customer_type, is_default, created_at)
            VALUES (@Id, @TenantId, @Code, @Name, @CustomerType, @IsDefault, @CreatedAt)
            """, new { p.Id, p.TenantId, p.Code, p.Name, CustomerType = p.CustomerType.ToString(), p.IsDefault, p.CreatedAt },
            db.Transaction, cancellationToken: ct));

    private sealed record PackageRow(Guid id, Guid tenant_id, string code, string name, string customer_type,
        bool is_default, DateTime created_at)
    {
        public Package ToDomain() => new(id, tenant_id, code, name, Enum.Parse<CustomerType>(customer_type), is_default,
            Utc(created_at));
    }

    internal static DateTimeOffset Utc(DateTime value) => new(DateTime.SpecifyKind(value, DateTimeKind.Utc));
}

public sealed class ScheduleRepository(DbSession db) : IScheduleRepository
{
    public async Task<IReadOnlyList<FeeSchedule>> ListAsync(Guid tenantId, Guid packageId, CancellationToken ct = default)
    {
        var schedules = (await db.Connection.QueryAsync<ScheduleRow>(new CommandDefinition("""
            SELECT id, tenant_id, package_id, valid_from, monthly_fee_cents, created_by, created_at
              FROM fee_schedule
             WHERE tenant_id = @tenantId AND package_id = @packageId
             ORDER BY valid_from
            """, new { tenantId, packageId }, db.Transaction, cancellationToken: ct))).ToList();
        if (schedules.Count == 0)
        {
            return [];
        }
        var prices = (await db.Connection.QueryAsync<PriceRow>(new CommandDefinition("""
            SELECT schedule_id, operation, free_per_month, price_cents, price_from_company_cents
              FROM fee_schedule_price
             WHERE schedule_id = ANY(@ids)
            """, new { ids = schedules.Select(s => s.id).ToArray() }, db.Transaction, cancellationToken: ct)))
            .ToLookup(p => p.schedule_id);
        return schedules.Select(s => new FeeSchedule(s.id, s.tenant_id, s.package_id, DateOnly.FromDateTime(s.valid_from),
            s.monthly_fee_cents,
            prices[s.id].Select(p => new OperationPrice(Enum.Parse<Operation>(p.operation), p.free_per_month,
                p.price_cents, p.price_from_company_cents)).OrderBy(p => p.Operation).ToList(),
            s.created_by, PackageRepository.Utc(s.created_at))).ToList();
    }

    public async Task InsertAsync(FeeSchedule s, CancellationToken ct = default)
    {
        await db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO fee_schedule (id, tenant_id, package_id, valid_from, monthly_fee_cents, created_by, created_at)
            VALUES (@Id, @TenantId, @PackageId, @ValidFrom, @MonthlyFeeCents, @CreatedBy, @CreatedAt)
            """, new { s.Id, s.TenantId, s.PackageId, ValidFrom = s.ValidFrom.ToDateTime(TimeOnly.MinValue), s.MonthlyFeeCents,
            s.CreatedBy, s.CreatedAt }, db.Transaction, cancellationToken: ct));
        foreach (var p in s.Prices)
        {
            await db.Connection.ExecuteAsync(new CommandDefinition("""
                INSERT INTO fee_schedule_price (schedule_id, operation, free_per_month, price_cents, price_from_company_cents)
                VALUES (@scheduleId, @operation, @FreePerMonth, @PriceCents, @PriceFromCompanyCents)
                """, new { scheduleId = s.Id, operation = p.Operation.ToString(), p.FreePerMonth, p.PriceCents,
                p.PriceFromCompanyCents }, db.Transaction, cancellationToken: ct));
        }
    }

    public Task DeleteAsync(Guid tenantId, Guid scheduleId, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition(
            "DELETE FROM fee_schedule WHERE tenant_id = @tenantId AND id = @scheduleId",
            new { tenantId, scheduleId }, db.Transaction, cancellationToken: ct));

    private sealed record ScheduleRow(Guid id, Guid tenant_id, Guid package_id, DateTime valid_from,
        long monthly_fee_cents, string created_by, DateTime created_at);

    private sealed record PriceRow(Guid schedule_id, string operation, int free_per_month, long price_cents,
        long? price_from_company_cents);
}

public sealed class AccountRepository(DbSession db) : IAccountRepository
{
    public async Task<FeeAccount?> FindAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        (await db.Connection.QuerySingleOrDefaultAsync<AccountRow>(new CommandDefinition("""
            SELECT tenant_id, account_id, customer_type, package_id, status, since
              FROM fee_account WHERE tenant_id = @tenantId AND account_id = @accountId
            """, new { tenantId, accountId }, db.Transaction, cancellationToken: ct)))?.ToDomain();

    public async Task<bool> InsertIfAbsentAsync(FeeAccount a, CancellationToken ct = default) =>
        await db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO fee_account (tenant_id, account_id, customer_type, package_id, status, since)
            VALUES (@TenantId, @AccountId, @CustomerType, @PackageId, @Status, @Since)
            ON CONFLICT (account_id) DO NOTHING
            """, Params(a), db.Transaction, cancellationToken: ct)) == 1;

    public Task UpdateAsync(FeeAccount a, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition("""
            UPDATE fee_account SET package_id = @PackageId, status = @Status, since = @Since
             WHERE tenant_id = @TenantId AND account_id = @AccountId
            """, Params(a), db.Transaction, cancellationToken: ct));

    public async Task<IReadOnlyList<Guid>> ActiveInPackageAsync(Guid tenantId, Guid packageId,
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<Guid>(new CommandDefinition("""
            SELECT account_id FROM fee_account
             WHERE tenant_id = @tenantId AND package_id = @packageId AND status = 'ACTIVE'
             ORDER BY account_id
            """, new { tenantId, packageId }, db.Transaction, cancellationToken: ct))).ToList();

    public Task InsertChangeAsync(PackageChange c, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO package_change (tenant_id, account_id, from_package_id, to_package_id, operator, client_id, changed_at)
            VALUES (@TenantId, @AccountId, @FromPackageId, @ToPackageId, @Operator, @ClientId, @ChangedAt)
            """, c, db.Transaction, cancellationToken: ct));

    public async Task<IReadOnlyList<PackageChange>> ChangesAsync(Guid tenantId, Guid accountId,
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<ChangeRow>(new CommandDefinition("""
            SELECT tenant_id, account_id, from_package_id, to_package_id, operator, client_id, changed_at
              FROM package_change WHERE tenant_id = @tenantId AND account_id = @accountId
             ORDER BY changed_at DESC, id DESC
            """, new { tenantId, accountId }, db.Transaction, cancellationToken: ct)))
        .Select(r => new PackageChange(r.tenant_id, r.account_id, r.from_package_id, r.to_package_id, r.@operator,
            r.client_id, PackageRepository.Utc(r.changed_at))).ToList();

    private static object Params(FeeAccount a) => new
    {
        a.TenantId, a.AccountId, CustomerType = a.CustomerType.ToString(), a.PackageId, Status = a.Status.ToString(),
        a.Since,
    };

    private sealed record AccountRow(Guid tenant_id, Guid account_id, string customer_type, Guid package_id,
        string status, DateTime since)
    {
        public FeeAccount ToDomain() => new(tenant_id, account_id, Enum.Parse<CustomerType>(customer_type), package_id,
            Enum.Parse<AccountStatus>(status), PackageRepository.Utc(since));
    }

    private sealed record ChangeRow(Guid tenant_id, Guid account_id, Guid? from_package_id, Guid to_package_id,
        string @operator, string client_id, DateTime changed_at);
}

public sealed class ProcessedMessages(DbSession db) : IProcessedMessages
{
    public async Task<bool> TryMarkAsync(Guid messageId, CancellationToken ct = default) =>
        await db.Connection.ExecuteAsync(new CommandDefinition(
            "INSERT INTO processed_message (message_id) VALUES (@messageId) ON CONFLICT DO NOTHING",
            new { messageId }, db.Transaction, cancellationToken: ct)) == 1;
}

public sealed class UsageRepository(DbSession db) : IUsageRepository
{
    public Task LockAccountAsync(Guid accountId, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition(
            "SELECT pg_advisory_xact_lock(hashtextextended('wallet-fees:account:' || @accountId::text, 0))",
            new { accountId }, db.Transaction, cancellationToken: ct));

    public async Task<bool> RecordAsync(Guid tenantId, Guid accountId, string month, Operation operation,
        Guid sourceTransactionId, DateTimeOffset occurredAt, CancellationToken ct = default) =>
        await db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO fee_usage (tenant_id, account_id, month, operation, source_transaction_id, occurred_at)
            VALUES (@tenantId, @accountId, @month, @operation, @sourceTransactionId, @occurredAt)
            ON CONFLICT DO NOTHING
            """, new { tenantId, accountId, month, operation = operation.ToString(), sourceTransactionId, occurredAt },
            db.Transaction, cancellationToken: ct)) == 1;

    public Task<int> CountAsync(Guid accountId, string month, Operation operation, CancellationToken ct = default) =>
        db.Connection.ExecuteScalarAsync<int>(new CommandDefinition("""
            SELECT count(*)::int FROM fee_usage
             WHERE account_id = @accountId AND month = @month AND operation = @operation AND NOT reversed
            """, new { accountId, month, operation = operation.ToString() }, db.Transaction, cancellationToken: ct));

    public async Task MarkReversedAsync(Guid tenantId, Guid transactionId, DateTimeOffset at,
        CancellationToken ct = default)
    {
        await db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO reversed_transaction (transaction_id, tenant_id, reversed_at)
            VALUES (@transactionId, @tenantId, @at) ON CONFLICT DO NOTHING
            """, new { transactionId, tenantId, at }, db.Transaction, cancellationToken: ct));
        await db.Connection.ExecuteAsync(new CommandDefinition(
            "UPDATE fee_usage SET reversed = true WHERE tenant_id = @tenantId AND source_transaction_id = @transactionId",
            new { tenantId, transactionId }, db.Transaction, cancellationToken: ct));
    }

    public Task<bool> IsReversedAsync(Guid transactionId, CancellationToken ct = default) =>
        db.Connection.ExecuteScalarAsync<bool>(new CommandDefinition(
            "SELECT EXISTS (SELECT 1 FROM reversed_transaction WHERE transaction_id = @transactionId)",
            new { transactionId }, db.Transaction, cancellationToken: ct));
}

public sealed class ChargeRepository(DbSession db) : IChargeRepository
{
    private const string Columns = """
        id, tenant_id, account_id, operation, source_transaction_id, reference, amount_cents, description,
        idempotency_key, status, core_transaction_id, attempts, last_error, created_at, updated_at,
        refund_transaction_id
        """;

    public Task InsertAsync(FeeCharge c, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition($"""
            INSERT INTO fee_charge ({Columns})
            VALUES (@Id, @TenantId, @AccountId, @Operation, @SourceTransactionId, @Reference, @AmountCents, @Description,
                    @IdempotencyKey, @Status, @CoreTransactionId, @Attempts, @LastError, @CreatedAt, @UpdatedAt,
                    @RefundTransactionId)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, Params(c), db.Transaction, cancellationToken: ct));

    public Task UpdateAsync(FeeCharge c, CancellationToken ct = default) =>
        db.Connection.ExecuteAsync(new CommandDefinition("""
            UPDATE fee_charge SET status = @Status, core_transaction_id = @CoreTransactionId, attempts = @Attempts,
                   last_error = @LastError, updated_at = @UpdatedAt, refund_transaction_id = @RefundTransactionId
             WHERE tenant_id = @TenantId AND id = @Id
            """, Params(c), db.Transaction, cancellationToken: ct));

    public async Task<IReadOnlyList<FeeCharge>> PendingAsync(Guid tenantId, Guid accountId,
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<ChargeRow>(new CommandDefinition($"""
            SELECT {Columns} FROM fee_charge
             WHERE tenant_id = @tenantId AND account_id = @accountId AND status = 'PENDING'
             ORDER BY created_at, id
            """, new { tenantId, accountId }, db.Transaction, cancellationToken: ct))).Select(r => r.ToDomain()).ToList();

    public async Task<IReadOnlyList<FeeCharge>> ListAsync(Guid tenantId, Guid accountId, int limit,
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<ChargeRow>(new CommandDefinition($"""
            SELECT {Columns} FROM fee_charge
             WHERE tenant_id = @tenantId AND account_id = @accountId
             ORDER BY created_at DESC, id DESC LIMIT @limit
            """, new { tenantId, accountId, limit }, db.Transaction, cancellationToken: ct)))
        .Select(r => r.ToDomain()).ToList();

    public async Task<IReadOnlyList<FeeCharge>> OfSourceAsync(Guid tenantId, Guid sourceTransactionId,
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<ChargeRow>(new CommandDefinition($"""
            SELECT {Columns} FROM fee_charge
             WHERE tenant_id = @tenantId AND source_transaction_id = @sourceTransactionId
             ORDER BY created_at, id
            """, new { tenantId, sourceTransactionId }, db.Transaction, cancellationToken: ct)))
        .Select(r => r.ToDomain()).ToList();

    public async Task<IReadOnlyList<(Guid TenantId, Guid AccountId)>> AccountsWithPendingAsync(
        CancellationToken ct = default) =>
        (await db.Connection.QueryAsync<(Guid, Guid)>(new CommandDefinition(
            "SELECT DISTINCT tenant_id, account_id FROM fee_charge WHERE status = 'PENDING'",
            null, db.Transaction, cancellationToken: ct))).ToList();

    private static object Params(FeeCharge c) => new
    {
        c.Id, c.TenantId, c.AccountId, c.Operation, c.SourceTransactionId, c.Reference, c.AmountCents, c.Description,
        c.IdempotencyKey, Status = c.Status.ToString(), c.CoreTransactionId, c.Attempts, c.LastError, c.CreatedAt,
        c.UpdatedAt, c.RefundTransactionId,
    };

    private sealed record ChargeRow(Guid id, Guid tenant_id, Guid account_id, string operation,
        Guid? source_transaction_id, string? reference, long amount_cents, string description, string idempotency_key,
        string status, Guid? core_transaction_id, int attempts, string? last_error, DateTime created_at,
        DateTime updated_at, Guid? refund_transaction_id)
    {
        public FeeCharge ToDomain() => new(id, tenant_id, account_id, operation, source_transaction_id, reference,
            amount_cents, description, idempotency_key, Enum.Parse<ChargeStatus>(status), core_transaction_id, attempts,
            last_error, PackageRepository.Utc(created_at), PackageRepository.Utc(updated_at), refund_transaction_id);
    }
}

public sealed class MonthlyRuns(DbSession db) : IMonthlyRuns
{
    public Task<string?> LastAsync(Guid tenantId, CancellationToken ct = default) =>
        db.Connection.ExecuteScalarAsync<string?>(new CommandDefinition(
            "SELECT max(month) FROM monthly_run WHERE tenant_id = @tenantId", new { tenantId }, db.Transaction,
            cancellationToken: ct));

    public async Task<bool> MarkAsync(Guid tenantId, string month, DateTimeOffset at, CancellationToken ct = default) =>
        await db.Connection.ExecuteAsync(new CommandDefinition("""
            INSERT INTO monthly_run (tenant_id, month, ran_at) VALUES (@tenantId, @month, @at) ON CONFLICT DO NOTHING
            """, new { tenantId, month, at }, db.Transaction, cancellationToken: ct)) == 1;
}
