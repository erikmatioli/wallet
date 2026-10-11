-- ADR-011: backing accounts (contas de lastro).
--   * backing_account: a place where the tenant keeps money (Conta PI, Caixa, A regularizar, Reservas...);
--   * its balance lives in internal ledger accounts of kind BACKING (shards), linked by account.backing_account_id;
--   * TREASURY_TRANSFER moves money between two of them, with its receipt in treasury_transfer_detail;
--   * the settlement's balance is reclassified, by rail, into the backing accounts: one RECLASSIFICATION
--     transaction per tenant. The ledger is immutable: the settlement keeps its history, at zero and CLOSED.

CREATE TABLE backing_account (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    type         text        NOT NULL CHECK (type IN (
                                 'PIX_PI', 'RESERVES', 'CCME', 'INVESTMENT', 'CASH', 'CREDIT_PORTFOLIO', 'UNASSIGNED')),
    name         text        NOT NULL,
    external_ref text,
    created_at   timestamptz NOT NULL
);
-- The rails route to these three: exactly one of each per tenant.
CREATE UNIQUE INDEX backing_account_default_uk ON backing_account (tenant_id, type)
    WHERE type IN ('PIX_PI', 'CASH', 'UNASSIGNED');
CREATE INDEX backing_account_tenant_idx ON backing_account (tenant_id);

ALTER TABLE account ADD COLUMN backing_account_id uuid REFERENCES backing_account (id);
ALTER TABLE account DROP CONSTRAINT account_kind_check;
ALTER TABLE account ADD CONSTRAINT account_kind_check CHECK (kind IN ('CUSTOMER', 'SETTLEMENT', 'BACKING'));
ALTER TABLE account DROP CONSTRAINT account_shape;
ALTER TABLE account ADD CONSTRAINT account_shape CHECK (
    (kind = 'CUSTOMER' AND customer_id IS NOT NULL AND ispb IS NOT NULL AND branch IS NOT NULL
         AND account_number IS NOT NULL AND check_digit IS NOT NULL AND account_type IS NOT NULL
         AND backing_account_id IS NULL)
    OR (kind = 'SETTLEMENT' AND customer_id IS NULL AND backing_account_id IS NULL)
    OR (kind = 'BACKING' AND customer_id IS NULL AND backing_account_id IS NOT NULL));
CREATE INDEX account_backing_idx ON account (backing_account_id) WHERE kind = 'BACKING';

ALTER TABLE financial_transaction DROP CONSTRAINT financial_transaction_type_check;
ALTER TABLE financial_transaction ADD CONSTRAINT financial_transaction_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION'));

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_type_check;
ALTER TABLE ledger_entry ADD CONSTRAINT ledger_entry_type_check CHECK (type IN (
    'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'PIX_IN', 'PIX_OUT', 'PIX_REFUND', 'PIX_RETURN_IN', 'PIX_RETURN_OUT',
    'TREASURY_TRANSFER', 'RECLASSIFICATION'));

