using Fees.Application;
using Fees.Domain;
using Fees.Infrastructure.Persistence;
using Npgsql;
using Testcontainers.PostgreSql;

namespace Fees.Tests;

// The migrations and the repositories against a real PostgreSQL (Testcontainers, so Docker must be running).
public sealed class PostgresTests : IAsyncLifetime
{
    private readonly PostgreSqlContainer postgres = new PostgreSqlBuilder("postgres:17-alpine").Build();
    private NpgsqlDataSource dataSource = null!;
    private readonly ManualClock clock = new(new DateTimeOffset(2026, 10, 15, 15, 0, 0, TimeSpan.Zero));

    public async Task InitializeAsync()
    {
        await postgres.StartAsync();
        Migrator.Migrate(postgres.GetConnectionString());
        dataSource = NpgsqlDataSource.Create(postgres.GetConnectionString());
    }

    public async Task DisposeAsync()
    {
        await dataSource.DisposeAsync();
        await postgres.DisposeAsync();
    }

    // One unit of work, as a request or a message has: its own session and transaction.
    private async Task<T> InScopeAsync<T>(Func<PackageService, AccountService, Task<T>> work)
    {
        await using var session = new DbSession(dataSource);
        var tx = new TransactionRunner(session);
        var packages = new PackageRepository(session);
        var packageService = new PackageService(tx, packages, new ScheduleRepository(session), clock);
        var accountService = new AccountService(tx, new AccountRepository(session), packages,
            new ProcessedMessages(session), packageService, clock);
        return await work(packageService, accountService);
    }

    // The same, for charging: the core is the fake one, shared by every scope.
    private async Task<T> InChargeScopeAsync<T>(ICoreFees core, Func<ChargeService, AccountService, Task<T>> work)
    {
        await using var session = new DbSession(dataSource);
        var tx = new TransactionRunner(session);
        var packages = new PackageRepository(session);
        var schedules = new ScheduleRepository(session);
        var accounts = new AccountRepository(session);
        var processed = new ProcessedMessages(session);
        var accountService = new AccountService(tx, accounts, packages, processed,
            new PackageService(tx, packages, schedules, clock), clock);
        var charges = new ChargeService(tx, accounts, schedules, new UsageRepository(session),
            new ChargeRepository(session), processed, core, accountService, clock);
        return await work(charges, accountService);
    }

    [Fact]
    public async Task TransfersAtOnceTakeEachFreeSlotOnceAndAreChargedOnce()
    {
        var tenant = Guid.CreateVersion7();
        var alice = Guid.CreateVersion7();
        var bob = Guid.CreateVersion7();
        var core = new SyncedCore();
        core.Balances[alice] = 10_000;
        await InChargeScopeAsync(core, async (_, accounts) =>
        {
            await accounts.OnOpenedAsync(Guid.CreateVersion7(), tenant, alice, "CPF", clock.GetUtcNow());
            await accounts.OnOpenedAsync(Guid.CreateVersion7(), tenant, bob, "CPF", clock.GetUtcNow());
            return true;
        });
        var transfers = Enumerable.Range(0, 6).Select(_ => new PostedTransaction(Guid.CreateVersion7(), tenant,
            Guid.CreateVersion7(), "TRANSFER", [new PostedEntry(alice, "DEBIT", 500), new PostedEntry(bob, "CREDIT", 500)],
            clock.GetUtcNow(), null, false, null)).ToList();

        // Each twice, all at once: the bus delivers at least once.
        await Task.WhenAll(transfers.Concat(transfers).Select(t =>
            InChargeScopeAsync(core, async (charges, _) =>
            {
                await charges.OnTransactionPostedAsync(t);
                return true;
            })));

        var view = await InChargeScopeAsync(core, (charges, _) => charges.ViewAsync(tenant, alice));
        Assert.Equal(6, view.Usage.Single(u => u.Operation == Operation.TRANSFER_OUT).Used);
        Assert.Equal(4, view.Charges.Count); // PF basic: two free
        Assert.All(view.Charges, c => Assert.Equal(ChargeStatus.CHARGED, c.Status));
        Assert.Equal(0, view.PendingCents);
        Assert.Equal(9_600, core.Balances[alice]);
    }

    [Fact]
    public async Task PendingFeesSurviveInTheDatabaseAndAreChargedOldestFirst()
    {
        var tenant = Guid.CreateVersion7();
        var alice = Guid.CreateVersion7();
        var core = new SyncedCore();
        await InChargeScopeAsync(core, (_, accounts) =>
            accounts.OnOpenedAsync(Guid.CreateVersion7(), tenant, alice, "CPF", clock.GetUtcNow()).ContinueWith(_ => true));
        foreach (var type in new[] { "PIX_OUT", "WITHDRAWAL" })
        {
            var t = new PostedTransaction(Guid.CreateVersion7(), tenant, Guid.CreateVersion7(), type,
                [new PostedEntry(alice, "DEBIT", 100), new PostedEntry(Guid.CreateVersion7(), "CREDIT", 100)],
                clock.GetUtcNow(), null, false, null);
            await InChargeScopeAsync(core, (charges, _) => charges.OnTransactionPostedAsync(t).ContinueWith(_ => true));
            clock.Now = clock.Now.AddSeconds(1);
        }
        Assert.Contains(alice, core.FeeBlocks);
        var pending = await InChargeScopeAsync(core, (charges, _) => charges.AccountsWithPendingAsync());
        Assert.Equal([(tenant, alice)], pending);

        core.Balances[alice] = 160; // the 1.50 fits, the 1.00 after it does not
        await InChargeScopeAsync(core, (charges, _) => charges.ChargePendingAsync(tenant, alice));

        var view = await InChargeScopeAsync(core, (charges, _) => charges.ViewAsync(tenant, alice));
        Assert.Equal([ChargeStatus.PENDING, ChargeStatus.CHARGED], view.Charges.Select(c => c.Status).ToArray());
        Assert.Equal("PIX_OUT", view.Charges[1].Operation);
        Assert.Equal(100, view.PendingCents);
    }

