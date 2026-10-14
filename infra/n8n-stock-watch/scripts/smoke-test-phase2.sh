#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

require_cmd curl
require_cmd jq
require_cmd node

"$SCRIPT_DIR/bootstrap.sh" >/dev/null
"$SCRIPT_DIR/import-workflows.sh" >/dev/null
load_env

mkdir -p "$ROOT_DIR/runtime/smoke"
stamp=$(date +%Y%m%d-%H%M%S)
proof_file="$ROOT_DIR/runtime/smoke/phase2-db-proof-$stamp.txt"
fence_proof_file="$ROOT_DIR/runtime/smoke/phase2-fence-proof-$stamp.txt"

reset_runtime_flags() {
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' >/dev/null 2>&1 || true
UPDATE sw_runtime_control
SET ingest_enabled = false,
    analysis_enabled = false,
    publisher_enabled = false,
    notify_enabled = false,
    model_provider = 'MOCK',
    manual_test_mode = false,
    manual_test_trade_date = NULL,
    manual_test_run_slot = NULL,
    updated_at = now()
WHERE id = 1;
SQL
}
trap reset_runtime_flags EXIT INT TERM

execute_success() {
  workflow_id="$1"
  log_file="$2"
  if ! compose run --rm n8n-import execute --id="$workflow_id" --rawOutput > "$log_file" 2>&1; then
    echo "[error] workflow $workflow_id failed" >&2
    tail -n 220 "$log_file" >&2 || true
    return 1
  fi
  grep -q '"status": "success"' "$log_file"
}

node --test "$SCRIPT_DIR/phase2-domain.test.js"

