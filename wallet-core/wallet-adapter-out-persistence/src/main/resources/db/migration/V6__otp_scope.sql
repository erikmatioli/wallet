-- Grants otp:use (wallet-otp's API, see Tenant.DEFAULT_SCOPES) to tenants provisioned before it
-- existed - the same treatment V5 gave the schedules scopes.
UPDATE tenant
   SET scopes = scopes || ' otp:use'
 WHERE NOT (' ' || scopes || ' ') LIKE '% otp:use %';
