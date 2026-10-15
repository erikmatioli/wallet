-- wallet-fees, ADR-001 step 4: the refund of a fee when its transaction is reversed, and the monthly fee.

-- The core's FEE_REFUND that gave a fee back.
ALTER TABLE fee_charge ADD COLUMN refund_transaction_id uuid;

-- The transactions the core reversed. A reversal read before its transaction (the queue does not keep the order)
-- leaves its mark here, and the transaction then takes no place in the quota and no fee.
CREATE TABLE reversed_transaction (
    transaction_id uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    reversed_at    timestamptz NOT NULL
);

-- The months whose monthly fees were created, per tenant: once each, and the missing ones when the service returns.
CREATE TABLE monthly_run (
    tenant_id uuid        NOT NULL,
    month     text        NOT NULL,    -- Brasília's, "2026-11"
    ran_at    timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, month)
);