-- What the treasury did outside the ledger: the receipt and a note. Append-only, like the ledger.
CREATE TABLE treasury_transfer_detail (
    transaction_id uuid        PRIMARY KEY REFERENCES financial_transaction (id),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    document       text,
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER treasury_transfer_detail_immutable
    BEFORE UPDATE OR DELETE ON treasury_transfer_detail
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER treasury_transfer_detail_no_truncate
    BEFORE TRUNCATE ON treasury_transfer_detail
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['backing_account', 'treasury_transfer_detail'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
                          USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                          WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT SELECT, INSERT ON backing_account, treasury_transfer_detail TO wallet_app;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- Existing tenants: the three default backing accounts (as many shards as the settlement had), then one
-- RECLASSIFICATION that zeroes the settlement and puts its balance where each rail keeps the money:
--   Pix (every PIX_*)                          -> Conta PI
--   WITHDRAWAL, and the DEPOSIT that reverses one (idempotency key 'reversal:...') -> Caixa
--   any other DEPOSIT                          -> A regularizar (money created, not covered yet)
-- RLS is forced on the owner too, so each tenant's rows are written with app.tenant_id set to it.
-- -------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE reclassification_leg (account_id uuid PRIMARY KEY, delta bigint NOT NULL) ON COMMIT DROP;

DO $$
DECLARE
    t            record;
    leg          record;
    shards       int;
    b_type       text;
    b_id         uuid;
    tx_id        uuid;
    occurred     timestamptz;
    total        bigint;
    new_balance  bigint;
    new_version  bigint;
    descr        text := 'Reclassificação: o saldo da settlement vai para as contas de lastro (ADR-011)';
BEGIN
    FOR t IN SELECT id FROM tenant ORDER BY created_at LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        SELECT count(*) INTO shards FROM account WHERE tenant_id = t.id AND kind = 'SETTLEMENT';
        shards := GREATEST(shards, 1);
        FOREACH b_type IN ARRAY ARRAY['PIX_PI', 'CASH', 'UNASSIGNED'] LOOP
            b_id := gen_random_uuid();
            INSERT INTO backing_account (id, tenant_id, type, name, external_ref, created_at)
            VALUES (b_id, t.id, b_type,
                    CASE b_type WHEN 'PIX_PI' THEN 'Conta PI' WHEN 'CASH' THEN 'Caixa' ELSE 'A regularizar' END,
                    NULL, now());
            FOR i IN 1..shards LOOP
                INSERT INTO account (id, tenant_id, kind, backing_account_id, status, allow_negative, balance_cents,
                                     version, created_at, updated_at)
                VALUES (gen_random_uuid(), t.id, 'BACKING', b_id, 'ACTIVE', true, 0, 0, now(), now());
            END LOOP;
        END LOOP;

        TRUNCATE reclassification_leg;
        -- Zero every settlement shard...
        INSERT INTO reclassification_leg (account_id, delta)
        SELECT id, -balance_cents FROM account
         WHERE tenant_id = t.id AND kind = 'SETTLEMENT' AND balance_cents <> 0;
        -- ...and put the same total, by rail, on the first shard of each backing account. "Expected" is the money
        -- that entered that place (a debit of the settlement) minus what left it; the shard keeps it negative.
        INSERT INTO reclassification_leg (account_id, delta)
        SELECT (SELECT a.id FROM account a JOIN backing_account b ON b.id = a.backing_account_id
                 WHERE b.tenant_id = t.id AND b.type = x.btype ORDER BY a.id LIMIT 1),
               -x.expected
          FROM (SELECT CASE WHEN e.type LIKE 'PIX\_%' THEN 'PIX_PI'
                            WHEN e.type = 'WITHDRAWAL' OR f.idempotency_key LIKE 'reversal:%' THEN 'CASH'
                            ELSE 'UNASSIGNED' END AS btype,
                       SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END) AS expected
                  FROM ledger_entry e
                  JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
                  JOIN financial_transaction f ON f.id = e.transaction_id
                 WHERE e.tenant_id = t.id
                 GROUP BY 1) x
         WHERE x.expected <> 0;

        SELECT COALESCE(SUM(-delta) FILTER (WHERE delta < 0), 0) INTO total FROM reclassification_leg;
        IF total > 0 THEN
            tx_id := gen_random_uuid();
            -- Never before an entry the accounts already have (ADR-012).
            SELECT GREATEST(now(), max(a.last_occurred_at)) INTO occurred
              FROM account a JOIN reclassification_leg l ON l.account_id = a.id;
            INSERT INTO financial_transaction (id, tenant_id, idempotency_key, fingerprint, type, amount_cents,
                                               description, occurred_at)
            VALUES (tx_id, t.id, 'reclassification:adr-011', 'adr-011', 'RECLASSIFICATION', total, descr, occurred);
            FOR leg IN SELECT account_id, delta FROM reclassification_leg ORDER BY account_id LOOP
                UPDATE account
                   SET balance_cents = balance_cents + leg.delta,
                       version = version + 1,
                       last_occurred_at = GREATEST(last_occurred_at, occurred),
                       updated_at = now()
                 WHERE id = leg.account_id
                RETURNING balance_cents, version INTO new_balance, new_version;
                INSERT INTO ledger_entry (id, tenant_id, transaction_id, account_id, sequence_no, direction,
                                          amount_cents, balance_after_cents, type, description,
                                          counterparty_account_id, occurred_at)
                VALUES (gen_random_uuid(), t.id, tx_id, leg.account_id, new_version,
                        CASE WHEN leg.delta < 0 THEN 'DEBIT' ELSE 'CREDIT' END, abs(leg.delta), new_balance,
                        'RECLASSIFICATION', descr, NULL, occurred);
            END LOOP;
        END IF;

        -- History only from now on: nothing routes to the settlement any more.
        UPDATE account SET status = 'CLOSED', updated_at = now() WHERE tenant_id = t.id AND kind = 'SETTLEMENT';
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END;
$$;

-- treasury:write (register backing accounts and move money between them, see Tenant.DEFAULT_SCOPES) for tenants
-- provisioned before it existed - the same treatment V3 to V6 gave the other scopes.
UPDATE tenant
   SET scopes = scopes || ' treasury:write'
 WHERE NOT (' ' || scopes || ' ') LIKE '% treasury:write %';
