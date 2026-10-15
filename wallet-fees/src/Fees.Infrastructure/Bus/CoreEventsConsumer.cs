using System.Diagnostics;
using System.Text.Json;
using Amazon.SQS;
using Amazon.SQS.Model;
using Fees.Application;
using Fees.Domain;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Fees.Infrastructure.Bus;

// Reads the core's events from this service's SQS queue, subscribed to the core's two topics (ADR-014 of the core).
// A message is deleted only after it was handled; one that keeps failing goes to the DLQ after five tries. Each
// message runs in its own scope: its own connection and transaction.
public sealed class CoreEventsConsumer(IAmazonSQS sqs, IServiceScopeFactory scopes, IOptions<FeesOptions> options,
    ILogger<CoreEventsConsumer> log) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var queueUrl = options.Value.Bus.QueueUrl;
        if (string.IsNullOrWhiteSpace(queueUrl))
        {
            log.LogInformation("no queue configured: the core's events are not consumed");
            return;
        }
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var batch = await sqs.ReceiveMessageAsync(new ReceiveMessageRequest
                {
                    QueueUrl = queueUrl,
                    MaxNumberOfMessages = 10,
                    WaitTimeSeconds = 20,
                    MessageAttributeNames = ["All"],
                }, stoppingToken);
                foreach (var message in batch.Messages ?? [])
                {
                    if (await HandleAsync(message, stoppingToken))
                    {
                        await sqs.DeleteMessageAsync(queueUrl, message.ReceiptHandle, stoppingToken);
                    }
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                // The queue is not there yet (the bus starts after this service) or is unreachable: try again soon.
                log.LogWarning("reading the core's events failed, retrying: {Error}", e.Message);
                await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);
            }
        }
    }

    private async Task<bool> HandleAsync(Message message, CancellationToken ct)
    {
        var type = Attribute(message, "eventType");
        using var activity = Telemetry.Source.StartActivity($"consume {type ?? "unknown"}", ActivityKind.Consumer);
        activity?.SetTag("messaging.system", "aws_sqs");
        activity?.SetTag("messaging.message.id", message.MessageId);
        activity?.SetTag("wallet.event_type", type);
        activity?.SetTag("tenant_id", Attribute(message, "tenantId"));
        try
        {
            using var body = JsonDocument.Parse(message.Body);
            var e = body.RootElement;
            var eventId = Attribute(message, "eventId") is { } id ? Guid.Parse(id) : e.GetProperty("eventId").GetGuid();
            await using var scope = scopes.CreateAsyncScope();
            var accounts = scope.ServiceProvider.GetRequiredService<AccountService>();
            var charges = scope.ServiceProvider.GetRequiredService<ChargeService>();
            switch (type)
            {
                case "wallet.customer.onboarded.v1":
                    await accounts.OnOpenedAsync(eventId, e.GetProperty("tenantId").GetGuid(),
                        e.GetProperty("accountId").GetGuid(), e.GetProperty("documentType").GetString()!,
                        e.GetProperty("occurredAt").GetDateTimeOffset(), ct);
                    break;
                case "wallet.account.closed.v1":
                    await accounts.OnClosedAsync(eventId, e.GetProperty("tenantId").GetGuid(),
                        e.GetProperty("accountId").GetGuid(), ct);
                    await charges.CancelPendingAsync(e.GetProperty("tenantId").GetGuid(),
                        e.GetProperty("accountId").GetGuid(), "ACCOUNT_CLOSED", ct);
                    break;
                case "wallet.transaction.posted.v1":
                    await charges.OnTransactionPostedAsync(Posted(eventId, e), ct);
                    break;
                case "wallet.account.block-released.v1":
                    await charges.OnBlockReleasedAsync(e.GetProperty("tenantId").GetGuid(),
                        e.GetProperty("accountId").GetGuid(), e.GetProperty("blockType").GetString() ?? "", ct);
                    break;
                default:
                    // The other events (a block put, a customer updated) change nothing in the fees.
                    break;
            }
            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            activity?.SetStatus(ActivityStatusCode.Error, ex.Message);
            log.LogError(ex, "core event {Type} {MessageId} failed; it will be retried", type, message.MessageId);
            return false;
        }
    }

    // The fields of wallet.transaction.posted.v1 the fee basket reads (ADR-014 of the core).
    internal static PostedTransaction Posted(Guid eventId, JsonElement e) => new(eventId,
        e.GetProperty("tenantId").GetGuid(), e.GetProperty("transactionId").GetGuid(), e.GetProperty("type").GetString()!,
        e.GetProperty("entries").EnumerateArray().Select(x => new PostedEntry(x.GetProperty("accountId").GetGuid(),
            x.GetProperty("direction").GetString()!, x.GetProperty("amountCents").GetInt64())).ToList(),
        e.GetProperty("occurredAt").GetDateTimeOffset(),
        e.TryGetProperty("relatedTransactionId", out var related) && related.ValueKind == JsonValueKind.String
            ? related.GetGuid()
            : null,
        e.TryGetProperty("reversal", out var reversal) && reversal.ValueKind == JsonValueKind.True,
        e.TryGetProperty("counterpartyDocumentType", out var doc) && doc.ValueKind == JsonValueKind.String
            ? doc.GetString()
            : null);

    private static string? Attribute(Message message, string name) =>
        message.MessageAttributes is not null && message.MessageAttributes.TryGetValue(name, out var value)
            ? value.StringValue
            : null;
}

