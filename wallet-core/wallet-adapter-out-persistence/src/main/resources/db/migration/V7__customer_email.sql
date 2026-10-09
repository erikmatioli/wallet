-- ADR-003 of wallet-app: the customer's email, registered by the operator at onboarding (console) or
-- later. Products that talk to the end customer (the app's backend) send login codes to it: it came from
-- someone who knows the customer, not from whoever is asking. Optional; personal data, never in events.
ALTER TABLE customer ADD COLUMN email text
    CHECK (email IS NULL OR (email = lower(email) AND email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'));

-- The application role may now update one column of a customer, the email - nothing else (same
-- conditional grant as V1: the role does not exist in every environment, e.g. Testcontainers).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wallet_app') THEN
        GRANT UPDATE (email) ON customer TO wallet_app;
    END IF;
END
$$;
