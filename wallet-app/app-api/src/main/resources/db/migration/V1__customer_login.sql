-- app-api (ADR-001): the customer's login. The money and the account live in wallet-core; here only
-- what the app needs to know who is logging in.
CREATE TABLE customer_login (
    id              uuid        PRIMARY KEY,
    -- Digits only. Unique: one login per CPF.
    cpf             text        NOT NULL UNIQUE CHECK (cpf ~ '^[0-9]{11}$'),
    name            text        NOT NULL,
    -- BCrypt; the password itself is never stored.
    password_hash   text        NOT NULL,
    -- PENDING while the signup has not finished in wallet-core (see CustomerLogin).
    status          text        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    account_id      uuid,
    failed_attempts int         NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until    timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT customer_login_active_has_account CHECK (status = 'PENDING' OR account_id IS NOT NULL)
);
