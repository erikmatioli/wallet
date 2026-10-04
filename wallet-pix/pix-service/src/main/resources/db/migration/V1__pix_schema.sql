-- Pix service schema. Lives in its own database ("pix"): wallet-core's database is never touched.

-- One row per Pix and side. A Pix between two of our own tenants has two rows with the same
-- end_to_end_id: OUTBOUND (payer's PSP) and INBOUND (payee's PSP).
CREATE TABLE pix_payment (
    id                    uuid        PRIMARY KEY,
    end_to_end_id         text        NOT NULL,
    direction             text        NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    status                text        NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED', 'CREDITED',
                                                                 'SENT', 'COMPLETED', 'REFUNDED', 'RETURNED')),
    ispb                  text        NOT NULL,   -- our participant (tenant)
    counterpart_ispb      text        NOT NULL,
    pacs008_msg_id        text        NOT NULL UNIQUE,  -- what a pacs.002's OrgnlMsgId points at
    amount_cents          bigint      NOT NULL CHECK (amount_cents > 0),
    payer_name            text,
    payer_tax_id          text,
    payer_branch          text,
    payer_account         text,
    payee_name            text,
    payee_tax_id          text,
    payee_branch          text,
    payee_account         text,
    wallet_account_id     uuid,       -- our customer's wallet-core account
    request_id            text        UNIQUE,     -- OUTBOUND only: idempotency of PixPaymentRequested
    reason_code           text,
    description           text,
    wallet_transaction_id uuid,       -- credit (INBOUND) or refund/return credit (OUTBOUND)
    version               bigint      NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    UNIQUE (end_to_end_id, direction)
);

-- Duplicate detection: SQS delivers at least once and the SPI may resend. A row is written in
-- the same transaction as the state change the message caused.
CREATE TABLE inbound_message (
    message_key  text        PRIMARY KEY,
    message_type text        NOT NULL,
    processed_at timestamptz NOT NULL
);

-- Transactional outbox: messages to the SPI and events leave only if the state change committed.
CREATE TABLE outbox (
    id           bigserial   PRIMARY KEY,
    destination  text        NOT NULL CHECK (destination IN ('SPI', 'EVENTS')),
    message_type text        NOT NULL,
    payload      text        NOT NULL,
    traceparent  text,       -- W3C trace context at enqueue time, so the trace crosses the relay
    created_at   timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox (id) WHERE published_at IS NULL;
