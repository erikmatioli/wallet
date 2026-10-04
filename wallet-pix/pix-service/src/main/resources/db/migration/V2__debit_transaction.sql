-- Outgoing Pix are now debited by this service (POST /v1/pix/payments) before the pacs.008 leaves;
-- the wallet-core withdrawal id is what a rejection reverses (POST /v1/transactions/{id}/reversals).
ALTER TABLE pix_payment ADD COLUMN debit_transaction_id uuid;
ALTER TABLE pix_payment ADD CONSTRAINT outbound_has_debit
    CHECK (direction = 'INBOUND' OR debit_transaction_id IS NOT NULL) NOT VALID;
