using Fees.Domain;

namespace Fees.Application;

// The monthly fee (ADR-001, decision 7): on the 1st, each active account owes the monthly fee of its package, at the
// price in force that day. Each tenant's month is run once; the months missed while the service was down are run
// when it returns. The fees are charged like any other (ChargeService), oldest first.
public sealed class MonthlyFeeService(ITransactionRunner tx, IPackageRepository packages, IScheduleRepository schedules,
    IAccountRepository accounts, IChargeRepository charges, IMonthlyRuns runs, TimeProvider clock)
{
    // The month starts a few minutes after midnight in Brasília, away from the turn of the day.
    public static readonly TimeSpan StartsAfter = TimeSpan.FromMinutes(5);

    // Creates the monthly fees of every month not run yet, for every tenant; answers the accounts that got one, to be
    // charged.
    public async Task<IReadOnlyList<(Guid TenantId, Guid AccountId)>> RunAsync(CancellationToken ct = default)
    {
        var current = Months.Of(clock.GetUtcNow() - StartsAfter);
        var created = new List<(Guid, Guid)>();
        foreach (var tenantId in await tx.InTransactionAsync(() => packages.TenantsAsync(ct), ct))
        {
            var last = await tx.InTransactionAsync(() => runs.LastAsync(tenantId, ct), ct);
            foreach (var month in Months.Since(last, current))
            {
                created.AddRange((await RunAsync(tenantId, month, ct)).Select(a => (tenantId, a)));
            }
        }
        return created.Distinct().ToList();
    }

    // One tenant's month, in one transaction: the mark and the fees go together, and two instances at once run it
    // once (the tenant's lock, then the mark).
    public Task<IReadOnlyList<Guid>> RunAsync(Guid tenantId, string month, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            await packages.LockTenantAsync(tenantId, ct);
            var now = clock.GetUtcNow();
            if (!await runs.MarkAsync(tenantId, month, now, ct))
            {
                return (IReadOnlyList<Guid>)[];
            }
            var day = Months.FirstDay(month);
            var charged = new List<Guid>();
            foreach (var package in await packages.ListAsync(tenantId, ct))
            {
                var schedule = FeeSchedule.InForce(await schedules.ListAsync(tenantId, package.Id, ct), day);
                if (schedule is null || schedule.MonthlyFeeCents <= 0)
                {
                    continue;
                }
                foreach (var accountId in await accounts.ActiveInPackageAsync(tenantId, package.Id, ct))
                {
                    await charges.InsertAsync(FeeCharge.Monthly(tenantId, accountId, month, package.Name,
                        schedule.MonthlyFeeCents, now), ct);
                    charged.Add(accountId);
                }
            }
            return charged;
        }, ct);
}
