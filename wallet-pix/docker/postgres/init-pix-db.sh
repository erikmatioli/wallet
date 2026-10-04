#!/bin/sh
# One-shot: creates the "pix" database and its roles on wallet-core's PostgreSQL. The Pix
# service gets its own database - it never reads or writes wallet-core's.
# Idempotent, so it can run on every "docker compose up".
set -eu

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pix_owner') THEN
    CREATE ROLE pix_owner LOGIN PASSWORD 'pix_owner';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pix_app') THEN
    CREATE ROLE pix_app LOGIN PASSWORD 'pix_app';
  END IF;
END
$$;
SELECT 'CREATE DATABASE pix OWNER pix_owner'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'pix') \gexec
SQL

# Runtime role: data access only, no DDL (Flyway runs as pix_owner). Default privileges make
# tables created by future migrations accessible too.
psql -v ON_ERROR_STOP=1 -d pix <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO pix_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO pix_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO pix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pix_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pix_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO pix_app;
SQL
echo "pix database ready"
