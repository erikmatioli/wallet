namespace Fees.Domain;

// A transaction as the core published it (wallet.transaction.posted.v1), with what the fee basket reads.
public sealed record PostedTransaction(Guid EventId, Guid TenantId, Guid TransactionId, string Type,
    IReadOnlyList<PostedEntry> Entries, DateTimeOffset OccurredAt, Guid? RelatedTransactionId, bool Reversal,
    string? CounterpartyDocumentType);

public sealed record PostedEntry(Guid AccountId, string Direction, long AmountCents);

// Which account an operation is charged to, and what the operation is (ADR-001, decision 2).
public sealed record Chargeable(Guid AccountId, Operation Operation, CustomerType? Payer);

public static class Classification
{
    // The account that pays for this transaction and the operation, or null when it is not priced (a deposit, a
    // return, a reversal, a treasury or judicial movement, a fee). `isCustomer` tells a customer account from the
    // core's internal ones, which also appear in the entries.
    public static Chargeable? Of(PostedTransaction t, Func<Guid, bool> isCustomer)
    {
        if (t.Reversal)
        {
            return null;
        }
        PostedEntry? Leg(string direction) =>
            t.Entries.FirstOrDefault(e => e.Direction == direction && isCustomer(e.AccountId));
        return t.Type switch
        {
            "PIX_OUT" => Leg("DEBIT") is { } d ? new Chargeable(d.AccountId, Operation.PIX_OUT, null) : null,
            "PIX_IN" => Leg("CREDIT") is { } c
                ? new Chargeable(c.AccountId, Operation.PIX_IN, PayerOf(t.CounterpartyDocumentType))
                : null,
            "TRANSFER" => Leg("DEBIT") is { } d ? new Chargeable(d.AccountId, Operation.TRANSFER_OUT, null) : null,
            "WITHDRAWAL" => Leg("DEBIT") is { } d ? new Chargeable(d.AccountId, Operation.WITHDRAWAL, null) : null,
            _ => null,
        };
    }

    // The accounts this transaction credited: a credit is when pending fees are tried again (ADR-001, decision 5).
    public static IEnumerable<Guid> Credited(PostedTransaction t, Func<Guid, bool> isCustomer) =>
        t.Entries.Where(e => e.Direction == "CREDIT" && isCustomer(e.AccountId)).Select(e => e.AccountId).Distinct();

    private static CustomerType? PayerOf(string? documentType) => documentType switch
    {
        "CPF" => CustomerType.PF,
        "CNPJ" => CustomerType.PJ,
        _ => null, // a Pix posted before the core kept the type: priced as from a person
    };

    // What the customer reads in the statement.
    public static string Description(Operation operation) => operation switch
    {
        Operation.PIX_OUT => "Tarifa: Pix enviado",
        Operation.PIX_IN => "Tarifa: Pix recebido",
        Operation.TRANSFER_OUT => "Tarifa: transferência",
        Operation.WITHDRAWAL => "Tarifa: saque",
        _ => "Tarifa",
    };
}

public enum ChargeStatus
{
    // Waiting to be charged: no balance, a block, or the core did not answer.
    PENDING,
    CHARGED,
    // Will never be charged: the account was closed, or its transaction was reversed before.
    CANCELLED,
    // Charged and given back (step 4: the reversal of its transaction).
    REFUNDED,
}

// A fee to charge, from a transaction (or, in step 4, a month). Charged whole or not at all, by the core.
public sealed record FeeCharge(Guid Id, Guid TenantId, Guid AccountId, string Operation, Guid? SourceTransactionId,
    string? Reference, long AmountCents, string Description, string IdempotencyKey, ChargeStatus Status,
    Guid? CoreTransactionId, int Attempts, string? LastError, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt)
{
    public static FeeCharge ForTransaction(Guid tenantId, Guid accountId, Operation operation, Guid sourceTransactionId,
        long amountCents, DateTimeOffset now) =>
        new(Guid.CreateVersion7(), tenantId, accountId, operation.ToString(), sourceTransactionId, null, amountCents,
            Classification.Description(operation),
            // The core's idempotency key: the same fee is never charged twice, whatever is retried.
            $"fee:{sourceTransactionId}:{operation}",
            ChargeStatus.PENDING, null, 0, null, now, now);

    public FeeCharge Charged(Guid coreTransactionId, DateTimeOffset now) =>
        this with
        {
            Status = ChargeStatus.CHARGED, CoreTransactionId = coreTransactionId, Attempts = Attempts + 1,
            LastError = null, UpdatedAt = now,
        };

    public FeeCharge Waiting(string reason, DateTimeOffset now) =>
        this with { Attempts = Attempts + 1, LastError = reason, UpdatedAt = now };

    public FeeCharge Cancelled(string reason, DateTimeOffset now) =>
        this with { Status = ChargeStatus.CANCELLED, LastError = reason, UpdatedAt = now };
}

public static class Months
{
    // The month of a free quota: Brasília's, as "2026-10".
    public static string Of(DateTimeOffset instant) => Brasilia.DayOf(instant).ToString("yyyy-MM");
}
