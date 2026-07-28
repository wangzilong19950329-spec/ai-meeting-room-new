#!/bin/sh
set -eu

: "${POSTGRES_ADMIN_USER:?POSTGRES_ADMIN_USER is required}"
: "${POSTGRES_ADMIN_PASSWORD:?POSTGRES_ADMIN_PASSWORD is required}"
: "${N8N_DB_NAME:?N8N_DB_NAME is required}"
: "${N8N_DB_USER:?N8N_DB_USER is required}"
: "${N8N_DB_PASSWORD:?N8N_DB_PASSWORD is required}"
: "${SW_DB_NAME:?SW_DB_NAME is required}"
: "${SW_DB_USER:?SW_DB_USER is required}"
: "${SW_DB_PASSWORD:?SW_DB_PASSWORD is required}"

export PGPASSWORD="$POSTGRES_ADMIN_PASSWORD"

sql_literal() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/''/g")"
}

sql_ident() {
  printf '"%s"' "$(printf "%s" "$1" | sed 's/"/""/g')"
}

psql_admin() {
  psql -v ON_ERROR_STOP=1 -h postgres -U "$POSTGRES_ADMIN_USER" -d postgres "$@"
}

ensure_role() {
  role="$1"
  password="$2"
  if psql_admin -tAc "SELECT 1 FROM pg_roles WHERE rolname = $(sql_literal "$role")" | grep -q 1; then
    psql_admin -c "ALTER ROLE $(sql_ident "$role") WITH LOGIN PASSWORD $(sql_literal "$password")" >/dev/null
  else
    psql_admin -c "CREATE ROLE $(sql_ident "$role") LOGIN PASSWORD $(sql_literal "$password")" >/dev/null
  fi
}

ensure_database() {
  db_name="$1"
  owner="$2"
  if psql_admin -tAc "SELECT 1 FROM pg_database WHERE datname = $(sql_literal "$db_name")" | grep -q 1; then
    psql_admin -c "ALTER DATABASE $(sql_ident "$db_name") OWNER TO $(sql_ident "$owner")" >/dev/null
  else
    createdb -h postgres -U "$POSTGRES_ADMIN_USER" --owner="$owner" "$db_name"
  fi
}

ensure_role "$N8N_DB_USER" "$N8N_DB_PASSWORD"
ensure_role "$SW_DB_USER" "$SW_DB_PASSWORD"
ensure_database "$N8N_DB_NAME" "$N8N_DB_USER"
ensure_database "$SW_DB_NAME" "$SW_DB_USER"

psql_admin -d "$SW_DB_NAME" -v ON_ERROR_STOP=1 <<SQL >/dev/null
REVOKE ALL ON DATABASE $(sql_ident "$SW_DB_NAME") FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE $(sql_ident "$SW_DB_NAME") TO $(sql_ident "$SW_DB_USER");
GRANT ALL ON SCHEMA public TO $(sql_ident "$SW_DB_USER");
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $(sql_ident "$SW_DB_USER");
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO $(sql_ident "$SW_DB_USER");
SQL

psql_admin -d "$N8N_DB_NAME" -v ON_ERROR_STOP=1 <<SQL >/dev/null
REVOKE ALL ON DATABASE $(sql_ident "$N8N_DB_NAME") FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE $(sql_ident "$N8N_DB_NAME") TO $(sql_ident "$N8N_DB_USER");
GRANT ALL ON SCHEMA public TO $(sql_ident "$N8N_DB_USER");
SQL

echo "databases and roles are ready"