    // FakeCore is for one thread; the concurrency test calls it from several.
    private sealed class SyncedCore : ICoreFees
    {
        private readonly FakeCore inner = new();
        private readonly Lock gate = new();

        public Dictionary<Guid, long> Balances => inner.Balances;
        public HashSet<Guid> FeeBlocks => inner.FeeBlocks;

        public Task<CoreFeeResult> ChargeAsync(FeeCharge charge, CancellationToken ct = default)
        {
            lock (gate) { return inner.ChargeAsync(charge, ct); }
        }

        public Task EnsureFeeBlockAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
        {
            lock (gate) { return inner.EnsureFeeBlockAsync(tenantId, accountId, ct); }
        }

        public Task ReleaseFeeBlocksAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
        {
            lock (gate) { return inner.ReleaseFeeBlocksAsync(tenantId, accountId, ct); }
        }

        public Task<CoreAccount?> AccountAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
            Task.FromResult<CoreAccount?>(null);
    }

    [Fact]
    public async Task MigratingTwiceChangesNothing()
    {
        Migrator.Migrate(postgres.GetConnectionString());
        await using var cmd = dataSource.CreateCommand("SELECT count(*) FROM schemaversions");
        Assert.Equal(2L, (long)(await cmd.ExecuteScalarAsync())!);
    }

    [Fact]
    public async Task TwoFirstRequestsAtOnceCreateTheTenantsPackagesOnce()
    {
        var tenant = Guid.CreateVersion7();

        await Task.WhenAll(Enumerable.Range(0, 4).Select(_ =>
            InScopeAsync(async (packages, _) =>
            {
                await packages.EnsureDefaultsAsync(tenant);
                return true;
            })));

        var list = await InScopeAsync((packages, _) => packages.ListAsync(tenant));
        Assert.Equal(6, list.Count);
        Assert.All(list, p => Assert.NotNull(p.Current));
    }

    [Fact]
    public async Task SchedulesAndAccountsRoundTrip()
    {
        var tenant = Guid.CreateVersion7();
        var account = Guid.CreateVersion7();
        var eventId = Guid.CreateVersion7();
        var nextMonth = new DateOnly(2026, 11, 1);

        var vip = await InScopeAsync(async (packages, accounts) =>
        {
            await accounts.OnOpenedAsync(eventId, tenant, account, "CNPJ", clock.GetUtcNow());
            await accounts.OnOpenedAsync(eventId, tenant, account, "CNPJ", clock.GetUtcNow());
            var pjVip = (await packages.ListAsync(tenant)).Single(p => p.Package.Code == "PJ_VIP").Package;
            await packages.AddScheduleAsync(tenant, pjVip.Id, new ScheduleCommand(nextMonth, 4990,
                [new OperationPrice(Operation.PIX_IN, 10, 50, 120), new OperationPrice(Operation.PIX_OUT, 0, 100, null)],
                "maria"));
            return pjVip;
        });
        var view = await InScopeAsync((_, accounts) =>
            accounts.ChangePackageAsync(tenant, account, vip.Id, "maria", "demo-tenant"));
        var schedules = await InScopeAsync((packages, _) => packages.SchedulesAsync(tenant, vip.Id));

        Assert.Equal("PJ_VIP", view.Package.Code);
        Assert.Equal(CustomerType.PJ, view.Account.CustomerType);
        Assert.Equal(2, view.Changes.Count);
        var november = schedules.Single(s => s.ValidFrom == nextMonth);
        Assert.Equal(4990, november.MonthlyFeeCents);
        Assert.Equal(120, november.PriceOf(Operation.PIX_IN).PriceFor(CustomerType.PJ));
        Assert.Equal(10, november.PriceOf(Operation.PIX_IN).FreePerMonth);
    }

    [Fact]
    public async Task ARefusedUnitOfWorkLeavesNothingBehind()
    {
        var tenant = Guid.CreateVersion7();
        var account = Guid.CreateVersion7();
        await InScopeAsync(async (_, accounts) =>
        {
            await accounts.OnOpenedAsync(Guid.CreateVersion7(), tenant, account, "CPF", clock.GetUtcNow());
            return true;
        });

        await Assert.ThrowsAsync<FeeNotFoundException>(() => InScopeAsync((_, accounts) =>
            accounts.ChangePackageAsync(tenant, account, Guid.CreateVersion7(), "maria", "demo-tenant")));

        var view = await InScopeAsync((_, accounts) => accounts.GetAsync(tenant, account));
        Assert.Equal("PF_BASIC", view.Package.Code);
        Assert.Single(view.Changes);
    }
}
