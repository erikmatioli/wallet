#!/bin/sh
# One-shot: creates an app-api database and its roles on wallet-core's PostgreSQL. app-api gets its
# own database - the customers' logins live here, never in wallet-core's - and each tenant's instance
# gets a database of its own (APP_DB, "app" by default): logins are unique per CPF within a database.
# Idempotent, so it can run on every "docker compose up".
set -eu
DB="${APP_DB:-app}"

psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_owner') THEN
    CREATE ROLE app_owner LOGIN PASSWORD 'app_owner';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_app') THEN
    CREATE ROLE app_app LOGIN PASSWORD 'app_app';
  END IF;
END
$$;
SQL
psql -v ON_ERROR_STOP=1 -v db="$DB" <<'SQL'
SELECT format('CREATE DATABASE %I OWNER app_owner', :'db')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SQL

# Runtime role: data access only, no DDL (Flyway runs as app_owner). Default privileges make
# tables created by future migrations accessible too.
psql -v ON_ERROR_STOP=1 -d "$DB" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO app_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_app;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_app;
ALTER DEFAULT PRIVILEGES FOR ROLE app_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO app_app;
SQL
echo "$DB database ready"
