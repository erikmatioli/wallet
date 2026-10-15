-- The holder's full CPF/CNPJ in the account's list and detail, for the operator's console (customers:document).
-- The tenants that already exist get the scope, as the new ones do (Tenant.DEFAULT_SCOPES). The services that only
-- move money ask wallet-core for tokens without it.
UPDATE tenant SET scopes = scopes || ' customers:document'
 WHERE NOT (' ' || scopes || ' ') LIKE '% customers:document %';
