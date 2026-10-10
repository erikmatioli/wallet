-- ADR-012: within one account, occurred_at never goes back as the sequence grows. The closing balance of a
-- day is found by binary search over the sequence (no index on occurred_at), which is only correct while
-- that holds. The core stamps entries with its own clock, but two instances' clocks can be a few
-- milliseconds apart: the account remembers its last occurred_at, and each posting uses
-- GREATEST(its clock, this) - in the UPDATE that already moves the balance and the version, so no new write.
ALTER TABLE account ADD COLUMN last_occurred_at timestamptz;

-- Accounts that already have entries start from their newest one.
UPDATE account a
   SET last_occurred_at = e.last
  FROM (SELECT account_id, max(occurred_at) AS last FROM ledger_entry GROUP BY account_id) e
 WHERE e.account_id = a.id;
