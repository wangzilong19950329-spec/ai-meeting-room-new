#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
compose exec -T postgres psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
SELECT version, applied_at FROM sw_schema_migrations ORDER BY version;
SELECT COUNT(*) AS protection_reports FROM sw_protection_report;
SQL
echo "recovery-readiness check completed"
