-- wallet-scheduler (ADR-001): schedule (the intent), execution (the day), attempt (each call).

CREATE TABLE schedule (
    id                      uuid        PRIMARY KEY,
    tenant_id               uuid        NOT NULL,
    -- The tenant's wallet-core client id: on the day the payment is made as that tenant.
    client_id               text        NOT NULL,
    idempotency_key         text        NOT NULL,
    fingerprint             text        NOT NULL,
    type                    text        NOT NULL CHECK (type IN ('TRANSFER')),
    payer_account_id        uuid        NOT NULL,
    -- The destination as typed, plus what wallet-core said about it when the schedule was created.
    destination_branch      text        NOT NULL,
    destination_number      text        NOT NULL,
    destination_check_digit text        NOT NULL,
    destination_account_id  uuid        NOT NULL,
    destination_holder_name text        NOT NULL,
    amount_cents            bigint      NOT NULL CHECK (amount_cents > 0),
    description             text,
    execute_on              date        NOT NULL,
    status                  text        NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED', 'COMPLETED')),
    created_at              timestamptz NOT NULL,
    cancelled_at            timestamptz,
    UNIQUE (tenant_id, idempotency_key)
);
-- The screen lists a payer account's schedules.
CREATE INDEX schedule_payer_idx ON schedule (tenant_id, payer_account_id, execute_on DESC);

CREATE TABLE schedule_execution (
    id              uuid        PRIMARY KEY,
    schedule_id     uuid        NOT NULL UNIQUE REFERENCES schedule (id),
    tenant_id       uuid        NOT NULL,
    execute_on      date        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'EXECUTED', 'FAILED', 'CANCELLED')),
    attempt_count   int         NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    lease_until     timestamptz,
    transaction_id  uuid,
    failure_code    text,
    failure_message text,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT schedule_execution_shape CHECK (
        (status = 'PENDING' AND next_attempt_at IS NOT NULL)
        OR (status = 'PROCESSING' AND lease_until IS NOT NULL)
        OR (status = 'EXECUTED' AND transaction_id IS NOT NULL)
        OR (status = 'FAILED' AND failure_code IS NOT NULL)
        OR status = 'CANCELLED')
);
-- What the job looks for: attempts due, and leases expired.
CREATE INDEX schedule_execution_due_idx ON schedule_execution (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX schedule_execution_lease_idx ON schedule_execution (lease_until) WHERE status = 'PROCESSING';

CREATE TABLE schedule_attempt (
    execution_id    uuid        NOT NULL REFERENCES schedule_execution (id),
    number          int         NOT NULL CHECK (number > 0),
    -- Written before the call to wallet-core: repeating the call with it can never pay twice.
    idempotency_key text        NOT NULL UNIQUE,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    outcome         text        CHECK (outcome IN ('EXECUTED', 'REFUSED')),
    reason_code     text,
    reason_message  text,
    transaction_id  uuid,
    PRIMARY KEY (execution_id, number),
    CONSTRAINT schedule_attempt_shape CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
-- At most one attempt in flight per execution.
CREATE UNIQUE INDEX schedule_attempt_open_uk ON schedule_attempt (execution_id) WHERE finished_at IS NULL;
