using Fees.Domain;

namespace Fees.Application;

// Runs a unit of work in one database transaction: everything a use case writes commits together or not at all.
public interface ITransactionRunner
{
    Task<T> InTransactionAsync<T>(Func<Task<T>> work, CancellationToken ct = default);
}

public interface IPackageRepository
{
    // Serializes the creation of a tenant's initial packages: two first requests at once create them once.
    Task LockTenantAsync(Guid tenantId, CancellationToken ct = default);

    Task<IReadOnlyList<Package>> ListAsync(Guid tenantId, CancellationToken ct = default);

    Task<Package?> FindAsync(Guid tenantId, Guid packageId, CancellationToken ct = default);

    Task<Package?> FindDefaultAsync(Guid tenantId, CustomerType type, CancellationToken ct = default);

    Task InsertAsync(Package package, CancellationToken ct = default);
}

public interface IScheduleRepository
{
    // The package's schedules, the oldest first.
    Task<IReadOnlyList<FeeSchedule>> ListAsync(Guid tenantId, Guid packageId, CancellationToken ct = default);

    Task InsertAsync(FeeSchedule schedule, CancellationToken ct = default);

    // Removes a schedule that has not come into force yet, to put another in its place.
    Task DeleteAsync(Guid tenantId, Guid scheduleId, CancellationToken ct = default);
}

public interface IAccountRepository
{
    Task<FeeAccount?> FindAsync(Guid tenantId, Guid accountId, CancellationToken ct = default);

    // False when the account was already there: an event read twice, or the sync and the event racing.
    Task<bool> InsertIfAbsentAsync(FeeAccount account, CancellationToken ct = default);

    Task UpdateAsync(FeeAccount account, CancellationToken ct = default);

    Task InsertChangeAsync(PackageChange change, CancellationToken ct = default);

    // The account's package changes, the newest first.
    Task<IReadOnlyList<PackageChange>> ChangesAsync(Guid tenantId, Guid accountId, CancellationToken ct = default);
}

// The messages already handled: the bus delivers at least once.
public interface IProcessedMessages
{
    // True the first time an id is seen, false afterwards; in the caller's transaction.
    Task<bool> TryMarkAsync(Guid messageId, CancellationToken ct = default);
}

// An account as the core lists it: what the fee basket needs to put it in a package.
public sealed record CoreAccount(Guid AccountId, string DocumentType, string Status);

// The core, read with each tenant's own credentials (ADR-001, decision 1).
public interface ICoreAccounts
{
    // The client ids this service has credentials for.
    IReadOnlyList<string> Tenants { get; }

    Task<Guid> TenantIdAsync(string clientId, CancellationToken ct = default);

    // A page of the tenant's accounts, the newest first; `next` is null on the last page.
    Task<(IReadOnlyList<CoreAccount> Items, string? Next)> AccountsAsync(string clientId, string? cursor,
        CancellationToken ct = default);
}
