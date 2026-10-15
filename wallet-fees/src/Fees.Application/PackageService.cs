using Fees.Domain;

namespace Fees.Application;

// A package with what it costs today and what is already scheduled to change.
public sealed record PackageView(Package Package, FeeSchedule? Current, FeeSchedule? Next);

// What the operator sends to set a package's prices from a date on.
public sealed record ScheduleCommand(DateOnly ValidFrom, long MonthlyFeeCents, IReadOnlyList<OperationPrice> Prices,
    string Operator);

// Packages and their schedules (ADR-001, decision 2).
public sealed class PackageService(ITransactionRunner tx, IPackageRepository packages, IScheduleRepository schedules,
    TimeProvider clock)
{
    // Creates the six initial packages, each with the initial schedule, the first time a tenant shows up.
    public Task EnsureDefaultsAsync(Guid tenantId, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            await EnsureDefaultsInTransactionAsync(tenantId, ct);
            return true;
        }, ct);

    // Same as EnsureDefaultsAsync, inside the caller's transaction.
    public async Task EnsureDefaultsInTransactionAsync(Guid tenantId, CancellationToken ct = default)
    {
        await packages.LockTenantAsync(tenantId, ct);
        if ((await packages.ListAsync(tenantId, ct)).Count > 0)
        {
            return;
        }
        var now = clock.GetUtcNow();
        var today = Brasilia.Today(clock);
        foreach (var (code, name, type, isDefault) in Package.Initial)
        {
            var package = new Package(Guid.CreateVersion7(), tenantId, code, name, type, isDefault, now);
            await packages.InsertAsync(package, ct);
            await schedules.InsertAsync(Package.InitialSchedule(tenantId, package.Id, today, now), ct);
        }
    }

    public async Task<IReadOnlyList<PackageView>> ListAsync(Guid tenantId, CancellationToken ct = default)
    {
        await EnsureDefaultsAsync(tenantId, ct);
        return await tx.InTransactionAsync(async () =>
        {
            var today = Brasilia.Today(clock);
            var views = new List<PackageView>();
            foreach (var package in await packages.ListAsync(tenantId, ct))
            {
                var all = await schedules.ListAsync(tenantId, package.Id, ct);
                views.Add(new PackageView(package, FeeSchedule.InForce(all, today),
                    all.Where(s => s.ValidFrom > today).MinBy(s => s.ValidFrom)));
            }
            return (IReadOnlyList<PackageView>)views;
        }, ct);
    }

    public async Task<IReadOnlyList<FeeSchedule>> SchedulesAsync(Guid tenantId, Guid packageId,
        CancellationToken ct = default)
    {
        await EnsureDefaultsAsync(tenantId, ct);
        return await tx.InTransactionAsync(async () =>
        {
            await LoadAsync(tenantId, packageId, ct);
            return await schedules.ListAsync(tenantId, packageId, ct);
        }, ct);
    }

    // A new schedule from a date on. From today at the earliest: what was charged is never repriced. A schedule
    // that has not come into force yet can be replaced, by one with the same date.
    public async Task<FeeSchedule> AddScheduleAsync(Guid tenantId, Guid packageId, ScheduleCommand command,
        CancellationToken ct = default)
    {
        var today = Brasilia.Today(clock);
        if (command.ValidFrom < today)
        {
            throw new FeeValidationException("PAST_DATE", "a schedule starts today or later: the past is not repriced");
        }
        var schedule = FeeSchedule.Create(tenantId, packageId, command.ValidFrom, command.MonthlyFeeCents,
            command.Prices, command.Operator, clock.GetUtcNow());
        await EnsureDefaultsAsync(tenantId, ct);
        return await tx.InTransactionAsync(async () =>
        {
            await LoadAsync(tenantId, packageId, ct);
            var same = (await schedules.ListAsync(tenantId, packageId, ct))
                .FirstOrDefault(s => s.ValidFrom == command.ValidFrom);
            if (same is not null)
            {
                if (same.ValidFrom <= today)
                {
                    throw new FeeRuleException("SCHEDULE_IN_FORCE",
                        "the schedule of this date is already in force; start the new one tomorrow");
                }
                await schedules.DeleteAsync(tenantId, same.Id, ct);
            }
            await schedules.InsertAsync(schedule, ct);
            return schedule;
        }, ct);
    }

    private async Task<Package> LoadAsync(Guid tenantId, Guid packageId, CancellationToken ct) =>
        await packages.FindAsync(tenantId, packageId, ct)
        ?? throw new FeeNotFoundException("PACKAGE_NOT_FOUND", "package not found");
}
