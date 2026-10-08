-- The Pix each customer sent through the app. wallet-pix only knows the tenant (every customer of the app
-- is the same tenant), so this is what lets a customer follow their own Pix - and only their own.
CREATE TABLE sent_pix (
    end_to_end_id text        PRIMARY KEY,
    login_id      uuid        NOT NULL REFERENCES customer_login (id),
    payee_name    text        NOT NULL,
    payee_ispb    text        NOT NULL,
    amount_cents  bigint      NOT NULL CHECK (amount_cents > 0),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sent_pix_login_idx ON sent_pix (login_id, created_at DESC);
