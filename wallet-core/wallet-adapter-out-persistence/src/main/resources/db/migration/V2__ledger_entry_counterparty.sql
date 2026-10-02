-- Adds the counterparty account to every ledger entry, so a statement can show "who was this
-- transfer with" without a second query per row. Nullable only because rows written by V1 (before
-- this column existed) do not have it; every entry written from now on always sets it (see
-- MoveMoneyService.post -> counterpartyOf). Backfilling historical rows is a one-off data-fix
-- script, not part of this migration, since it needs no behaviour change to run safely later.
ALTER TABLE ledger_entry
    ADD COLUMN counterparty_account_id uuid REFERENCES account (id);

-- Powers "for this transaction, what are its other legs" lookups efficiently - the same shape of
-- query used to backfill historical rows, and handy for ad-hoc investigation.
CREATE INDEX ledger_entry_transaction_account_idx ON ledger_entry (transaction_id, account_id);
