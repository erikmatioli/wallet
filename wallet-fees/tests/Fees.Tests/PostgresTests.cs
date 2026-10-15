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

    [Fact]
    public async Task MigratingTwiceChangesNothing()
    {
        Migrator.Migrate(postgres.GetConnectionString());
        await using var cmd = dataSource.CreateCommand("SELECT count(*) FROM schemaversions");
        Assert.Equal(1L, (long)(await cmd.ExecuteScalarAsync())!);
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
