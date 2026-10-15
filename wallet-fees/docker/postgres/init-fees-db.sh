#!/bin/sh
# One-shot: creates the "fees" database and its roles on wallet-core's PostgreSQL. wallet-fees gets its own
# database - it never reads or writes wallet-core's. Idempotent, so it can run on every "docker compose up".
set -eu

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fees_owner') THEN
    CREATE ROLE fees_owner LOGIN PASSWORD 'fees_owner';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fees_app') THEN
    CREATE ROLE fees_app LOGIN PASSWORD 'fees_app';
  END IF;
END
$$;
SELECT 'CREATE DATABASE fees OWNER fees_owner'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fees') \gexec
SQL

# Runtime role: data access only, no DDL (the migrations run as fees_owner). Default privileges make tables
# created by future migrations accessible too.
psql -v ON_ERROR_STOP=1 -d fees <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fees_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fees_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fees_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fees_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO fees_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fees_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO fees_app;
SQL
echo "fees database ready"
