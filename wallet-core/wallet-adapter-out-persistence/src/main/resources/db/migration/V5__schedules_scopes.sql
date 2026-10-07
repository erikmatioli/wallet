-- Grants schedules:read and schedules:write (wallet-scheduler's API, see Tenant.DEFAULT_SCOPES) to
-- tenants provisioned before they existed - the same treatment V3 and V4 gave the Pix scopes.
UPDATE tenant
   SET scopes = scopes || ' schedules:read'
 WHERE NOT (' ' || scopes || ' ') LIKE '% schedules:read %';

UPDATE tenant
   SET scopes = scopes || ' schedules:write'
 WHERE NOT (' ' || scopes || ' ') LIKE '% schedules:write %';
