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
    public readonly List<(Guid AccountId, string Month, Operation Operation, Guid Source)> Usage = [];
    public readonly List<FeeCharge> Charges = [];
    public readonly FakeCore Core = new();

    public readonly PackageService PackageService;
    public readonly AccountService AccountService;
    public readonly ChargeService ChargeService;

    public Fixture()
    {
        var tx = new DirectRunner();
        var packages = new FakePackages(this);
        PackageService = new PackageService(tx, packages, new FakeSchedules(this), Clock);
        var accounts = new FakeAccounts(this);
        var schedules = new FakeSchedules(this);
        var processed = new FakeProcessed(this);
        AccountService = new AccountService(tx, accounts, packages, processed, PackageService, Clock);
        ChargeService = new ChargeService(tx, accounts, schedules, new FakeUsage(this), new FakeCharges(this),
            processed, Core, AccountService, Clock);
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

internal sealed class FakeUsage(Fixture f) : IUsageRepository
{
    public Task LockAccountAsync(Guid accountId, CancellationToken ct = default) => Task.CompletedTask;

    public Task<bool> RecordAsync(Guid tenantId, Guid accountId, string month, Operation operation,
        Guid sourceTransactionId, DateTimeOffset occurredAt, CancellationToken ct = default)
    {
        if (f.Usage.Any(u => u.Source == sourceTransactionId && u.Operation == operation))
        {
            return Task.FromResult(false);
        }
        f.Usage.Add((accountId, month, operation, sourceTransactionId));
        return Task.FromResult(true);
    }

    public Task<int> CountAsync(Guid accountId, string month, Operation operation, CancellationToken ct = default) =>
        Task.FromResult(f.Usage.Count(u => u.AccountId == accountId && u.Month == month && u.Operation == operation));
}

internal sealed class FakeCharges(Fixture f) : IChargeRepository
{
    public Task InsertAsync(FeeCharge charge, CancellationToken ct = default)
    {
        if (f.Charges.All(c => c.IdempotencyKey != charge.IdempotencyKey))
        {
            f.Charges.Add(charge);
        }
        return Task.CompletedTask;
    }

    public Task UpdateAsync(FeeCharge charge, CancellationToken ct = default)
    {
        f.Charges[f.Charges.FindIndex(c => c.Id == charge.Id)] = charge;
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<FeeCharge>> PendingAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<FeeCharge>>(f.Charges
            .Where(c => c.TenantId == tenantId && c.AccountId == accountId && c.Status == ChargeStatus.PENDING)
            .ToList());

    public Task<IReadOnlyList<FeeCharge>> ListAsync(Guid tenantId, Guid accountId, int limit,
        CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<FeeCharge>>(f.Charges
            .Where(c => c.TenantId == tenantId && c.AccountId == accountId).Reverse().Take(limit).ToList());

    public Task<IReadOnlyList<(Guid TenantId, Guid AccountId)>> AccountsWithPendingAsync(
        CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<(Guid, Guid)>>(f.Charges.Where(c => c.Status == ChargeStatus.PENDING)
            .Select(c => (c.TenantId, c.AccountId)).Distinct().ToList());
}

// The core as the fee basket sees it: balances, blocks, closed accounts, and the accounts it can be asked about.
internal sealed class FakeCore : ICoreFees
{
    public readonly Dictionary<Guid, long> Balances = [];
    public readonly HashSet<Guid> OtherBlocks = [];
    public readonly HashSet<Guid> FeeBlocks = [];
    public readonly HashSet<Guid> Closed = [];
    public readonly Dictionary<Guid, CoreAccount> Accounts = [];
    public readonly Dictionary<string, Guid> Charged = [];
    public int BlocksPut;
    public bool Down;

    public Task<CoreFeeResult> ChargeAsync(FeeCharge charge, CancellationToken ct = default)
    {
        if (Down)
        {
            return Task.FromResult(CoreFeeResult.Unavailable);
        }
        if (Charged.TryGetValue(charge.IdempotencyKey, out var replay))
        {
            return Task.FromResult(CoreFeeResult.Ok(replay));
        }
        if (Closed.Contains(charge.AccountId))
        {
            return Task.FromResult(CoreFeeResult.Refused("ACCOUNT_NOT_ACTIVE"));
        }
        if (OtherBlocks.Contains(charge.AccountId))
        {
            return Task.FromResult(CoreFeeResult.Refused("ACCOUNT_BLOCKED"));
        }
        var balance = Balances.GetValueOrDefault(charge.AccountId);
        if (balance < charge.AmountCents)
        {
            return Task.FromResult(CoreFeeResult.Refused("INSUFFICIENT_FUNDS"));
        }
        Balances[charge.AccountId] = balance - charge.AmountCents;
        var id = Guid.CreateVersion7();
        Charged[charge.IdempotencyKey] = id;
        return Task.FromResult(CoreFeeResult.Ok(id));
    }

    public Task EnsureFeeBlockAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
    {
        if (FeeBlocks.Add(accountId))
        {
            BlocksPut++;
        }
        return Task.CompletedTask;
    }

    public Task ReleaseFeeBlocksAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
    {
        FeeBlocks.Remove(accountId);
        return Task.CompletedTask;
    }

    public Task<CoreAccount?> AccountAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        Task.FromResult(Accounts.GetValueOrDefault(accountId));
}

// A clock the test moves by hand.
internal sealed class ManualClock(DateTimeOffset now) : TimeProvider
{
    public DateTimeOffset Now { get; set; } = now;

    public override DateTimeOffset GetUtcNow() => Now;
}
