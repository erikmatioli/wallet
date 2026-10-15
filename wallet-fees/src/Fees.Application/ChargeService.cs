using Fees.Domain;
namespace Fees.Application;

// The use of the free quota of one operation in a month.
public sealed record UsageView(Operation Operation, int Used, int Free);

// The fees of an account: the quota used this month, the latest fees and what is still pending.
public sealed record ChargesView(string Month, IReadOnlyList<UsageView> Usage, IReadOnlyList<FeeCharge> Charges,
    long PendingCents);

// From a transaction to a fee, and from a fee to the core (ADR-001, decisions 4 and 5).
public sealed class ChargeService(ITransactionRunner tx, IAccountRepository accounts, IScheduleRepository schedules,
    IUsageRepository usage, IChargeRepository charges, IProcessedMessages processed, ICoreFees core,
    AccountService accountService, TimeProvider clock)
{
    private const int Latest = 50;

    // A transaction the core posted (wallet.transaction.posted.v1): counts it against the quota, creates its fee when
    // the quota is used up, then charges the pending fees of the account that pays and of every account it credited.
    // Charging runs again on a repeated delivery: it is idempotent, and it is how a fee left pending by a failure
    // after the first commit is not forgotten.
    public async Task OnTransactionPostedAsync(PostedTransaction t, CancellationToken ct = default)
    {
        var (payer, credited) = await tx.InTransactionAsync(async () =>
        {
            var customers = await CustomersAsync(t, ct);
            bool IsCustomer(Guid id) => customers.ContainsKey(id);
            var chargeable = Classification.Of(t, IsCustomer);
            var credited = Classification.Credited(t, IsCustomer).ToList();
            if (await processed.TryMarkAsync(t.EventId, ct) && chargeable is not null)
            {
                await PriceAsync(t, chargeable, customers[chargeable.AccountId], ct);
            }
            return (chargeable?.AccountId, credited);
        }, ct);
        foreach (var accountId in new[] { payer }.OfType<Guid>().Concat(credited).Distinct())
        {
            await ChargePendingAsync(t.TenantId, accountId, ct);
        }
    }

    // A block released (wallet.account.block-released.v1): the fees waiting for it are charged. The release of the
    // fee block itself is this service's own doing, after charging everything.
    public Task OnBlockReleasedAsync(Guid tenantId, Guid accountId, string blockType, CancellationToken ct = default) =>
        blockType == "FEE_PENDING" ? Task.CompletedTask : ChargePendingAsync(tenantId, accountId, ct);

    // Charges the account's pending fees, the oldest first, whole, stopping at the first one that does not fit
    // (ADR-001, decision 5). No balance puts the fee block on the account; nothing left pending releases it. Answers
    // why the rest waits, or null when nothing does.
    public Task<string?> ChargePendingAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            await usage.LockAccountAsync(accountId, ct);
            var pending = await charges.PendingAsync(tenantId, accountId, ct);
            if (pending.Count == 0)
            {
                return (string?)null;
            }
            var left = pending.Count;
            foreach (var charge in pending)
            {
                var result = await core.ChargeAsync(charge, ct);
                var now = clock.GetUtcNow();
                if (result.Charged)
                {
                    await charges.UpdateAsync(charge.Charged(result.TransactionId!.Value, now), ct);
                    left--;
                    continue;
                }
                if (result.Code is "ACCOUNT_NOT_ACTIVE" or "ACCOUNT_NOT_FOUND")
                {
                    // Closed (or gone) in the core: nothing more will ever be charged from it.
                    await charges.UpdateAsync(charge.Cancelled(result.Code, now), ct);
                    left--;
                    continue;
                }
                await charges.UpdateAsync(charge.Waiting(result.Code ?? "UNAVAILABLE", now), ct);
                if (result.Code == "INSUFFICIENT_FUNDS")
                {
                    await core.EnsureFeeBlockAsync(tenantId, accountId, ct);
                }
                return $"{result.Code ?? "UNAVAILABLE"} ({left} pending)";
            }
            // Nothing left: the account pays again. A failure here rolls the statuses back; the core's idempotency
            // gives the same fee transactions on the next try, and the release is tried again.
            await core.ReleaseFeeBlocksAsync(tenantId, accountId, ct);
            return null;
        }, ct);

    // Every account with a pending fee, for the sweep: in case a credit or a release was missed.
    public Task<IReadOnlyList<(Guid TenantId, Guid AccountId)>> AccountsWithPendingAsync(CancellationToken ct = default) =>
        tx.InTransactionAsync(() => charges.AccountsWithPendingAsync(ct), ct);

    // An account closed in the core: its pending fees are cancelled (ADR-001, decision 5).
    public Task CancelPendingAsync(Guid tenantId, Guid accountId, string reason, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            await usage.LockAccountAsync(accountId, ct);
            var now = clock.GetUtcNow();
            foreach (var charge in await charges.PendingAsync(tenantId, accountId, ct))
            {
                await charges.UpdateAsync(charge.Cancelled(reason, now), ct);
            }
            return true;
        }, ct);

    public async Task<ChargesView> ViewAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        await tx.InTransactionAsync(async () =>
        {
            var account = await accounts.FindAsync(tenantId, accountId, ct)
                          ?? throw new FeeNotFoundException("ACCOUNT_NOT_FOUND", "account not found in the fee basket");
            var now = clock.GetUtcNow();
            var month = Months.Of(now);
            var schedule = FeeSchedule.InForce(await schedules.ListAsync(tenantId, account.PackageId, ct),
                Brasilia.DayOf(now));
            var used = new List<UsageView>();
            foreach (var operation in Enum.GetValues<Operation>())
            {
                used.Add(new UsageView(operation, await usage.CountAsync(accountId, month, operation, ct),
                    schedule?.PriceOf(operation).FreePerMonth ?? 0));
            }
            var pending = await charges.PendingAsync(tenantId, accountId, ct);
            return new ChargesView(month, used, await charges.ListAsync(tenantId, accountId, Latest, ct),
                pending.Sum(c => c.AmountCents));
        }, ct);

    private async Task PriceAsync(PostedTransaction t, Chargeable chargeable, FeeAccount account, CancellationToken ct)
    {
        await usage.LockAccountAsync(account.AccountId, ct);
        var month = Months.Of(t.OccurredAt);
        if (!await usage.RecordAsync(t.TenantId, account.AccountId, month, chargeable.Operation, t.TransactionId,
                t.OccurredAt, ct))
        {
            return; // the same transaction in another event: counted once
        }
        // The package the account has now, at the prices in force on the day of the transaction.
        var schedule = FeeSchedule.InForce(await schedules.ListAsync(t.TenantId, account.PackageId, ct),
            Brasilia.DayOf(t.OccurredAt));
        if (schedule is null || account.Status != AccountStatus.ACTIVE)
        {
            return;
        }
        var price = schedule.PriceOf(chargeable.Operation);
        var count = await usage.CountAsync(account.AccountId, month, chargeable.Operation, ct);
        var amount = price.PriceFor(chargeable.Payer);
        if (count <= price.FreePerMonth || amount <= 0)
        {
            return;
        }
        await charges.InsertAsync(FeeCharge.ForTransaction(t.TenantId, account.AccountId, chargeable.Operation,
            t.TransactionId, amount, clock.GetUtcNow()), ct);
    }

    // The customer accounts among the transaction's entries. One the basket has not seen (its opening event not read
    // yet) is asked to the core and joins its basic package; the core's internal accounts are left out.
    private async Task<Dictionary<Guid, FeeAccount>> CustomersAsync(PostedTransaction t, CancellationToken ct)
    {
        var found = new Dictionary<Guid, FeeAccount>();
        foreach (var id in t.Entries.Select(e => e.AccountId).Distinct())
        {
            var account = await accounts.FindAsync(t.TenantId, id, ct);
            if (account is null && await core.AccountAsync(t.TenantId, id, ct) is { } fromCore)
            {
                await accountService.SyncAsync(t.TenantId, [fromCore], ct);
                account = await accounts.FindAsync(t.TenantId, id, ct);
            }
            if (account is not null)
            {
                found[id] = account;
            }
        }
        return found;
    }
}
