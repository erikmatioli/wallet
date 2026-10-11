-- ADR-013: blocks and the closing of an account.
--   * account.status says only whether the account exists (ACTIVE, CLOSED); the blocks live in account_block, several
--     at once, each released on its own;
--   * account.credit_policy / debit_policy sum up the active blocks for the ledger's single balance UPDATE;
--   * account_status_event: the openings and closings, append-only, in the order wallet-ccs reads them;
--   * JUDICIAL_TRANSFER and JUDICIAL_TRANSIT_OUT, with their court reference in judicial_transfer_detail, and the
--     tenant's "depósitos judiciais a transferir" (account kind JUDICIAL_TRANSIT).

-- Nothing ever set BLOCKED (checked on 11/10/2026): the value goes without migrating a row.
ALTER TABLE account DROP CONSTRAINT account_status_check;
ALTER TABLE account ADD CONSTRAINT account_status_check CHECK (status IN ('ACTIVE', 'CLOSED'));
ALTER TABLE account ADD COLUMN credit_policy text NOT NULL DEFAULT 'ALLOWED'
    CONSTRAINT account_credit_policy_check CHECK (credit_policy IN ('ALLOWED', 'REFUND_ONLY'));
ALTER TABLE account ADD COLUMN debit_policy text NOT NULL DEFAULT 'ALLOWED'
    CONSTRAINT account_debit_policy_check CHECK (debit_policy IN ('ALLOWED', 'MED_ONLY', 'JUDICIAL_ONLY', 'NONE'));
ALTER TABLE account ADD COLUMN closed_at timestamptz;

ALTER TABLE account DROP CONSTRAINT account_kind_check;
ALTER TABLE account ADD CONSTRAINT account_kind_check
    CHECK (kind IN ('CUSTOMER', 'SETTLEMENT', 'BACKING', 'JUDICIAL_TRANSIT'));
ALTER TABLE account DROP CONSTRAINT account_shape;
ALTER TABLE account ADD CONSTRAINT account_shape CHECK (
    (kind = 'CUSTOMER' AND customer_id IS NOT NULL AND ispb IS NOT NULL AND branch IS NOT NULL
         AND account_number IS NOT NULL AND check_digit IS NOT NULL AND account_type IS NOT NULL
         AND backing_account_id IS NULL)
    OR (kind IN ('SETTLEMENT', 'JUDICIAL_TRANSIT') AND customer_id IS NULL AND backing_account_id IS NULL)
    OR (kind = 'BACKING' AND customer_id IS NULL AND backing_account_id IS NOT NULL));
CREATE UNIQUE INDEX account_judicial_transit_uk ON account (tenant_id) WHERE kind = 'JUDICIAL_TRANSIT';

ALTER TABLE financial_transaction DROP CONSTRAINT financial_transaction_type_check;
ALTER TABLE financial_transaction ADD CONSTRAINT financial_transaction_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT'));

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION', 'JUDICIAL_TRANSFER', 'JUDICIAL_TRANSIT_OUT'));

-- -------------------------------------------------------------------------------------
-- The blocks. A row is never deleted; the only change it takes is its release, once.
-- -------------------------------------------------------------------------------------
CREATE TABLE account_block (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    account_id        uuid        NOT NULL REFERENCES account (id),
    type              text        NOT NULL CHECK (type IN ('FRAUD', 'ADMINISTRATIVE', 'JUDICIAL')),
    reason            text        NOT NULL CHECK (reason IN ('FRAUD_SUSPICION', 'CUSTOMER_REQUEST',
                                      'REGISTRATION_PENDING', 'INSTITUTION_DECISION', 'COURT_ORDER', 'OTHER')),
    note              text,
    court_process     text,
    court_protocol    text,
    created_operator  text        NOT NULL,
    created_client    text        NOT NULL,
    created_at        timestamptz NOT NULL,
    released_at       timestamptz,
    released_operator text,
    released_client   text,
    release_note      text,
    CONSTRAINT account_block_judicial CHECK ((type = 'JUDICIAL') = (reason = 'COURT_ORDER')
                                             AND (type <> 'JUDICIAL' OR court_process IS NOT NULL)),
    CONSTRAINT account_block_other_note CHECK (reason <> 'OTHER' OR note IS NOT NULL),
    CONSTRAINT account_block_release CHECK ((released_at IS NULL) = (released_operator IS NULL)
                                            AND (released_at IS NULL) = (released_client IS NULL)
                                            AND (released_at IS NULL) = (release_note IS NULL))
);
CREATE INDEX account_block_account_idx ON account_block (account_id, created_at);
CREATE INDEX account_block_active_idx ON account_block (account_id) WHERE released_at IS NULL;

