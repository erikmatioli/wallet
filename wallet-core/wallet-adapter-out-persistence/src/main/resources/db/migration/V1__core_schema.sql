-- =====================================================================================
-- Wallet Core - schema V1
--   * append-only ledger (double entry) + per-account gapless sequence
--   * balance projection with DB-level non-negative guard
--   * idempotency, transactional outbox
--   * multi-tenancy through PostgreSQL Row Level Security (session variable app.tenant_id)
-- =====================================================================================

CREATE SEQUENCE account_number_seq AS bigint START WITH 100001 MAXVALUE 99999999 NO CYCLE CACHE 20;

CREATE TABLE tenant (
    id          uuid        PRIMARY KEY,
    client_id   text        NOT NULL UNIQUE,
    secret_hash text        NOT NULL,
    name        text        NOT NULL,
    ispb        text        NOT NULL CHECK (ispb ~ '^[0-9]{8}$'),
    branch      text        NOT NULL CHECK (branch ~ '^[0-9]{4}$'),
    scopes      text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz NOT NULL
);

CREATE TABLE customer (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    name         text        NOT NULL,
    tax_id       text        NOT NULL,
    tax_id_type  text        NOT NULL CHECK (tax_id_type IN ('CPF', 'CNPJ')),
    external_ref text,
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    created_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, tax_id)
);
CREATE UNIQUE INDEX customer_tenant_external_ref_uk ON customer (tenant_id, external_ref) WHERE external_ref IS NOT NULL;

CREATE TABLE account (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    kind           text        NOT NULL CHECK (kind IN ('CUSTOMER', 'SETTLEMENT')),
    customer_id    uuid        REFERENCES customer (id),
    ispb           text,
    branch         text,
    account_number text,
    check_digit    text,
    account_type   text        CHECK (account_type IN ('TRAN')),
    status         text        NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    allow_negative boolean     NOT NULL DEFAULT false,
    balance_cents  bigint      NOT NULL DEFAULT 0,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT account_shape CHECK (
        (kind = 'CUSTOMER' AND customer_id IS NOT NULL AND ispb IS NOT NULL AND branch IS NOT NULL
             AND account_number IS NOT NULL AND check_digit IS NOT NULL AND account_type IS NOT NULL)
        OR (kind = 'SETTLEMENT' AND customer_id IS NULL)),
    -- defence in depth: even a buggy statement cannot make a customer balance negative
    CONSTRAINT account_non_negative CHECK (allow_negative OR balance_cents >= 0)
);
CREATE UNIQUE INDEX account_number_uk ON account (ispb, branch, account_number) WHERE kind = 'CUSTOMER';
CREATE INDEX account_tenant_customer_idx ON account (tenant_id, customer_id);
CREATE INDEX account_tenant_kind_idx ON account (tenant_id, kind);

CREATE TABLE financial_transaction (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    idempotency_key text        NOT NULL,
    fingerprint     text        NOT NULL,
    type            text        NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    description     text        NOT NULL DEFAULT '',
    status          text        NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED')),
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE ledger_entry (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenant (id),
    transaction_id      uuid        NOT NULL REFERENCES financial_transaction (id),
    account_id          uuid        NOT NULL REFERENCES account (id),
    sequence_no         bigint      NOT NULL CHECK (sequence_no > 0),
    direction           text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents        bigint      NOT NULL CHECK (amount_cents > 0),
    balance_after_cents bigint      NOT NULL,
    type                text        NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    description         text        NOT NULL DEFAULT '',
    occurred_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    -- gapless, strictly ordered stream per account: the basis for replaying balances
    UNIQUE (account_id, sequence_no)
);
CREATE INDEX ledger_entry_transaction_idx ON ledger_entry (transaction_id);

CREATE TABLE outbox_event (
    seq            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id             uuid        NOT NULL UNIQUE,
    tenant_id      uuid        NOT NULL,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX outbox_event_pending_idx ON outbox_event (seq) WHERE published_at IS NULL;

-- -------------------------------------------------------------------------------------
-- Immutability: ledger facts can never be changed or removed (not even by the app role)
-- -------------------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: append-only table', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '42501';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_entry_no_truncate
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER financial_transaction_immutable
    BEFORE UPDATE OR DELETE ON financial_transaction
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER financial_transaction_no_truncate
    BEFORE TRUNCATE ON financial_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- -------------------------------------------------------------------------------------
-- Double entry enforced by the database at COMMIT: sum(credits) - sum(debits) = 0
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_transaction_balanced() RETURNS trigger AS $$
DECLARE
    diff bigint;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END), 0)
      INTO diff
      FROM ledger_entry
     WHERE transaction_id = NEW.transaction_id;
    IF diff <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced (diff=%)', NEW.transaction_id, diff
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entry_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();

-- -------------------------------------------------------------------------------------
-- Multi-tenancy: Row Level Security keyed on the transaction-local setting app.tenant_id.
-- FORCE makes the policy apply to the table owner as well. NULLIF handles the empty string
-- PostgreSQL leaves behind after a transaction-local setting expires.
-- -------------------------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer', 'account', 'financial_transaction', 'ledger_entry'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
                          USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                          WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
END;
$$;

-- -------------------------------------------------------------------------------------
-- Least privilege for the runtime role (created outside Flyway, see docker/postgres/init.sql).
-- No UPDATE/DELETE on the ledger tables. Outbox: only published_at may be updated.
-- -------------------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT USAGE ON SCHEMA public TO wallet_app;
        GRANT SELECT, INSERT ON tenant, customer, financial_transaction, ledger_entry TO wallet_app;
        GRANT SELECT, INSERT, UPDATE ON account TO wallet_app;
        GRANT SELECT, INSERT ON outbox_event TO wallet_app;
        GRANT UPDATE (published_at) ON outbox_event TO wallet_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO wallet_app;
    END IF;
END;
$$;
