-- wallet-fees, ADR-001: packages, their schedules, and the accounts as the fee basket sees them.
-- Every row carries tenant_id and every query filters by it: the tenant comes from the token, never from the request.

CREATE TABLE package (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    code          text        NOT NULL,
    name          text        NOT NULL,
    customer_type text        NOT NULL CHECK (customer_type IN ('PF', 'PJ')),
    is_default    boolean     NOT NULL,
    created_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, code)
);
-- The basic of each customer type: where every account starts.
CREATE UNIQUE INDEX package_default_uk ON package (tenant_id, customer_type) WHERE is_default;

-- The prices of a package from a date on (Brasília). A new price is a new schedule; the old ones stay.
CREATE TABLE fee_schedule (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    package_id        uuid        NOT NULL REFERENCES package (id),
    valid_from        date        NOT NULL,
    monthly_fee_cents bigint      NOT NULL CHECK (monthly_fee_cents >= 0),
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    UNIQUE (package_id, valid_from)
);

CREATE TABLE fee_schedule_price (
    schedule_id              uuid    NOT NULL REFERENCES fee_schedule (id) ON DELETE CASCADE,
    operation                text    NOT NULL,
    free_per_month           integer NOT NULL CHECK (free_per_month >= 0),
    price_cents              bigint  NOT NULL CHECK (price_cents >= 0),
    -- Only a Pix received: its price when a company paid it.
    price_from_company_cents bigint  CHECK (price_from_company_cents >= 0),
    PRIMARY KEY (schedule_id, operation)
);

CREATE TABLE fee_account (
    tenant_id     uuid        NOT NULL,
    account_id    uuid        PRIMARY KEY,
    customer_type text        NOT NULL CHECK (customer_type IN ('PF', 'PJ')),
    package_id    uuid        NOT NULL REFERENCES package (id),
    status        text        NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    since         timestamptz NOT NULL
);
CREATE INDEX fee_account_tenant_idx ON fee_account (tenant_id);

-- Who moved an account between packages, and when. The first row is its start in the basic package.
CREATE TABLE package_change (
    id              bigserial   PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    account_id      uuid        NOT NULL REFERENCES fee_account (account_id),
    from_package_id uuid        REFERENCES package (id),
    to_package_id   uuid        NOT NULL REFERENCES package (id),
    operator        text        NOT NULL,
    client_id       text        NOT NULL,
    changed_at      timestamptz NOT NULL
);
CREATE INDEX package_change_account_idx ON package_change (account_id, changed_at);

-- The bus delivers at least once: an event handled once is marked here, in the same transaction as its effect.
CREATE TABLE processed_message (
    message_id   uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);
