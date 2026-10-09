#!/bin/sh
# One-shot: creates the "otp" database and its roles on wallet-core's PostgreSQL. The
# OTP engine gets its own database - it never reads or writes the other services' databases.
# Idempotent, so it can run on every "docker compose up".
set -eu

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otp_owner') THEN
    CREATE ROLE otp_owner LOGIN PASSWORD 'otp_owner';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otp_app') THEN
    CREATE ROLE otp_app LOGIN PASSWORD 'otp_app';
  END IF;
END
$$;
SELECT 'CREATE DATABASE otp OWNER otp_owner'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'otp') \gexec
SQL

# Runtime role: data access only, no DDL (Flyway runs as otp_owner). Default privileges
# make tables created by future migrations accessible too.
psql -v ON_ERROR_STOP=1 -d otp <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO otp_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO otp_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO otp_app;
ALTER DEFAULT PRIVILEGES FOR ROLE otp_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO otp_app;
ALTER DEFAULT PRIVILEGES FOR ROLE otp_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO otp_app;
SQL
echo "otp database ready"
