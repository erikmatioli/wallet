-- Runtime role used by the tests: not a superuser, so Row Level Security really applies.
CREATE ROLE wallet_app LOGIN PASSWORD 'wallet_app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