CREATE FUNCTION account_block_release_only() RETURNS trigger AS $$
BEGIN
    IF OLD.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'account_block % was already released', OLD.id;
    END IF;
    IF NEW.released_at IS NULL
       OR (NEW.id, NEW.tenant_id, NEW.account_id, NEW.type, NEW.reason, NEW.note, NEW.court_process,
           NEW.court_protocol, NEW.created_operator, NEW.created_client, NEW.created_at)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.account_id, OLD.type, OLD.reason, OLD.note, OLD.court_process,
           OLD.court_protocol, OLD.created_operator, OLD.created_client, OLD.created_at) THEN
        RAISE EXCEPTION 'account_block only takes its release';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER account_block_release_only
    BEFORE UPDATE ON account_block
    FOR EACH ROW EXECUTE FUNCTION account_block_release_only();
CREATE TRIGGER account_block_no_delete
    BEFORE DELETE ON account_block
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER account_block_no_truncate
    BEFORE TRUNCATE ON account_block
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- -------------------------------------------------------------------------------------
-- Openings and closings. seq is global; the repository takes a per-tenant lock before drawing it, so within a
-- tenant the seqs commit in order and a reader that saw n never finds a smaller one later (a rollback only
-- leaves a gap).
-- -------------------------------------------------------------------------------------
CREATE SEQUENCE account_status_event_seq AS bigint;
CREATE TABLE account_status_event (
    seq         bigint      PRIMARY KEY DEFAULT nextval('account_status_event_seq'),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    account_id  uuid        NOT NULL REFERENCES account (id),
    from_status text        CHECK (from_status IN ('ACTIVE', 'CLOSED')),
    to_status   text        NOT NULL CHECK (to_status IN ('ACTIVE', 'CLOSED')),
    occurred_at timestamptz NOT NULL,
    operator    text        NOT NULL,
    client_id   text        NOT NULL,
    reason      text        CHECK (reason IN ('CUSTOMER_REQUEST', 'INSTITUTION_DECISION', 'DEATH', 'OTHER')),
    note        text
);
ALTER SEQUENCE account_status_event_seq OWNED BY account_status_event.seq;
CREATE INDEX account_status_event_feed_idx ON account_status_event (tenant_id, seq);
CREATE INDEX account_status_event_account_idx ON account_status_event (account_id);
CREATE TRIGGER account_status_event_immutable
    BEFORE UPDATE OR DELETE ON account_status_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER account_status_event_no_truncate
    BEFORE TRUNCATE ON account_status_event
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- -------------------------------------------------------------------------------------
-- The court reference of a judicial transfer and of its TED. Append-only, like the ledger.
-- -------------------------------------------------------------------------------------
CREATE TABLE judicial_transfer_detail (
    transaction_id         uuid        PRIMARY KEY REFERENCES financial_transaction (id),
    tenant_id              uuid        NOT NULL REFERENCES tenant (id),
    kind                   text        NOT NULL CHECK (kind IN ('TRANSFER', 'TRANSIT_OUT')),
    account_id             uuid        REFERENCES account (id),
    block_id               uuid        NOT NULL REFERENCES account_block (id),
    court_process          text        NOT NULL,
    court_protocol         text,
    to_transit             boolean     NOT NULL,
    related_transaction_id uuid        REFERENCES financial_transaction (id),
    operator               text        NOT NULL,
    client_id              text        NOT NULL,
    note                   text,
    document               text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT judicial_transfer_detail_shape CHECK (
        (kind = 'TRANSFER' AND account_id IS NOT NULL AND related_transaction_id IS NULL)
        OR (kind = 'TRANSIT_OUT' AND account_id IS NULL AND related_transaction_id IS NOT NULL
            AND document IS NOT NULL AND NOT to_transit))
);
CREATE INDEX judicial_transfer_detail_related_idx ON judicial_transfer_detail (related_transaction_id)
    WHERE related_transaction_id IS NOT NULL;
