#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env

"$SCRIPT_DIR/bootstrap.sh"
"$SCRIPT_DIR/import-workflows.sh"

mkdir -p "$ROOT_DIR/runtime/smoke"
stamp=$(date +%Y%m%d-%H%M%S)
execute_log="$ROOT_DIR/runtime/smoke/phase1-execute-$stamp.log"
db_proof="$ROOT_DIR/runtime/smoke/phase1-db-proof-$stamp.txt"

if compose run --rm n8n-import execute --id=SW01Phase1POC --rawOutput > "$execute_log" 2>&1; then
  echo "workflow execution log: $execute_log"
else
  echo "[error] workflow execution failed; tail follows" >&2
  tail -n 220 "$execute_log" >&2 || true
  exit 10
fi

compose exec -T postgres sh -lc 'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' | tee "$db_proof"
WITH latest AS (
  SELECT
    report.id,
    packet.packet_hash,
    report.report_type,
    report.status,
    report.risk_items,
    report.markdown,
    validation.status AS validation_status
  FROM sw_protection_report report
  JOIN sw_market_fact_packet packet ON packet.id = report.packet_id
  JOIN sw_structural_validation validation ON validation.id = report.validation_id
  ORDER BY report.created_at DESC
  LIMIT 1
),
proof AS (
  SELECT 'packet_hash' AS key, packet_hash AS value FROM latest
  UNION ALL
  SELECT 'report_type', report_type FROM latest
  UNION ALL
  SELECT 'report_status', status FROM latest
  UNION ALL
  SELECT 'validation_status', validation_status FROM latest
  UNION ALL
  SELECT 'has_t1_locked', (risk_items @> '[{"code":"T1_LOCKED"}]'::jsonb)::text FROM latest
  UNION ALL
  SELECT
    'markdown_forbidden_scan',
    CASE
      WHEN markdown ~* '(buy|sell|win rate|cvar|target position|automated sizing)' THEN 'fail'
      ELSE 'ok'
    END
  FROM latest
)
SELECT key || '=' || value FROM proof;
SQL

grep -q 'report_type=STRESS_ONLY' "$db_proof"
grep -q 'validation_status=accepted' "$db_proof"
grep -q 'has_t1_locked=true' "$db_proof"
grep -q 'markdown_forbidden_scan=ok' "$db_proof"

echo "phase1 smoke passed"
