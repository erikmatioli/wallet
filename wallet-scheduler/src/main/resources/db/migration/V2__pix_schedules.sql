-- Pix schedules (ADR-001, step 3): the payee as the Pix service needs it on the day, the payer's
-- CPF/CNPJ (the Pix service checks it against the account holder), and the EndToEndId of what was sent.

ALTER TABLE schedule DROP CONSTRAINT schedule_type_check;
ALTER TABLE schedule ADD CONSTRAINT schedule_type_check CHECK (type IN ('TRANSFER', 'PIX'));

-- The transfer destination columns only apply to transfers now.
ALTER TABLE schedule ALTER COLUMN destination_branch DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN destination_number DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN destination_check_digit DROP NOT NULL;
ALTER TABLE schedule ALTER COLUMN destination_account_id DROP NOT NULL;

-- destination_holder_name stays NOT NULL: it is the payee's name for a Pix too.
ALTER TABLE schedule
    ADD COLUMN payer_tax_id         text,
    ADD COLUMN pix_payee_ispb       text,
    ADD COLUMN pix_payee_branch     text,
    ADD COLUMN pix_payee_account    text,
    -- Kept in full until the day, because sending needs it. The API only shows it masked; for
    -- production this column (and payer_tax_id) should be encrypted, as noted for wallet-pix's database.
    ADD COLUMN pix_payee_tax_id     text,
    ADD CONSTRAINT schedule_destination_shape CHECK (
        (type = 'TRANSFER' AND destination_account_id IS NOT NULL AND destination_branch IS NOT NULL
             AND destination_number IS NOT NULL AND destination_check_digit IS NOT NULL
             AND payer_tax_id IS NULL AND pix_payee_ispb IS NULL)
        OR (type = 'PIX' AND payer_tax_id IS NOT NULL AND pix_payee_ispb IS NOT NULL
             AND pix_payee_branch IS NOT NULL AND pix_payee_account IS NOT NULL AND pix_payee_tax_id IS NOT NULL
             AND destination_account_id IS NULL));

ALTER TABLE schedule_execution ADD COLUMN end_to_end_id text;
ALTER TABLE schedule_attempt ADD COLUMN end_to_end_id text;
