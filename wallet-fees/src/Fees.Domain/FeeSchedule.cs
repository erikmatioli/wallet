namespace Fees.Domain;

// What one operation costs in a schedule: how many are free each month and the price after that. A Pix received
// has two prices, by who paid it: `PriceCents` for a person, `PriceFromCompanyCents` for a company.
public sealed record OperationPrice(Operation Operation, int FreePerMonth, long PriceCents, long? PriceFromCompanyCents)
{
    public long PriceFor(CustomerType? payer) =>
        Operation == Operation.PIX_IN && payer == CustomerType.PJ ? PriceFromCompanyCents ?? PriceCents : PriceCents;
}

// The prices of a package from a date on (ADR-001, decision 2). Changing a price is a new schedule; the old one
// stays, to explain what was charged. A transaction is priced by the schedule in force on its day.
public sealed record FeeSchedule(Guid Id, Guid TenantId, Guid PackageId, DateOnly ValidFrom, long MonthlyFeeCents,
    IReadOnlyList<OperationPrice> Prices, string CreatedBy, DateTimeOffset CreatedAt)
{
    public const long MaxPriceCents = 100_000_00;
    public const int MaxFreePerMonth = 10_000;

    public static FeeSchedule Create(Guid tenantId, Guid packageId, DateOnly validFrom, long monthlyFeeCents,
        IReadOnlyList<OperationPrice> prices, string createdBy, DateTimeOffset now)
    {
        if (monthlyFeeCents is < 0 or > MaxPriceCents)
        {
            throw new FeeValidationException("INVALID_PRICE", "the monthly fee must be between 0 and 100000.00");
        }
        if (prices.GroupBy(p => p.Operation).Any(g => g.Count() > 1))
        {
            throw new FeeValidationException("DUPLICATE_OPERATION", "each operation appears once in a schedule");
        }
        foreach (var p in prices)
        {
            if (!Enum.IsDefined(p.Operation))
            {
                throw new FeeValidationException("INVALID_OPERATION", $"unknown operation {p.Operation}");
            }
            if (p.FreePerMonth is < 0 or > MaxFreePerMonth)
            {
                throw new FeeValidationException("INVALID_FREE_QUOTA",
                    $"the free quota of {p.Operation} must be between 0 and {MaxFreePerMonth}");
            }
            if (p.PriceCents is < 0 or > MaxPriceCents || p.PriceFromCompanyCents is < 0 or > MaxPriceCents)
            {
                throw new FeeValidationException("INVALID_PRICE", $"the price of {p.Operation} must be between 0 and 100000.00");
            }
            if (p.PriceFromCompanyCents is not null && p.Operation != Operation.PIX_IN)
            {
                throw new FeeValidationException("INVALID_PRICE",
                    "only a Pix received has a price by who paid it");
            }
        }
        var by = (createdBy ?? "").Trim();
        if (by.Length is 0 or > 80)
        {
            throw new FeeValidationException("INVALID_OPERATOR", "the operator is required, at most 80 characters");
        }
        return new FeeSchedule(Guid.CreateVersion7(), tenantId, packageId, validFrom, monthlyFeeCents,
            prices.OrderBy(p => p.Operation).ToList(), by, now);
    }

    // The line of an operation; an operation the schedule does not list is free.
    public OperationPrice PriceOf(Operation operation) =>
        Prices.FirstOrDefault(p => p.Operation == operation) ?? new OperationPrice(operation, 0, 0, null);

    // The schedule in force on `day`: the latest one that started on it or before; null when none did.
    public static FeeSchedule? InForce(IEnumerable<FeeSchedule> schedules, DateOnly day) =>
        schedules.Where(s => s.ValidFrom <= day).MaxBy(s => s.ValidFrom);
}
