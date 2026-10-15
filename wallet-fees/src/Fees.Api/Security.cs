using System.Security.Claims;
using Fees.Infrastructure;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;

namespace Fees.Api;

// The core issues the tokens (Basic -> JWT); this service only checks them against the core's public keys (JWKS),
// like wallet-pix and wallet-scheduler. The keys are kept for ten minutes, and fetched again for an unknown key id.
public sealed class CoreSigningKeys(HttpClient http, IOptions<FeesOptions> options, TimeProvider clock,
    ILogger<CoreSigningKeys> log)
{
    private readonly object gate = new();
    private IReadOnlyList<SecurityKey> keys = [];
    private DateTimeOffset fetchedAt = DateTimeOffset.MinValue;

    public IEnumerable<SecurityKey> Resolve(string? kid)
    {
        var stale = clock.GetUtcNow() - fetchedAt > TimeSpan.FromMinutes(10);
        var unknown = kid is not null && keys.All(k => k.KeyId != kid);
        if (stale || unknown)
        {
            Refresh();
        }
        return keys;
    }

    private void Refresh()
    {
        lock (gate)
        {
            try
            {
                var json = http.GetStringAsync(options.Value.Jwt.JwksUrl).GetAwaiter().GetResult();
                keys = new JsonWebKeySet(json).GetSigningKeys().ToList();
                fetchedAt = clock.GetUtcNow();
            }
            catch (Exception e)
            {
                // Keeps the keys it had: a core restart should not log everyone out.
                log.LogWarning("could not read the core's JWKS: {Error}", e.Message);
            }
        }
    }
}

public static class Scopes
{
    public const string Read = "fees:read";
    public const string Admin = "fees:admin";

    public static bool Has(ClaimsPrincipal user, string scope) =>
        (user.FindFirst("scope")?.Value ?? "").Split(' ', StringSplitOptions.RemoveEmptyEntries).Contains(scope);

    // The tenant comes from the token the core signed, never from the request.
    public static Guid TenantOf(ClaimsPrincipal user) =>
        Guid.Parse(user.FindFirst("tenant_id")?.Value ?? throw new UnauthorizedAccessException("token without tenant"));

    public static string ClientOf(ClaimsPrincipal user) => user.FindFirst("sub")?.Value ?? "unknown";

    public static void AddCoreJwt(this IServiceCollection services)
    {
        services.AddHttpClient<CoreSigningKeys>();
        services.AddSingleton<CoreSigningKeys>(sp => new CoreSigningKeys(
            sp.GetRequiredService<IHttpClientFactory>().CreateClient(nameof(CoreSigningKeys)),
            sp.GetRequiredService<IOptions<FeesOptions>>(), sp.GetRequiredService<TimeProvider>(),
            sp.GetRequiredService<ILogger<CoreSigningKeys>>()));
        services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer();
        services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
            .Configure<CoreSigningKeys, IOptions<FeesOptions>>((jwt, keys, fees) =>
            {
                jwt.MapInboundClaims = false; // keep "scope", "sub" and "tenant_id" as the core wrote them
                jwt.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidIssuer = fees.Value.Jwt.Issuer,
                    ValidateAudience = false,
                    IssuerSigningKeyResolver = (_, _, kid, _) => keys.Resolve(kid),
                    ClockSkew = TimeSpan.FromSeconds(30),
                };
            });
        services.AddAuthorizationBuilder()
            .AddPolicy(Read, p => p.RequireAssertion(c => Has(c.User, Read) || Has(c.User, Admin)))
            .AddPolicy(Admin, p => p.RequireAssertion(c => Has(c.User, Admin)));
    }
}
