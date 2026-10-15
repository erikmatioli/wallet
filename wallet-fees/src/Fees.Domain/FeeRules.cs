namespace Fees.Domain;

// A rule of the fee basket was broken. The code is stable: the API answers with it.
public class FeeRuleException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

// The request is malformed (an empty name, a negative price).
public sealed class FeeValidationException(string code, string message) : FeeRuleException(code, message);

// What was asked for does not exist for this tenant.
public sealed class FeeNotFoundException(string code, string message) : FeeRuleException(code, message);

// The holder of the account: a person (CPF) or a company (CNPJ). Each has its own packages.
public enum CustomerType
{
    PF,
    PJ,
}

public static class CustomerTypes
{
    // The core says CPF or CNPJ; the basket says PF or PJ.
    public static CustomerType FromDocumentType(string documentType) => documentType switch
    {
        "CPF" => CustomerType.PF,
        "CNPJ" => CustomerType.PJ,
        _ => throw new FeeValidationException("INVALID_DOCUMENT_TYPE", $"unknown document type {documentType}"),
    };
}

// The operations a package prices (ADR-001, decision 2). A new one is a new value here and a new line in the
// schedules: the model does not change.
public enum Operation
{
    // A Pix sent: the payer is charged.
    PIX_OUT,
    // A Pix received: the payee is charged, by the type of the payer's document.
    PIX_IN,
    // A transfer between accounts of the tenant: the payer is charged.
    TRANSFER_OUT,
    // A withdrawal.
    WITHDRAWAL,
}

// Brasília's calendar: the day a schedule starts, the month of a free quota.
public static class Brasilia
{
    private static readonly TimeZoneInfo Zone = TimeZoneInfo.FindSystemTimeZoneById("America/Sao_Paulo");

    public static DateOnly Today(TimeProvider clock) => DateOnly.FromDateTime(
        TimeZoneInfo.ConvertTime(clock.GetUtcNow(), Zone).DateTime);

    public static DateOnly DayOf(DateTimeOffset instant) => DateOnly.FromDateTime(
        TimeZoneInfo.ConvertTime(instant, Zone).DateTime);
}
