-- ADR-014: what the core needs for the fee basket (wallet-fees).
--   * TENANT_REVENUE: the tenant's revenue account, one per tenant, where the fees go;
--   * FEE and FEE_REFUND, with what they carry in fee_detail;
--   * the fee block (FEE_PENDING, reason UNPAID_FEES) and its debit policy FEE_ONLY;
--   * the type (CPF/CNPJ) of a Pix counterparty's document - never the document itself;
--   * the fees:* scopes.

ALTER TABLE account DROP CONSTRAINT account_kind_check;
ALTER TABLE account ADD CONSTRAINT account_kind_check
    CHECK (kind IN ('CUSTOMER', 'SETTLEMENT', 'BACKING', 'JUDICIAL_TRANSIT', 'TENANT_REVENUE'));
ALTER TABLE account DROP CONSTRAINT account_shape;
ALTER TABLE account ADD CONSTRAINT account_shape CHECK (
    (kind = 'CUSTOMER' AND customer_id IS NOT NULL AND ispb IS NOT NULL AND branch IS NOT NULL
         AND account_number IS NOT NULL AND check_digit IS NOT NULL AND account_type IS NOT NULL
         AND backing_account_id IS NULL)
    OR (kind IN ('SETTLEMENT', 'JUDICIAL_TRANSIT', 'TENANT_REVENUE') AND customer_id IS NULL
        AND backing_account_id IS NULL)
    OR (kind = 'BACKING' AND customer_id IS NULL AND backing_account_id IS NOT NULL));
CREATE UNIQUE INDEX account_tenant_revenue_uk ON account (tenant_id) WHERE kind = 'TENANT_REVENUE';

ALTER TABLE account DROP CONSTRAINT account_debit_policy_check;
ALTER TABLE account ADD CONSTRAINT account_debit_policy_check
    CHECK (debit_policy IN ('ALLOWED', 'MED_ONLY', 'JUDICIAL_ONLY', 'FEE_ONLY', 'NONE'));

ALTER TABLE financial_transaction DROP CONSTRAINT financial_transaction_type_check;
ALTER TABLE financial_transaction ADD CONSTRAINT financial_transaction_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT', 'FEE', 'FEE_REFUND'));

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT', 'FEE', 'FEE_REFUND'));

-- The fee block: only wallet-fees creates it, only for unpaid fees.
ALTER TABLE account_block DROP CONSTRAINT account_block_type_check;
ALTER TABLE account_block ADD CONSTRAINT account_block_type_check
    CHECK (type IN ('FRAUD', 'ADMINISTRATIVE', 'JUDICIAL', 'FEE_PENDING'));
ALTER TABLE account_block DROP CONSTRAINT account_block_reason_check;
ALTER TABLE account_block ADD CONSTRAINT account_block_reason_check CHECK (reason IN ('FRAUD_SUSPICION',
    'CUSTOMER_REQUEST', 'REGISTRATION_PENDING', 'INSTITUTION_DECISION', 'COURT_ORDER', 'UNPAID_FEES', 'OTHER'));
ALTER TABLE account_block ADD CONSTRAINT account_block_fee CHECK ((type = 'FEE_PENDING') = (reason = 'UNPAID_FEES'));

-- Taken while the full document is in hand; the Pix posted before this stay without it.
ALTER TABLE pix_transaction_detail ADD COLUMN counterparty_tax_id_type text
    CONSTRAINT pix_transaction_detail_tax_id_type_check CHECK (counterparty_tax_id_type IN ('CPF', 'CNPJ'));

-- What a fee and a fee refund carry besides the ledger. Append-only, like the ledger.
CREATE TABLE fee_detail (
    transaction_id        uuid        PRIMARY KEY REFERENCES financial_transaction (id),
    tenant_id             uuid        NOT NULL REFERENCES tenant (id),
    operation             text        NOT NULL,
    source_transaction_id uuid        REFERENCES financial_transaction (id),
    reference             text,
    created_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fee_detail_source_idx ON fee_detail (source_transaction_id) WHERE source_transaction_id IS NOT NULL;
CREATE TRIGGER fee_detail_immutable
    BEFORE UPDATE OR DELETE ON fee_detail
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER fee_detail_no_truncate
    BEFORE TRUNCATE ON fee_detail
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

ALTER TABLE fee_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fee_detail
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT SELECT, INSERT ON fee_detail TO wallet_app;
    END IF;
END;
$$;

-- Existing tenants: their revenue account. RLS is forced on the owner too, so each one is written as its tenant.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT id FROM tenant ORDER BY created_at LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        INSERT INTO account (id, tenant_id, kind, status, allow_negative, balance_cents, version, created_at,
                             updated_at)
        VALUES (gen_random_uuid(), t.id, 'TENANT_REVENUE', 'ACTIVE', false, 0, 0, now(), now());
    END LOOP;
END;
$$;

UPDATE tenant SET scopes = scopes || ' fees:read'  WHERE NOT (' ' || scopes || ' ') LIKE '% fees:read %';
UPDATE tenant SET scopes = scopes || ' fees:admin' WHERE NOT (' ' || scopes || ' ') LIKE '% fees:admin %';
UPDATE tenant SET scopes = scopes || ' fees:write' WHERE NOT (' ' || scopes || ' ') LIKE '% fees:write %';