// Brings, at start-up, the accounts the basket has not seen: the ones opened before it existed. Retries while the
// core is not up yet.
public sealed class AccountSync(ICoreAccounts core, IServiceScopeFactory scopes, ILogger<AccountSync> log)
    : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        foreach (var clientId in core.Tenants)
        {
            for (var attempt = 1; !stoppingToken.IsCancellationRequested; attempt++)
            {
                try
                {
                    var tenantId = await core.TenantIdAsync(clientId, stoppingToken);
                    var added = 0;
                    string? cursor = null;
                    do
                    {
                        var (items, next) = await core.AccountsAsync(clientId, cursor, stoppingToken);
                        await using var scope = scopes.CreateAsyncScope();
                        added += await scope.ServiceProvider.GetRequiredService<AccountService>()
                            .SyncAsync(tenantId, items, stoppingToken);
                        cursor = next;
                    } while (cursor is not null);
                    log.LogInformation("accounts of {Tenant} in step with the core: {Added} added", clientId, added);
                    break;
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    log.LogWarning("syncing the accounts of {Tenant} failed (attempt {Attempt}): {Error}", clientId,
                        attempt, e.Message);
                    await Task.Delay(TimeSpan.FromSeconds(Math.Min(60, 5 * attempt)), stoppingToken);
                }
            }
        }
    }
}

// Charges, every hour, the accounts with a pending fee: a credit or a release whose event was missed, or a core that
// did not answer (ADR-001, decision 5).
public sealed class PendingFeesSweep(IServiceScopeFactory scopes, ILogger<PendingFeesSweep> log) : BackgroundService
{
    public static readonly TimeSpan Every = TimeSpan.FromHours(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Every);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                IReadOnlyList<(Guid TenantId, Guid AccountId)> pending;
                await using (var scope = scopes.CreateAsyncScope())
                {
                    pending = await scope.ServiceProvider.GetRequiredService<ChargeService>()
                        .AccountsWithPendingAsync(stoppingToken);
                }
                foreach (var (tenantId, accountId) in pending)
                {
                    // One scope per account: a failure leaves the others untouched.
                    await using var scope = scopes.CreateAsyncScope();
                    try
                    {
                        var waiting = await scope.ServiceProvider.GetRequiredService<ChargeService>()
                            .ChargePendingAsync(tenantId, accountId, stoppingToken);
                        if (waiting is not null)
                        {
                            log.LogInformation("fees of account {Account} still wait: {Reason}", accountId, waiting);
                        }
                    }
                    catch (Exception e) when (e is not OperationCanceledException)
                    {
                        log.LogWarning("charging the pending fees of account {Account} failed: {Error}", accountId,
                            e.Message);
                    }
                }
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("the sweep of pending fees failed: {Error}", e.Message);
            }
        }
    }
}

// Creates the monthly fees once the month starts, and charges them (ADR-001, decision 7). Checks every quarter of an
// hour: the month is run once per tenant, so the checks in between do nothing.
public sealed class MonthlyFeeJob(IServiceScopeFactory scopes, ILogger<MonthlyFeeJob> log) : BackgroundService
{
    public static readonly TimeSpan Every = TimeSpan.FromMinutes(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        do
        {
            try
            {
                await RunOnceAsync(stoppingToken);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("the monthly fees failed, retrying later: {Error}", e.Message);
            }
        } while (await Delay(stoppingToken));
    }

    private static async Task<bool> Delay(CancellationToken ct)
    {
        try
        {
            await Task.Delay(Every, ct);
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private async Task RunOnceAsync(CancellationToken ct)
    {
        IReadOnlyList<(Guid TenantId, Guid AccountId)> created;
        await using (var scope = scopes.CreateAsyncScope())
        {
            using var activity = Telemetry.Source.StartActivity("monthly fees");
            created = await scope.ServiceProvider.GetRequiredService<MonthlyFeeService>().RunAsync(ct);
        }
        if (created.Count == 0)
        {
            return;
        }
        log.LogInformation("monthly fees created for {Count} accounts", created.Count);
        foreach (var (tenantId, accountId) in created)
        {
            await using var scope = scopes.CreateAsyncScope();
            try
            {
                await scope.ServiceProvider.GetRequiredService<ChargeService>().ChargePendingAsync(tenantId, accountId, ct);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                // Left pending: the sweep tries again.
                log.LogWarning("charging the monthly fee of account {Account} failed: {Error}", accountId, e.Message);
            }
        }
    }
}
