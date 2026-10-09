-- wallet-otp (ADR-001): one row per code sent. The code itself is never stored, only its HMAC; the
-- destination only masked (to show) and as an HMAC (to count codes per destination).
CREATE TABLE challenge (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    -- CPF or CNPJ, normalized (digits; letters only in an alphanumeric CNPJ).
    subject            text        NOT NULL CHECK (subject ~ '^([0-9]{11}|[0-9A-Z]{12}[0-9]{2})$'),
    purpose            text        NOT NULL CHECK (purpose IN ('SIGNUP', 'LOGIN', 'PAYMENT_APPROVAL')),
    channel            text        NOT NULL CHECK (channel IN ('EMAIL')),
    destination_masked text        NOT NULL,
    destination_hash   text        NOT NULL,
    code_hash          text        NOT NULL,
    context_hash       text,
    status             text        NOT NULL CHECK (status IN ('OPEN', 'USED', 'LOCKED', 'SUPERSEDED', 'FAILED')),
    attempts           int         NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    used_at            timestamptz,
    CONSTRAINT challenge_used_has_time CHECK (status <> 'USED' OR used_at IS NOT NULL)
);
-- The send limits (ADR-001, decision 5): codes per subject and purpose, and per destination, in the last hour.
CREATE INDEX challenge_subject_idx ON challenge (tenant_id, subject, purpose, created_at DESC);
CREATE INDEX challenge_destination_idx ON challenge (tenant_id, destination_hash, created_at DESC);
-- At most one open code per subject and purpose: asking for a new one supersedes the old.
CREATE UNIQUE INDEX challenge_one_open ON challenge (tenant_id, subject, purpose) WHERE status = 'OPEN';
