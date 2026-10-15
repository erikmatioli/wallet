namespace Fees.Infrastructure;

// Settings of wallet-fees, under "Fees" (appsettings.json, or environment variables such as Fees__Db__Connection).
public sealed class FeesOptions
{
    public DbOptions Db { get; set; } = new();
    public CoreOptions Core { get; set; } = new();
    public BusOptions Bus { get; set; } = new();
    public JwtOptions Jwt { get; set; } = new();

    public sealed class DbOptions
    {
        // The runtime role: data only, no DDL.
        public string Connection { get; set; } = "";
        // The owner role, only for the migrations.
        public string OwnerConnection { get; set; } = "";
    }

    public sealed class CoreOptions
    {
        public string BaseUrl { get; set; } = "http://localhost:8080";
        // One entry per tenant this service works for: it reads and charges with each tenant's own credentials.
        public List<TenantCredentials> Tenants { get; set; } = [];
    }

    public sealed class TenantCredentials
    {
        public string ClientId { get; set; } = "";
        public string ClientSecret { get; set; } = "";
    }

    public sealed class BusOptions
    {
        // Empty: the consumer does not start (tests, a service running without the bus).
        public string QueueUrl { get; set; } = "";
        public string Region { get; set; } = "us-east-1";
        // LocalStack; empty in AWS.
        public string Endpoint { get; set; } = "";
        public string AccessKey { get; set; } = "";
        public string SecretKey { get; set; } = "";
    }

    public sealed class JwtOptions
    {
        public string JwksUrl { get; set; } = "http://localhost:8080/.well-known/jwks.json";
        public string Issuer { get; set; } = "wallet-core";
    }
}
