using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fees.Application;
using Fees.Domain;
using Microsoft.Extensions.Options;

namespace Fees.Infrastructure.Core;

// What outlives one CoreClient (a typed HttpClient is created per use): the tokens, kept until shortly before they
// expire, and the core's internal accounts, which appear in every Pix and are asked about once.
public sealed class CoreCache
{
    public readonly ConcurrentDictionary<string, (string Token, Guid TenantId, DateTimeOffset Until)> Tokens = new();
    public readonly ConcurrentDictionary<Guid, bool> NotCustomers = new();
}

// The core's API, called with each tenant's own credentials (client id and secret -> a token with only the scopes
// this service needs).
public sealed class CoreClient(HttpClient http, IOptions<FeesOptions> options, TimeProvider clock, CoreCache cache)
    : ICoreAccounts, ICoreFees
{
    private const string Scopes = "accounts:read fees:write";
    private const string Operator = "wallet-fees";

    private ConcurrentDictionary<string, (string Token, Guid TenantId, DateTimeOffset Until)> tokens => cache.Tokens;

    public IReadOnlyList<string> Tenants => options.Value.Core.Tenants.Select(t => t.ClientId).ToList();

    public async Task<Guid> TenantIdAsync(string clientId, CancellationToken ct = default) =>
        (await TokenAsync(clientId, ct)).TenantId;

    public async Task<(IReadOnlyList<CoreAccount> Items, string? Next)> AccountsAsync(string clientId, string? cursor,
        CancellationToken ct = default)
    {
        var url = "/v1/accounts?limit=200" + (cursor is null ? "" : "&cursor=" + Uri.EscapeDataString(cursor));
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", (await TokenAsync(clientId, ct)).Token);
        using var response = await http.SendAsync(request, ct);
        response.EnsureSuccessStatusCode();
        var page = await response.Content.ReadFromJsonAsync<AccountPage>(ct)
                   ?? throw new InvalidOperationException("empty answer from the core");
        return (page.Items.Select(i => new CoreAccount(i.AccountId, i.DocumentType ?? "CPF", i.Status)).ToList(),
            page.NextCursor);
    }

    public async Task<CoreFeeResult> ChargeAsync(FeeCharge charge, CancellationToken ct = default)
    {
        try
        {
            using var response = await SendAsync(charge.TenantId, HttpMethod.Post,
                $"/v1/accounts/{charge.AccountId}/fees", new
                {
                    amount = charge.AmountCents / 100m, operation = charge.Operation, description = charge.Description,
                    sourceTransactionId = charge.SourceTransactionId, reference = charge.Reference,
                }, ct, charge.IdempotencyKey);
            if (response.IsSuccessStatusCode)
            {
                var tx = await response.Content.ReadFromJsonAsync<TransactionItem>(ct);
                return tx is null ? CoreFeeResult.Unavailable : CoreFeeResult.Ok(tx.Id);
            }
            if ((int)response.StatusCode >= 500)
            {
                return CoreFeeResult.Unavailable;
            }
            return CoreFeeResult.Refused(await CodeAsync(response, ct) ?? $"HTTP_{(int)response.StatusCode}");
        }
        catch (HttpRequestException)
        {
            return CoreFeeResult.Unavailable;
        }
        catch (TaskCanceledException) when (!ct.IsCancellationRequested)
        {
            return CoreFeeResult.Unavailable; // a timeout
        }
    }

    public async Task<Guid> RefundAsync(FeeCharge charge, CancellationToken ct = default)
    {
        var feeTransactionId = charge.CoreTransactionId
                               ?? throw new InvalidOperationException($"fee {charge.Id} was not charged");
        using var response = await SendAsync(charge.TenantId, HttpMethod.Post, $"/v1/fees/{feeTransactionId}/refunds",
            new { description = charge.RefundDescription }, ct);
        response.EnsureSuccessStatusCode();
        var tx = await response.Content.ReadFromJsonAsync<TransactionItem>(ct)
                 ?? throw new InvalidOperationException("empty answer from the core");
        return tx.Id;
    }

    public async Task EnsureFeeBlockAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
    {
        if ((await FeeBlocksAsync(tenantId, accountId, ct)).Count > 0)
        {
            return;
        }
        using var response = await SendAsync(tenantId, HttpMethod.Post, $"/v1/accounts/{accountId}/blocks", new
        {
            type = "FEE_PENDING", reason = "UNPAID_FEES", @operator = Operator,
            note = "Tarifas em aberto: cobradas no próximo crédito",
        }, ct);
        if (response.StatusCode == HttpStatusCode.UnprocessableEntity
            && await CodeAsync(response, ct) is "ACCOUNT_CLOSED")
        {
            return; // closed meanwhile: its fees are cancelled by the closing event
        }
        response.EnsureSuccessStatusCode();
    }

    public async Task ReleaseFeeBlocksAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
    {
        foreach (var blockId in await FeeBlocksAsync(tenantId, accountId, ct))
        {
            using var response = await SendAsync(tenantId, HttpMethod.Post,
                $"/v1/accounts/{accountId}/blocks/{blockId}/release",
                new { @operator = Operator, note = "Tarifas pagas" }, ct);
            response.EnsureSuccessStatusCode();
        }
    }

    public async Task<CoreAccount?> AccountAsync(Guid tenantId, Guid accountId, CancellationToken ct = default)
    {
        if (cache.NotCustomers.ContainsKey(accountId))
        {
            return null;
        }
        using var response = await SendAsync(tenantId, HttpMethod.Get, $"/v1/accounts/{accountId}", null, ct);
        if (response.StatusCode == HttpStatusCode.NotFound)
        {
            cache.NotCustomers[accountId] = true;
            return null;
        }
        response.EnsureSuccessStatusCode();
        var account = await response.Content.ReadFromJsonAsync<AccountDetail>(ct)
                      ?? throw new InvalidOperationException("empty answer from the core");
        if (account.CustomerId is null)
        {
            cache.NotCustomers[accountId] = true; // an internal account: PI, cash, revenue
            return null;
        }
        return new CoreAccount(account.Id, account.DocumentType ?? "CPF", account.Status);
    }

    private async Task<IReadOnlyList<Guid>> FeeBlocksAsync(Guid tenantId, Guid accountId, CancellationToken ct)
    {
        using var response = await SendAsync(tenantId, HttpMethod.Get, $"/v1/accounts/{accountId}/blocks?active=true",
            null, ct);
        response.EnsureSuccessStatusCode();
        var blocks = await response.Content.ReadFromJsonAsync<BlockPage>(ct);
        return blocks?.Items.Where(b => b.Type == "FEE_PENDING" && b.Active).Select(b => b.Id).ToList() ?? [];
    }

    private async Task<HttpResponseMessage> SendAsync(Guid tenantId, HttpMethod method, string url, object? body,
        CancellationToken ct, string? idempotencyKey = null)
    {
        using var request = new HttpRequestMessage(method, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await TokenOfAsync(tenantId, ct));
        if (idempotencyKey is not null)
        {
            request.Headers.Add("Idempotency-Key", idempotencyKey);
        }
        if (body is not null)
        {
            request.Content = JsonContent.Create(body);
        }
        return await http.SendAsync(request, ct);
    }

    // The token of a tenant by its id: the events carry the tenant id, the credentials are by client id.
    private async Task<string> TokenOfAsync(Guid tenantId, CancellationToken ct)
    {
        foreach (var clientId in Tenants)
        {
            if (tokens.TryGetValue(clientId, out var known) && known.TenantId != tenantId)
            {
                continue;
            }
            var token = await TokenAsync(clientId, ct);
            if (token.TenantId == tenantId)
            {
                return token.Token;
            }
        }
        throw new InvalidOperationException($"no credentials for tenant {tenantId}");
    }

    // The stable code of the core's problem+json answer.
    private static async Task<string?> CodeAsync(HttpResponseMessage response, CancellationToken ct)
    {
        try
        {
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
            return json.RootElement.TryGetProperty("code", out var code) ? code.GetString() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private async Task<(string Token, Guid TenantId, DateTimeOffset Until)> TokenAsync(string clientId,
        CancellationToken ct)
    {
        if (tokens.TryGetValue(clientId, out var cached) && cached.Until > clock.GetUtcNow())
        {
            return cached;
        }
        var credentials = options.Value.Core.Tenants.FirstOrDefault(t => t.ClientId == clientId)
                          ?? throw new InvalidOperationException($"no credentials for tenant {clientId}");
        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/auth/token?scope=" + Uri.EscapeDataString(Scopes));
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic",
            Convert.ToBase64String(Encoding.UTF8.GetBytes($"{credentials.ClientId}:{credentials.ClientSecret}")));
        using var response = await http.SendAsync(request, ct);
        response.EnsureSuccessStatusCode();
        var token = await response.Content.ReadFromJsonAsync<TokenResponse>(ct)
                    ?? throw new InvalidOperationException("empty token from the core");
        var fresh = (token.AccessToken, TenantOf(token.AccessToken),
            clock.GetUtcNow().AddSeconds(Math.Max(token.ExpiresIn - 30, 5)));
        tokens[clientId] = fresh;
        return fresh;
    }

    // The tenant is in the token the core signed: its "tenant_id" claim.
    internal static Guid TenantOf(string jwt)
    {
        var payload = jwt.Split('.')[1].Replace('-', '+').Replace('_', '/');
        payload = payload.PadRight(payload.Length + (4 - payload.Length % 4) % 4, '=');
        using var json = JsonDocument.Parse(Convert.FromBase64String(payload));
        return json.RootElement.GetProperty("tenant_id").GetGuid();
    }

    private sealed record TokenResponse(
        [property: JsonPropertyName("access_token")] string AccessToken,
        [property: JsonPropertyName("expires_in")] int ExpiresIn);

    private sealed record AccountPage(List<AccountItem> Items, string? NextCursor);

    private sealed record AccountItem(Guid AccountId, string Status, string? DocumentType);

    private sealed record AccountDetail(Guid Id, string Status, Guid? CustomerId, string? DocumentType);

    private sealed record TransactionItem(Guid Id);

    private sealed record BlockPage(List<BlockItem> Items);

    private sealed record BlockItem(Guid Id, string Type, bool Active);
}