CREATE INDEX judicial_transfer_detail_transit_idx ON judicial_transfer_detail (tenant_id) WHERE to_transit;
CREATE TRIGGER judicial_transfer_detail_immutable
    BEFORE UPDATE OR DELETE ON judicial_transfer_detail
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER judicial_transfer_detail_no_truncate
    BEFORE TRUNCATE ON judicial_transfer_detail
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['account_block', 'account_status_event', 'judicial_transfer_detail'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
                          USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                          WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT SELECT, INSERT ON account_block, account_status_event, judicial_transfer_detail TO wallet_app;
        GRANT UPDATE (released_at, released_operator, released_client, release_note) ON account_block TO wallet_app;
        GRANT USAGE, SELECT ON SEQUENCE account_status_event_seq TO wallet_app;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- Existing tenants: the judicial transit, and the opening (and, for a closed account, the closing) of every
-- customer account, at the date it was created - the CCS starts from the whole history. RLS is forced on the owner
-- too, so each tenant's rows are written with app.tenant_id set to it.
-- -------------------------------------------------------------------------------------
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT id, client_id FROM tenant ORDER BY created_at LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        INSERT INTO account (id, tenant_id, kind, status, allow_negative, balance_cents, version, created_at,
                             updated_at)
        VALUES (gen_random_uuid(), t.id, 'JUDICIAL_TRANSIT', 'ACTIVE', false, 0, 0, now(), now());

        INSERT INTO account_status_event (tenant_id, account_id, from_status, to_status, occurred_at, operator,
                                          client_id)
        SELECT t.id, a.id, NULL, 'ACTIVE', a.created_at, 'sistema', t.client_id
          FROM account a
         WHERE a.tenant_id = t.id AND a.kind = 'CUSTOMER'
         ORDER BY a.created_at, a.id;

        UPDATE account SET closed_at = updated_at
         WHERE tenant_id = t.id AND kind = 'CUSTOMER' AND status = 'CLOSED';
        INSERT INTO account_status_event (tenant_id, account_id, from_status, to_status, occurred_at, operator,
                                          client_id, reason)
        SELECT t.id, a.id, 'ACTIVE', 'CLOSED', a.closed_at, 'sistema', t.client_id, 'OTHER'
          FROM account a
         WHERE a.tenant_id = t.id AND a.kind = 'CUSTOMER' AND a.status = 'CLOSED'
         ORDER BY a.closed_at, a.id;
    END LOOP;
END;
$$;

-- Only now that the closed customer accounts have their date. The settlement shards were closed by V9 as history,
-- without one: the rule is the customer accounts'.
ALTER TABLE account ADD CONSTRAINT account_closed_at
    CHECK (kind <> 'CUSTOMER' OR (status = 'CLOSED') = (closed_at IS NOT NULL));

-- The new scopes for the tenants provisioned before they existed - the same treatment V3 to V9 gave the others.
UPDATE tenant SET scopes = scopes || ' accounts:block'    WHERE NOT (' ' || scopes || ' ') LIKE '% accounts:block %';
UPDATE tenant SET scopes = scopes || ' accounts:judicial' WHERE NOT (' ' || scopes || ' ') LIKE '% accounts:judicial %';
UPDATE tenant SET scopes = scopes || ' accounts:close'    WHERE NOT (' ' || scopes || ' ') LIKE '% accounts:close %';
UPDATE tenant SET scopes = scopes || ' ccs:read'          WHERE NOT (' ' || scopes || ' ') LIKE '% ccs:read %';
