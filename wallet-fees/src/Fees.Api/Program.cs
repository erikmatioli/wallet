using System.Text.Json.Serialization;
using Amazon.Runtime;
using Amazon.SQS;
using Fees.Api;
using Fees.Application;
using Fees.Domain;
using Fees.Infrastructure;
using Fees.Infrastructure.Bus;
using Fees.Infrastructure.Core;
using Fees.Infrastructure.Persistence;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.Extensions.Options;
using Npgsql;
using OpenTelemetry.Logs;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

var builder = WebApplication.CreateBuilder(args);
builder.Services.Configure<FeesOptions>(builder.Configuration.GetSection("Fees"));
builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));
builder.Services.AddSingleton(TimeProvider.System);

// Persistence: one connection and transaction per request or message (scoped).
builder.Services.AddSingleton(sp =>
    NpgsqlDataSource.Create(sp.GetRequiredService<IOptions<FeesOptions>>().Value.Db.Connection));
builder.Services.AddScoped<DbSession>();
builder.Services.AddScoped<ITransactionRunner, TransactionRunner>();
builder.Services.AddScoped<IPackageRepository, PackageRepository>();
builder.Services.AddScoped<IScheduleRepository, ScheduleRepository>();
builder.Services.AddScoped<IAccountRepository, AccountRepository>();
builder.Services.AddScoped<IProcessedMessages, ProcessedMessages>();
builder.Services.AddScoped<IUsageRepository, UsageRepository>();
builder.Services.AddScoped<IChargeRepository, ChargeRepository>();
builder.Services.AddScoped<IMonthlyRuns, MonthlyRuns>();
builder.Services.AddScoped<PackageService>();
builder.Services.AddScoped<AccountService>();
builder.Services.AddScoped<ChargeService>();
builder.Services.AddScoped<MonthlyFeeService>();

// The core: its accounts (start-up sync), the charging of fees and its events (SQS).
builder.Services.AddSingleton<CoreCache>();
builder.Services.AddHttpClient<CoreClient>((sp, http) =>
{
    http.BaseAddress = new Uri(sp.GetRequiredService<IOptions<FeesOptions>>().Value.Core.BaseUrl);
    http.Timeout = TimeSpan.FromSeconds(15);
});
builder.Services.AddTransient<ICoreAccounts>(sp => sp.GetRequiredService<CoreClient>());
builder.Services.AddTransient<ICoreFees>(sp => sp.GetRequiredService<CoreClient>());
builder.Services.AddSingleton<IAmazonSQS>(sp =>
{
    var bus = sp.GetRequiredService<IOptions<FeesOptions>>().Value.Bus;
    var config = new AmazonSQSConfig { RegionEndpoint = Amazon.RegionEndpoint.GetBySystemName(bus.Region) };
    if (!string.IsNullOrWhiteSpace(bus.Endpoint))
    {
        config.ServiceURL = bus.Endpoint;
    }
    return string.IsNullOrWhiteSpace(bus.AccessKey)
        ? new AmazonSQSClient(config)
        : new AmazonSQSClient(new BasicAWSCredentials(bus.AccessKey, bus.SecretKey), config);
});
builder.Services.AddHostedService<CoreEventsConsumer>();
builder.Services.AddHostedService<AccountSync>();
builder.Services.AddHostedService<PendingFeesSweep>();
builder.Services.AddHostedService<MonthlyFeeJob>();

// OpenTelemetry, to the same collector as the other products (OTLP over HTTP, from the OTEL_* variables). Off when
// no endpoint is set: the tests and a service run by hand do not export.
if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
{
    builder.Services.AddOpenTelemetry()
        .ConfigureResource(r => r.AddService("wallet-fees"))
        .WithTracing(t => t
            .AddAspNetCoreInstrumentation(o => o.Filter = http => http.Request.Path != "/health")
            .AddHttpClientInstrumentation()
            .AddSource(Telemetry.SourceName, "Npgsql")
            .AddOtlpExporter());
    builder.Logging.AddOpenTelemetry(o =>
    {
        o.IncludeScopes = true;
        o.AddOtlpExporter();
    });
}

builder.Services.AddCoreJwt();
builder.Services.AddProblemDetails();

var app = builder.Build();

Migrator.Migrate(app.Services.GetRequiredService<IOptions<FeesOptions>>().Value.Db.OwnerConnection);

// The domain's refusals become problem+json with a stable code, like the core's answers. They are answers, not
// failures: only the unexpected ones are logged as errors.
app.UseExceptionHandler(new ExceptionHandlerOptions
{
    SuppressDiagnosticsCallback = c => c.Exception is FeeRuleException or BadHttpRequestException,
    ExceptionHandler = async http =>
{
    var error = http.Features.Get<IExceptionHandlerFeature>()?.Error;
    var (status, code) = error switch
    {
        FeeNotFoundException e => (StatusCodes.Status404NotFound, e.Code),
        FeeValidationException e => (StatusCodes.Status400BadRequest, e.Code),
        FeeRuleException e => (StatusCodes.Status422UnprocessableEntity, e.Code),
        BadHttpRequestException => (StatusCodes.Status400BadRequest, "MALFORMED_REQUEST"),
        UnauthorizedAccessException => (StatusCodes.Status401Unauthorized, "UNAUTHORIZED"),
        _ => (StatusCodes.Status500InternalServerError, "INTERNAL_ERROR"),
    };
    await Results.Json(new
    {
        title = status == 500 ? "unexpected error" : error?.Message,
        status,
        code,
    }, contentType: "application/problem+json", statusCode: status).ExecuteAsync(http);
},
});
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/health", () => Results.Ok(new { status = "UP" }));
app.MapFees();

app.Run();

// For the API tests (WebApplicationFactory).
public partial class Program;
