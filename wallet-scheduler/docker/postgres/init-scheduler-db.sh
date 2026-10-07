#!/bin/sh
# One-shot: creates the "scheduler" database and its roles on wallet-core's PostgreSQL. The
# scheduler gets its own database - it never reads or writes wallet-core's or wallet-pix's.
# Idempotent, so it can run on every "docker compose up".
set -eu

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_owner') THEN
    CREATE ROLE scheduler_owner LOGIN PASSWORD 'scheduler_owner';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'scheduler_app') THEN
    CREATE ROLE scheduler_app LOGIN PASSWORD 'scheduler_app';
  END IF;
END
$$;
SELECT 'CREATE DATABASE scheduler OWNER scheduler_owner'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'scheduler') \gexec
SQL

# Runtime role: data access only, no DDL (Flyway runs as scheduler_owner). Default privileges
# make tables created by future migrations accessible too.
psql -v ON_ERROR_STOP=1 -d scheduler <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO scheduler_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO scheduler_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO scheduler_app;
ALTER DEFAULT PRIVILEGES FOR ROLE scheduler_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO scheduler_app;
ALTER DEFAULT PRIVILEGES FOR ROLE scheduler_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO scheduler_app;
SQL
echo "scheduler database ready"
