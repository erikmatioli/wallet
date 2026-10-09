-- ADR-002: no password any more; every login is a code sent to the customer's email (wallet-otp).
-- The email is nullable only for logins made before this migration: they have no email to send a code
-- to, so they cannot log in (the plan is to start from an empty database anyway).
ALTER TABLE customer_login ADD COLUMN email text CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$');
ALTER TABLE customer_login DROP COLUMN password_hash, DROP COLUMN failed_attempts, DROP COLUMN locked_until;
-- One email may serve several logins (a person's CPF and their company's CNPJ): no unique constraint.
