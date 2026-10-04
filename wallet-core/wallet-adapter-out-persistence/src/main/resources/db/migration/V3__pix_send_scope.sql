-- Grants the new pix:send scope (debit + reversal only, see Tenant.DEFAULT_SCOPES) to tenants
-- provisioned before it existed. Granting a scope is not the same as using it: tokens still carry
-- every scope by default, and only a client that asks for scope=pix:send gets the narrow token.
UPDATE tenant
   SET scopes = scopes || ' pix:send'
 WHERE NOT (' ' || scopes || ' ') LIKE '% pix:send %';
