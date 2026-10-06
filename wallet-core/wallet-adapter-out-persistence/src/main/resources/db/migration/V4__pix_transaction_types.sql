-- ADR-010: Pix transaction types and the Pix detail of each one.
--   * PIX_IN, PIX_OUT, PIX_REFUND, PIX_RETURN_IN and PIX_RETURN_OUT join the generic types;
--   * pix_transaction_detail keeps, next to each Pix transaction, what a statement needs to show
--     it on its own (EndToEndId, counterparty, reason) - written in the same database transaction.
-- Existing rows are not reclassified: Pix posted before this migration stay DEPOSIT/WITHDRAWAL.

ALTER TABLE financial_transaction DROP CONSTRAINT financial_transaction_type_check;
ALTER TABLE financial_transaction ADD CONSTRAINT financial_transaction_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT'));

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT'));

CREATE TABLE pix_transaction_detail (
    transaction_id             uuid        PRIMARY KEY REFERENCES financial_transaction (id),
    tenant_id                  uuid        NOT NULL REFERENCES tenant (id),
    -- Same as financial_transaction.type; repeated so uniqueness and filters need no join.
    type                       text        NOT NULL CHECK (type IN (
                                               'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT')),
    end_to_end_id              text        NOT NULL,
    return_id                  text,
    related_transaction_id     uuid        REFERENCES financial_transaction (id),
    counterparty_name          text        NOT NULL,
    counterparty_tax_id_masked text        NOT NULL,
    counterparty_ispb          text        NOT NULL CHECK (counterparty_ispb ~ '^[0-9]{8}$'),
    counterparty_branch        text,
    counterparty_account       text        NOT NULL,
    counterparty_account_type  text,
    reason_code                text,
    remittance_info            text,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    -- A refund or return always points at the original Pix; only a return has a return id.
    CONSTRAINT pix_transaction_detail_shape CHECK (
        (type IN ('PIX_IN', 'PIX_OUT') AND related_transaction_id IS NULL AND return_id IS NULL)
        OR (type = 'PIX_REFUND' AND related_transaction_id IS NOT NULL AND return_id IS NULL)
        OR (type IN ('PIX_RETURN_IN', 'PIX_RETURN_OUT') AND related_transaction_id IS NOT NULL
                AND return_id IS NOT NULL))
);

-- One transaction per Pix fact, whatever Idempotency-Key the caller used. The type is part of the
-- key: a Pix between two accounts of the same tenant is a PIX_OUT and a PIX_IN with one EndToEndId.
CREATE UNIQUE INDEX pix_transaction_detail_pix_uk
    ON pix_transaction_detail (tenant_id, type, end_to_end_id, return_id) NULLS NOT DISTINCT;
-- Refunds and returns of one Pix (the "not more than the original" and "refund xor return" rules).
CREATE INDEX pix_transaction_detail_related_idx
    ON pix_transaction_detail (tenant_id, related_transaction_id) WHERE related_transaction_id IS NOT NULL;

-- Append-only, like the ledger.
CREATE TRIGGER pix_transaction_detail_immutable
    BEFORE UPDATE OR DELETE ON pix_transaction_detail
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER pix_transaction_detail_no_truncate
    BEFORE TRUNCATE ON pix_transaction_detail
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Every PIX_* transaction has its detail by COMMIT time (same idea as ledger_entry_balanced): a
-- Pix without counterparty in the statement is exactly what this ADR set out to end.
CREATE FUNCTION assert_pix_transaction_has_detail() RETURNS trigger AS $$
BEGIN
    IF NEW.type LIKE 'PIX\_%' AND NOT EXISTS (
            SELECT 1 FROM pix_transaction_detail WHERE transaction_id = NEW.id) THEN
        RAISE EXCEPTION 'Pix transaction % has no pix_transaction_detail', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER financial_transaction_pix_detail
    AFTER INSERT ON financial_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_pix_transaction_has_detail();

ALTER TABLE pix_transaction_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE pix_transaction_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pix_transaction_detail
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT SELECT, INSERT ON pix_transaction_detail TO wallet_app;
    END IF;
END;
$$;

-- pix:receive (credit an incoming Pix or return, see Tenant.DEFAULT_SCOPES) for tenants provisioned
-- before it existed - the same treatment V3 gave pix:send.
UPDATE tenant
   SET scopes = scopes || ' pix:receive'
 WHERE NOT (' ' || scopes || ' ') LIKE '% pix:receive %';
