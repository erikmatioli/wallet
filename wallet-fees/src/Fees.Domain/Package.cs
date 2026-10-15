namespace Fees.Domain;

// A package a customer contracts: basic, standard, vip for a person; the same three for a company (ADR-001). The
// basic of each customer type is where every account starts.
public sealed record Package(Guid Id, Guid TenantId, string Code, string Name, CustomerType CustomerType,
    bool IsDefault, DateTimeOffset CreatedAt)
{
    public const int MaxName = 60;

    // The six packages every tenant starts with: code, name, customer type, whether it is where accounts start.
    public static readonly IReadOnlyList<(string Code, string Name, CustomerType Type, bool IsDefault)> Initial =
    [
        ("PF_BASIC", "Basic", CustomerType.PF, true),
        ("PF_STANDARD", "Standard", CustomerType.PF, false),
        ("PF_VIP", "VIP", CustomerType.PF, false),
        ("PJ_BASIC", "PJ Basic", CustomerType.PJ, true),
        ("PJ_STANDARD", "PJ Standard", CustomerType.PJ, false),
        ("PJ_VIP", "PJ VIP", CustomerType.PJ, false),
    ];

    // The schedule a new tenant starts with, the same in every package until the operator sets them apart: the
    // example of ADR-001 (no monthly fee; R$ 1,00 per withdrawal; two free transfers, then R$ 1,00; R$ 1,50 per Pix
    // sent; Pix received free).
    public static FeeSchedule InitialSchedule(Guid tenantId, Guid packageId, DateOnly validFrom, DateTimeOffset now) =>
        FeeSchedule.Create(tenantId, packageId, validFrom, 0,
        [
            new OperationPrice(Operation.WITHDRAWAL, 0, 100, null),
            new OperationPrice(Operation.TRANSFER_OUT, 2, 100, null),
            new OperationPrice(Operation.PIX_OUT, 0, 150, null),
            new OperationPrice(Operation.PIX_IN, 0, 0, 0),
        ], "sistema", now);
}
