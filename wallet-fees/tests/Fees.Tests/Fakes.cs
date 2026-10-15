using Fees.Application;
using Fees.Domain;

namespace Fees.Tests;

// The ports in memory, for fast use-case tests (the same idea as the core's InMemoryFixture).
internal sealed class Fixture
{
    public readonly Guid Tenant = Guid.CreateVersion7();
    public readonly ManualClock Clock = new(new DateTimeOffset(2026, 10, 15, 15, 0, 0, TimeSpan.Zero));
    public readonly List<Package> Packages = [];
    public readonly List<FeeSchedule> Schedules = [];
    public readonly Dictionary<Guid, FeeAccount> Accounts = [];
    public readonly List<PackageChange> Changes = [];
    public readonly HashSet<Guid> Processed = [];

    public readonly PackageService PackageService;
    public readonly AccountService AccountService;

    public Fixture()
    {
        var tx = new DirectRunner();
        var packages = new FakePackages(this);
        PackageService = new PackageService(tx, packages, new FakeSchedules(this), Clock);
        AccountService = new AccountService(tx, new FakeAccounts(this), packages, new FakeProcessed(this),
            PackageService, Clock);
    }

    public Package PackageOf(string code) => Packages.Single(p => p.TenantId == Tenant && p.Code == code);

    public DateOnly Today => Brasilia.Today(Clock);

    private sealed class DirectRunner : ITransactionRunner
    {
        public Task<T> InTransactionAsync<T>(Func<Task<T>> work, CancellationToken ct = default) => work();
    }

    private sealed class FakePackages(Fixture f) : IPackageRepository
    {
        public Task LockTenantAsync(Guid tenantId, CancellationToken ct = default) => Task.CompletedTask;

        public Task<IReadOnlyList<Package>> ListAsync(Guid tenantId, CancellationToken ct = default) =>
            Task.FromResult<IReadOnlyList<Package>>(f.Packages.Where(p => p.TenantId == tenantId).ToList());

        public Task<Package?> FindAsync(Guid tenantId, Guid packageId, CancellationToken ct = default) =>
            Task.FromResult(f.Packages.FirstOrDefault(p => p.TenantId == tenantId && p.Id == packageId));

        public Task<Package?> FindDefaultAsync(Guid tenantId, CustomerType type, CancellationToken ct = default) =>
            Task.FromResult(f.Packages.FirstOrDefault(p => p.TenantId == tenantId && p.CustomerType == type && p.IsDefault));

        public Task InsertAsync(Package package, CancellationToken ct = default)
        {
            f.Packages.Add(package);
            return Task.CompletedTask;
        }
    }

    private sealed class FakeSchedules(Fixture f) : IScheduleRepository
    {
        public Task<IReadOnlyList<FeeSchedule>> ListAsync(Guid tenantId, Guid packageId, CancellationToken ct = default) =>
            Task.FromResult<IReadOnlyList<FeeSchedule>>(f.Schedules
                .Where(s => s.TenantId == tenantId && s.PackageId == packageId).OrderBy(s => s.ValidFrom).ToList());

        public Task InsertAsync(FeeSchedule schedule, CancellationToken ct = default)
        {
            if (f.Schedules.Any(s => s.PackageId == schedule.PackageId && s.ValidFrom == schedule.ValidFrom))
            {
                throw new InvalidOperationException("unique (package_id, valid_from)");
            }
            f.Schedules.Add(schedule);
            return Task.CompletedTask;
        }

        public Task DeleteAsync(Guid tenantId, Guid scheduleId, CancellationToken ct = default)
        {
            f.Schedules.RemoveAll(s => s.TenantId == tenantId && s.Id == scheduleId);
            return Task.CompletedTask;
        }
    }

    private sealed class FakeAccounts(Fixture f) : IAccountRepository
    {
        public Task<FeeAccount?> FindAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
            Task.FromResult(f.Accounts.TryGetValue(accountId, out var a) && a.TenantId == tenantId ? a : null);

        public Task<bool> InsertIfAbsentAsync(FeeAccount account, CancellationToken ct = default) =>
            Task.FromResult(f.Accounts.TryAdd(account.AccountId, account));

        public Task UpdateAsync(FeeAccount account, CancellationToken ct = default)
        {
            f.Accounts[account.AccountId] = account;
            return Task.CompletedTask;
        }

        public Task InsertChangeAsync(PackageChange change, CancellationToken ct = default)
        {
            f.Changes.Add(change);
            return Task.CompletedTask;
        }

        public Task<IReadOnlyList<PackageChange>> ChangesAsync(Guid tenantId, Guid accountId,
            CancellationToken ct = default) =>
            Task.FromResult<IReadOnlyList<PackageChange>>(f.Changes
                .Where(c => c.TenantId == tenantId && c.AccountId == accountId)
                .OrderByDescending(c => c.ChangedAt).ToList());
    }

    private sealed class FakeProcessed(Fixture f) : IProcessedMessages
    {
        public Task<bool> TryMarkAsync(Guid messageId, CancellationToken ct = default) =>
            Task.FromResult(f.Processed.Add(messageId));
    }
}

// A clock the test moves by hand.
internal sealed class ManualClock(DateTimeOffset now) : TimeProvider
{
    public DateTimeOffset Now { get; set; } = now;

    public override DateTimeOffset GetUtcNow() => Now;
}