deepseek_no_auth=$(curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8793/v1/models)
deepseek_wrong_auth=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer wrong' http://127.0.0.1:8793/v1/models)
deepseek_valid_auth=$(curl -sS -o /tmp/sw-deepseek-models.json -w '%{http_code}' \
  -H "Authorization: Bearer $DEEPSEEK_FACADE_API_KEY" http://127.0.0.1:8793/v1/models)
[ "$deepseek_no_auth" = 401 ]
[ "$deepseek_wrong_auth" = 401 ]
[ "$deepseek_valid_auth" = 200 ]
jq -e '.data | map(.id) | index("deepseek-web-bridge") != null' /tmp/sw-deepseek-models.json >/dev/null

java_no_auth=$(curl -sS -o /dev/null -w '%{http_code}' \
  'http://127.0.0.1:8127/api/stock-watch/n8n/quotes?symbols=300750.sz')
java_valid_auth=$(curl -sS -o /tmp/sw-java-quote.json -w '%{http_code}' \
  -H "X-N8N-Token: $JAVA_API_TOKEN" \
  'http://127.0.0.1:8127/api/stock-watch/n8n/quotes?symbols=300750.sz')
[ "$java_no_auth" = 401 ]
[ "$java_valid_auth" = 200 ]
jq -e '.schemaVersion == "stock-watch.n8n.quote.v1" and .itemCount == 1' /tmp/sw-java-quote.json >/dev/null
java_quality=$(jq -r '.items[0].quality.status' /tmp/sw-java-quote.json)

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL'
DELETE FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot = '10:00';

WITH snapshot AS (
  INSERT INTO sw_quote_snapshot (
    symbol, market, quote_time, fetched_at, provider, source, quality_status,
    price, open, day_high, day_low, previous_close,
    cumulative_volume_raw, cumulative_volume_shares, volume_unit,
    cumulative_amount_raw, amount_unit, limitations, raw_payload
  ) VALUES (
    '300750', 'sz', '2026-07-31 02:00:00+00', '2026-07-31 02:00:05+00',
    'PHASE2_TEST', 'LOCAL_QA_FIXTURE', 'PARTIAL',
    100.30, 100, 101, 98, 99.5, 130, 13000, 'LOT_100_SHARES',
    1300000, 'CNY',
    '["LOCAL_QA_FIXTURE","UNIT_CONTRACT_NOT_AUTHORITATIVE"]'::jsonb,
    '{"fixture":true}'::jsonb
  )
  ON CONFLICT (symbol, market, provider, quote_time) DO UPDATE SET price = EXCLUDED.price
  RETURNING id
), chosen AS (
  SELECT id FROM snapshot
  UNION ALL
  SELECT id FROM sw_quote_snapshot
  WHERE symbol = '300750' AND market = 'sz' AND provider = 'PHASE2_TEST'
    AND quote_time = '2026-07-31 02:00:00+00'
  LIMIT 1
)
INSERT INTO sw_bar_1m (
  symbol, market, minute_start, open, high, low, close,
  volume_delta_raw, volume_delta_shares, amount_delta, volume_unit, amount_unit,
  vwap, source, quality_status, limitations, last_snapshot_id
)
SELECT
  '300750', 'sz',
  '2026-07-31 01:30:00+00'::timestamptz + (n || ' minutes')::interval,
  100 + n * 0.01, 100.10 + n * 0.01, 99.90 + n * 0.01, 100 + n * 0.01,
  1, 100, 10000, 'LOT_100_SHARES', 'CNY', NULL,
  'LOCAL_QA_FIXTURE', 'APPROXIMATE',
  '["UNIT_CONTRACT_NOT_AUTHORITATIVE"]'::jsonb, chosen.id
FROM generate_series(0, 30) AS n
CROSS JOIN chosen
ON CONFLICT (symbol, market, minute_start) DO UPDATE SET
  close = EXCLUDED.close,
  vwap = NULL,
  limitations = EXCLUDED.limitations,
  last_snapshot_id = EXCLUDED.last_snapshot_id;
SQL

if [ "$java_quality" = "STALE" ] || [ "$java_quality" = "INVALID" ] || [ "$java_quality" = "SOURCE_ERROR" ]; then
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' >/dev/null
UPDATE sw_runtime_control SET ingest_enabled = true, manual_test_mode = true WHERE id = 1;
SQL
  java_gate_log="$ROOT_DIR/runtime/smoke/phase2-java-gate-$stamp.log"
  if compose run --rm n8n-import execute --id=SW10MinuteQuoteIngest --rawOutput > "$java_gate_log" 2>&1; then
    echo "[error] SW-10 accepted a non-usable Java quote" >&2
    exit 21
  fi
  grep -Eq 'QUOTE_REJECTED:(STALE|INVALID|SOURCE_ERROR)' "$java_gate_log"
else
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' >/dev/null
UPDATE sw_runtime_control SET ingest_enabled = true, manual_test_mode = true WHERE id = 1;
SQL
  execute_success SW10MinuteQuoteIngest "$ROOT_DIR/runtime/smoke/phase2-java-ingest-$stamp.log"
fi

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' >/dev/null
UPDATE sw_runtime_control
SET ingest_enabled = false,
    analysis_enabled = true,
    publisher_enabled = false,
    notify_enabled = false,
    model_provider = 'MOCK',
    manual_test_mode = true,
    manual_test_trade_date = '2026-07-31',
    manual_test_run_slot = '10:00'
WHERE id = 1;
SQL

execute_success SW20HalfHourAnalysis "$ROOT_DIR/runtime/smoke/phase2-sw20-$stamp.log"

mail_before=$(curl -sS http://127.0.0.1:8025/api/v1/messages \
  | jq '[.messages[]? | select(.Subject | startswith("[LOCAL TEST] Stock Watch report "))] | length')

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' >/dev/null
UPDATE sw_runtime_control
SET analysis_enabled = false,
    publisher_enabled = true,
    notify_enabled = true,
    notification_transport = 'MAILPIT',
    notification_recipient = 'stock-watch-test@local.invalid'
WHERE id = 1;
SQL

execute_success SW30StressReportPublisher "$ROOT_DIR/runtime/smoke/phase2-sw30-first-$stamp.log"
execute_success SW30StressReportPublisher "$ROOT_DIR/runtime/smoke/phase2-sw30-second-$stamp.log"

mail_after=$(curl -sS http://127.0.0.1:8025/api/v1/messages \
  | jq '[.messages[]? | select(.Subject | startswith("[LOCAL TEST] Stock Watch report "))] | length')
[ "$((mail_after - mail_before))" -eq 1 ]

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -At -v ON_ERROR_STOP=1' <<'SQL' | tee "$proof_file"
WITH target_job AS (
  SELECT * FROM sw_analysis_job
  WHERE symbol = '300750' AND market = 'sz'
    AND trade_date = '2026-07-31' AND run_slot = '10:00'
  ORDER BY id DESC LIMIT 1
), proof AS (
  SELECT 'job_status' AS key, job.status::text AS value
  FROM target_job job
  UNION ALL
  SELECT 'validation_status', validation.status
  FROM target_job job
  JOIN sw_market_fact_packet packet ON packet.job_id = job.id
  JOIN sw_model_run model ON model.job_id = job.id
  JOIN sw_structural_validation validation ON validation.model_run_id = model.id
  UNION ALL
  SELECT 'model_input_leaks_symbol', (packet.packet->'model_input' ? 'symbol')::text
  FROM target_job job JOIN sw_market_fact_packet packet ON packet.job_id = job.id
  UNION ALL
  SELECT 'packet_source_kind', packet.source_kind
  FROM target_job job JOIN sw_market_fact_packet packet ON packet.job_id = job.id
  UNION ALL
  SELECT 'vwap_fact_status', packet.packet->'facts'->'F_PRICE_VS_VWAP'->>'quality_status'
  FROM target_job job JOIN sw_market_fact_packet packet ON packet.job_id = job.id
  UNION ALL
  SELECT 'report_type', report.report_type
  FROM target_job job JOIN sw_protection_report report ON report.job_id = job.id
  UNION ALL
  SELECT 'scenario_count', jsonb_array_length(report.risk_items->0->'scenarios')::text
  FROM target_job job JOIN sw_protection_report report ON report.job_id = job.id
  UNION ALL
  SELECT 'report_claim_status', claim.status
  FROM target_job job JOIN sw_report_claim claim ON claim.job_id = job.id
  UNION ALL
  SELECT 'delivery_status', delivery.status
  FROM target_job job
  JOIN sw_protection_report report ON report.job_id = job.id
  JOIN sw_notification_delivery delivery ON delivery.report_id = report.id
)
SELECT key || '=' || value FROM proof ORDER BY key;
SQL

grep -q '^job_status=SUCCESS$' "$proof_file"
grep -q '^validation_status=accepted$' "$proof_file"
grep -q '^model_input_leaks_symbol=false$' "$proof_file"
grep -q '^packet_source_kind=LOCAL_QA_FIXTURE$' "$proof_file"
grep -q '^vwap_fact_status=UNAVAILABLE$' "$proof_file"
grep -q '^report_type=STRESS_ONLY$' "$proof_file"
grep -q '^scenario_count=8$' "$proof_file"
grep -q '^report_claim_status=COMPLETED$' "$proof_file"
grep -q '^delivery_status=SENT_LOCAL_TEST$' "$proof_file"

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -At -v ON_ERROR_STOP=1' <<'SQL' | tee "$fence_proof_file"
BEGIN;
UPDATE sw_analysis_job
SET status = 'RUNNING', attempt_count = 1, locked_by = 'qa-attempt-a', locked_at = now()
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot = '10:00';
UPDATE sw_analysis_job
SET status = 'RETRY', locked_by = NULL, locked_at = NULL
WHERE locked_by = 'qa-attempt-a';
UPDATE sw_analysis_job
SET status = 'RUNNING', attempt_count = 2, locked_by = 'qa-attempt-b', locked_at = now()
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot = '10:00';
WITH stale_attempt AS (
  UPDATE sw_analysis_job
  SET last_error_code = 'STALE_ATTEMPT_MUST_NOT_WRITE'
  WHERE symbol = '300750' AND market = 'sz'
    AND trade_date = '2026-07-31' AND run_slot = '10:00'
    AND status = 'RUNNING'
    AND locked_by = 'qa-attempt-a'
    AND attempt_count = 1
  RETURNING id
)
SELECT 'stale_attempt_updates=' || count(*) FROM stale_attempt;
ROLLBACK;
SQL
grep -q '^stale_attempt_updates=0$' "$fence_proof_file"

reset_runtime_flags
trap - EXIT INT TERM

compose exec -T postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -At -v ON_ERROR_STOP=1' <<'SQL' | grep -qx 'f|f|f|f|MOCK|f'
SELECT ingest_enabled, analysis_enabled, publisher_enabled, notify_enabled, model_provider, manual_test_mode
FROM sw_runtime_control WHERE id = 1;
SQL

"$SCRIPT_DIR/resolve-workflow-bindings.sh" >/dev/null
echo "phase2/phase3 local smoke passed; proof: $proof_file"
