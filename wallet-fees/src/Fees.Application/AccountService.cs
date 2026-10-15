using Fees.Domain;

namespace Fees.Application;

// An account with its package and the history of package changes.
public sealed record AccountView(FeeAccount Account, Package Package, IReadOnlyList<PackageChange> Changes);

// The accounts as the fee basket sees them (ADR-001, decision 3): read, move between packages, and keep in step with
// the core, by its events and, for the accounts that existed before, by its API.
public sealed class AccountService(ITransactionRunner tx, IAccountRepository accounts, IPackageRepository packages,
    IProcessedMessages processed, PackageService packageService, TimeProvider clock)
{
    public async Task<AccountView> GetAsync(Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        await tx.InTransactionAsync(async () =>
        {
            var account = await LoadAsync(tenantId, accountId, ct);
            var package = await packages.FindAsync(tenantId, account.PackageId, ct)
                          ?? throw new InvalidOperationException($"package {account.PackageId} is gone");
            return new AccountView(account, package, await accounts.ChangesAsync(tenantId, accountId, ct));
        }, ct);

    // The operator moves the account to another package of the same customer type, from now on.
    public async Task<AccountView> ChangePackageAsync(Guid tenantId, Guid accountId, Guid packageId, string @operator,
        string clientId, CancellationToken ct = default)
    {
        var by = (@operator ?? "").Trim();
        if (by.Length is 0 or > 80)
        {
            throw new FeeValidationException("INVALID_OPERATOR", "the operator is required, at most 80 characters");
        }
        await tx.InTransactionAsync(async () =>
        {
            var account = await LoadAsync(tenantId, accountId, ct);
            var package = await packages.FindAsync(tenantId, packageId, ct)
                          ?? throw new FeeNotFoundException("PACKAGE_NOT_FOUND", "package not found");
            var now = clock.GetUtcNow();
            var moved = account.MoveTo(package, now);
            await accounts.UpdateAsync(moved, ct);
            await accounts.InsertChangeAsync(
                new PackageChange(tenantId, accountId, account.PackageId, package.Id, by, clientId, now), ct);
            return true;
        }, ct);
        return await GetAsync(tenantId, accountId, ct);
    }

    // A new account in the core (wallet.customer.onboarded.v1): it starts in the basic package of its type.
    public Task OnOpenedAsync(Guid messageId, Guid tenantId, Guid accountId, string documentType,
        DateTimeOffset openedAt, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            if (!await processed.TryMarkAsync(messageId, ct))
            {
                return false;
            }
            await StartAsync(tenantId, accountId, CustomersOf(documentType), AccountStatus.ACTIVE, openedAt, ct);
            return true;
        }, ct);

    // An account closed in the core (wallet.account.closed.v1). One the basket never saw is ignored.
    public Task OnClosedAsync(Guid messageId, Guid tenantId, Guid accountId, CancellationToken ct = default) =>
        tx.InTransactionAsync(async () =>
        {
            if (!await processed.TryMarkAsync(messageId, ct))
            {
                return false;
            }
            var account = await accounts.FindAsync(tenantId, accountId, ct);
            if (account is not null && account.Status == AccountStatus.ACTIVE)
            {
                await accounts.UpdateAsync(account with { Status = AccountStatus.CLOSED }, ct);
            }
            return true;
        }, ct);

    // Brings the accounts the basket has not seen yet, page by page: the ones opened before it existed, or whose
    // event it missed. Answers how many it added.
    public async Task<int> SyncAsync(Guid tenantId, IEnumerable<CoreAccount> page, CancellationToken ct = default) =>
        await tx.InTransactionAsync(async () =>
        {
            var added = 0;
            foreach (var a in page)
            {
                var status = a.Status == "CLOSED" ? AccountStatus.CLOSED : AccountStatus.ACTIVE;
                if (await StartAsync(tenantId, a.AccountId, CustomersOf(a.DocumentType), status, clock.GetUtcNow(), ct))
                {
                    added++;
                }
            }
            return added;
        }, ct);

    private async Task<bool> StartAsync(Guid tenantId, Guid accountId, CustomerType type, AccountStatus status,
        DateTimeOffset since, CancellationToken ct)
    {
        await packageService.EnsureDefaultsInTransactionAsync(tenantId, ct);
        var basic = await packages.FindDefaultAsync(tenantId, type, ct)
                    ?? throw new InvalidOperationException($"tenant {tenantId} has no default {type} package");
        var inserted = await accounts.InsertIfAbsentAsync(
            new FeeAccount(tenantId, accountId, type, basic.Id, status, since), ct);
        if (inserted)
        {
            await accounts.InsertChangeAsync(
                new PackageChange(tenantId, accountId, null, basic.Id, "sistema", "wallet-fees", since), ct);
        }
        return inserted;
    }

    private static CustomerType CustomersOf(string documentType) => CustomerTypes.FromDocumentType(documentType);

    private async Task<FeeAccount> LoadAsync(Guid tenantId, Guid accountId, CancellationToken ct) =>
        await accounts.FindAsync(tenantId, accountId, ct)
        ?? throw new FeeNotFoundException("ACCOUNT_NOT_FOUND", "account not found in the fee basket");
}
