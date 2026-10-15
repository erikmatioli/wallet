using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Fees.Application;
using Microsoft.Extensions.Options;

namespace Fees.Infrastructure.Core;

// The core's API, called with each tenant's own credentials (client id and secret -> a token with only the scopes
// this service needs). Tokens are kept until shortly before they expire.
public sealed class CoreClient(HttpClient http, IOptions<FeesOptions> options, TimeProvider clock) : ICoreAccounts
{
    private const string Scopes = "accounts:read fees:write";

    private readonly ConcurrentDictionary<string, (string Token, Guid TenantId, DateTimeOffset Until)> tokens = new();

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
}
