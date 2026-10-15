using Fees.Application;
using Fees.Domain;

namespace Fees.Api;

// The API of the fee basket (ADR-001, decision 8). Money is in reais with two decimals, like the core's API; the
// domain keeps cents.
public static class Endpoints
{
    public static void MapFees(this WebApplication app)
    {
        var fees = app.MapGroup("/v1/fees").RequireAuthorization(Scopes.Read);

        fees.MapGet("/packages", async (HttpContext http, PackageService packages, CancellationToken ct) =>
            (await packages.ListAsync(Scopes.TenantOf(http.User), ct)).Select(PackageResponse.From));

        fees.MapGet("/packages/{packageId:guid}/schedules",
            async (Guid packageId, HttpContext http, PackageService packages, CancellationToken ct) =>
                (await packages.SchedulesAsync(Scopes.TenantOf(http.User), packageId, ct)).Select(ScheduleResponse.From));

        fees.MapPost("/packages/{packageId:guid}/schedules",
            async (Guid packageId, ScheduleRequest request, HttpContext http, PackageService packages,
                CancellationToken ct) =>
            {
                var schedule = await packages.AddScheduleAsync(Scopes.TenantOf(http.User), packageId, request.ToCommand(), ct);
                return Results.Created($"/v1/fees/packages/{packageId}/schedules", ScheduleResponse.From(schedule));
            }).RequireAuthorization(Scopes.Admin);

        fees.MapGet("/accounts/{accountId:guid}",
            async (Guid accountId, HttpContext http, AccountService accounts, ChargeService charges,
                CancellationToken ct) =>
            {
                var tenantId = Scopes.TenantOf(http.User);
                return AccountResponse.From(await accounts.GetAsync(tenantId, accountId, ct),
                    await charges.ViewAsync(tenantId, accountId, ct));
            });

        fees.MapPut("/accounts/{accountId:guid}/package",
            async (Guid accountId, ChangePackageRequest request, HttpContext http, AccountService accounts,
                ChargeService charges, CancellationToken ct) =>
            {
                var tenantId = Scopes.TenantOf(http.User);
                return AccountResponse.From(await accounts.ChangePackageAsync(tenantId, accountId, request.PackageId,
                    request.Operator, Scopes.ClientOf(http.User), ct), await charges.ViewAsync(tenantId, accountId, ct));
            })
            .RequireAuthorization(Scopes.Admin);
    }

    internal static decimal Reais(long cents) => cents / 100m;

    internal static long Cents(decimal reais, string field)
    {
        if (decimal.Round(reais, 2) != reais)
        {
            throw new FeeValidationException("INVALID_PRICE", $"{field} has at most two decimals");
        }
        return (long)(reais * 100);
    }
}

public sealed record OperationPriceBody(Operation Operation, int FreePerMonth, decimal Price, decimal? PriceFromCompany)
{
    public static OperationPriceBody From(OperationPrice p) => new(p.Operation, p.FreePerMonth,
        Endpoints.Reais(p.PriceCents), p.PriceFromCompanyCents is { } c ? Endpoints.Reais(c) : null);
}

public sealed record ScheduleResponse(Guid Id, DateOnly ValidFrom, decimal MonthlyFee,
    IReadOnlyList<OperationPriceBody> Operations, string CreatedBy, DateTimeOffset CreatedAt)
{
    public static ScheduleResponse From(FeeSchedule s) => new(s.Id, s.ValidFrom, Endpoints.Reais(s.MonthlyFeeCents),
        s.Prices.Select(OperationPriceBody.From).ToList(), s.CreatedBy, s.CreatedAt);
}

public sealed record PackageResponse(Guid Id, string Code, string Name, CustomerType CustomerType, bool IsDefault,
    ScheduleResponse? Current, ScheduleResponse? Next)
{
    public static PackageResponse From(PackageView v) => new(v.Package.Id, v.Package.Code, v.Package.Name,
        v.Package.CustomerType, v.Package.IsDefault, v.Current is null ? null : ScheduleResponse.From(v.Current),
        v.Next is null ? null : ScheduleResponse.From(v.Next));
}

public sealed record ScheduleRequest(DateOnly ValidFrom, decimal MonthlyFee, IReadOnlyList<OperationPriceBody> Operations,
    string Operator)
{
    public ScheduleCommand ToCommand() => new(ValidFrom, Endpoints.Cents(MonthlyFee, "monthlyFee"),
        (Operations ?? []).Select(o => new OperationPrice(o.Operation, o.FreePerMonth, Endpoints.Cents(o.Price, "price"),
            o.PriceFromCompany is { } c ? Endpoints.Cents(c, "priceFromCompany") : null)).ToList(),
        Operator);
}

public sealed record ChangePackageRequest(Guid PackageId, string Operator);

public sealed record PackageChangeResponse(Guid? FromPackageId, Guid ToPackageId, string Operator, string ClientId,
    DateTimeOffset ChangedAt);

public sealed record AccountResponse(Guid AccountId, CustomerType CustomerType, AccountStatus Status, Guid PackageId,
    string PackageCode, string PackageName, DateTimeOffset Since, IReadOnlyList<PackageChangeResponse> Changes,
    FeesResponse Fees)
{
    public static AccountResponse From(AccountView v, ChargesView fees) => new(v.Account.AccountId,
        v.Account.CustomerType, v.Account.Status, v.Package.Id, v.Package.Code, v.Package.Name, v.Account.Since,
        v.Changes.Select(c => new PackageChangeResponse(c.FromPackageId, c.ToPackageId, c.Operator, c.ClientId,
            c.ChangedAt)).ToList(), FeesResponse.From(fees));
}

// The quota used this month, the latest fees and the total still pending.
public sealed record FeesResponse(string Month, IReadOnlyList<UsageResponse> Usage, decimal Pending,
    IReadOnlyList<ChargeResponse> Charges)
{
    public static FeesResponse From(ChargesView v) => new(v.Month,
        v.Usage.Select(u => new UsageResponse(u.Operation, u.Used, u.Free)).ToList(), Endpoints.Reais(v.PendingCents),
        v.Charges.Select(c => new ChargeResponse(c.Id, c.Operation, Endpoints.Reais(c.AmountCents), c.Description,
            c.Status, c.SourceTransactionId, c.Reference, c.CoreTransactionId, c.RefundTransactionId, c.LastError,
            c.CreatedAt, c.UpdatedAt)).ToList());
}

public sealed record UsageResponse(Operation Operation, int Used, int Free);

public sealed record ChargeResponse(Guid Id, string Operation, decimal Amount, string Description, ChargeStatus Status,
    Guid? SourceTransactionId, string? Reference, Guid? FeeTransactionId, Guid? RefundTransactionId, string? LastError,
    DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt);
