using System.Text.Json;
using Amazon.SQS;
using Amazon.SQS.Model;
using Fees.Application;
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
        try
        {
            using var body = JsonDocument.Parse(message.Body);
            var e = body.RootElement;
            var eventId = Attribute(message, "eventId") is { } id ? Guid.Parse(id) : e.GetProperty("eventId").GetGuid();
            await using var scope = scopes.CreateAsyncScope();
            var accounts = scope.ServiceProvider.GetRequiredService<AccountService>();
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
                    break;
                default:
                    // Transactions and blocks are for the charging step (ADR-001, decision 10); nothing to do yet.
                    break;
            }
            return true;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            log.LogError(ex, "core event {Type} {MessageId} failed; it will be retried", type, message.MessageId);
            return false;
        }
    }

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
