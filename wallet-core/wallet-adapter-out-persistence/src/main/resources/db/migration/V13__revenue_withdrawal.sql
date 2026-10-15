-- ADR-015: the treasury takes the tenant's revenue out of the platform. REVENUE_WITHDRAWAL debits the revenue
-- account and credits the backing account the money left from; what happened outside the ledger (the destination,
-- the receipt, who did it) goes in revenue_withdrawal_detail. Append-only, like the other details.

ALTER TABLE financial_transaction DROP CONSTRAINT financial_transaction_type_check;
ALTER TABLE financial_transaction ADD CONSTRAINT financial_transaction_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT', 'FEE', 'FEE_REFUND',
    'REVENUE_WITHDRAWAL'));

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT', 'FEE', 'FEE_REFUND',
    'REVENUE_WITHDRAWAL'));

CREATE TABLE revenue_withdrawal_detail (
    transaction_id      uuid        PRIMARY KEY REFERENCES financial_transaction (id),
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    backing_account_id  uuid        NOT NULL REFERENCES backing_account (id),
    destination_bank    text        NOT NULL,
    destination_branch  text        NOT NULL,
    destination_account text        NOT NULL,
    document            text        NOT NULL,
    note                text,
    operator            text        NOT NULL,
    client_id           text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX revenue_withdrawal_detail_tenant_idx ON revenue_withdrawal_detail (tenant_id);
CREATE TRIGGER revenue_withdrawal_detail_immutable
    BEFORE UPDATE OR DELETE ON revenue_withdrawal_detail
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER revenue_withdrawal_detail_no_truncate
    BEFORE TRUNCATE ON revenue_withdrawal_detail
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

ALTER TABLE revenue_withdrawal_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE revenue_withdrawal_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON revenue_withdrawal_detail
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT SELECT, INSERT ON revenue_withdrawal_detail TO wallet_app;
    END IF;
END;
$$;
