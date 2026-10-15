-- wallet-fees, ADR-001 step 3: the use of the free quota and the fees to charge.

-- One row per priced transaction: what counts against the month's free quota of its account and operation. A
-- reversed transaction gives its place back (step 4) by being marked, never deleted.
CREATE TABLE fee_usage (
    tenant_id             uuid        NOT NULL,
    account_id            uuid        NOT NULL,
    month                 text        NOT NULL,    -- Brasília's, "2026-10"
    operation             text        NOT NULL,
    source_transaction_id uuid        NOT NULL,
    occurred_at           timestamptz NOT NULL,
    reversed              boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (source_transaction_id, operation)
);
CREATE INDEX fee_usage_count_idx ON fee_usage (account_id, month, operation) WHERE NOT reversed;

-- A fee to charge. Charged whole by the core (FEE), under an idempotency key derived from its origin.
CREATE TABLE fee_charge (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    account_id            uuid        NOT NULL,
    operation             text        NOT NULL,
    source_transaction_id uuid,
    reference             text,
    amount_cents          bigint      NOT NULL CHECK (amount_cents > 0),
    description           text        NOT NULL,
    idempotency_key       text        NOT NULL UNIQUE,
    status                text        NOT NULL CHECK (status IN ('PENDING', 'CHARGED', 'CANCELLED', 'REFUNDED')),
    core_transaction_id   uuid,
    attempts              integer     NOT NULL DEFAULT 0,
    last_error            text,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);
CREATE INDEX fee_charge_account_idx ON fee_charge (account_id, created_at);
CREATE INDEX fee_charge_pending_idx ON fee_charge (account_id, created_at) WHERE status = 'PENDING';
CREATE INDEX fee_charge_source_idx ON fee_charge (source_transaction_id) WHERE source_transaction_id IS NOT NULL;
