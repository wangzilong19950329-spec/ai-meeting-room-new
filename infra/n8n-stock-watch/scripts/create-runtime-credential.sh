#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
: "${SW_DB_NAME:?SW_DB_NAME is required}"
: "${SW_DB_USER:?SW_DB_USER is required}"
: "${SW_DB_PASSWORD:?SW_DB_PASSWORD is required}"

mkdir -p "$ROOT_DIR/runtime/credentials"
credential_file="$ROOT_DIR/runtime/credentials/sw-postgres.credentials.json"
umask 077
cat > "$credential_file" <<JSON
[
  {
    "id": "SWPostgresLocal",
    "name": "SW PostgreSQL",
    "type": "postgres",
    "data": {
      "host": "postgres",
      "database": "$SW_DB_NAME",
      "user": "$SW_DB_USER",
      "password": "$SW_DB_PASSWORD",
      "port": 5432,
      "ssl": "disable",
      "allowUnauthorizedCerts": false
    }
  }
]
JSON
printf '%s\n' "$credential_file"
