namespace Fees.Domain;

public enum AccountStatus
{
    ACTIVE,
    CLOSED,
}

// An account as the fee basket sees it (ADR-001, decision 3): whose type it is and which package it has. Built from
// the core's events; the accounts that existed before are brought from the core's API.
public sealed record FeeAccount(Guid TenantId, Guid AccountId, CustomerType CustomerType, Guid PackageId,
    AccountStatus Status, DateTimeOffset Since)
{
    // Moves the account to another package of the same customer type.
    public FeeAccount MoveTo(Package package, DateTimeOffset now)
    {
        if (Status == AccountStatus.CLOSED)
        {
            throw new FeeRuleException("ACCOUNT_CLOSED", "a closed account has no package to change");
        }
        if (package.CustomerType != CustomerType)
        {
            throw new FeeRuleException("PACKAGE_OF_ANOTHER_TYPE",
                $"a {CustomerType} account only takes a {CustomerType} package");
        }
        if (package.Id == PackageId)
        {
            throw new FeeRuleException("SAME_PACKAGE", "the account already has this package");
        }
        return this with { PackageId = package.Id, Since = now };
    }
}

// One package change, as it happened: who asked, from what, to what (ADR-001, decision 3).
public sealed record PackageChange(Guid TenantId, Guid AccountId, Guid? FromPackageId, Guid ToPackageId,
    string Operator, string ClientId, DateTimeOffset ChangedAt);
