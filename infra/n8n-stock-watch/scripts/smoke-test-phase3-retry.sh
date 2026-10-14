#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

require_cmd curl
require_cmd jq
require_cmd node
load_env

mkdir -p "$ROOT_DIR/runtime/smoke"
stamp=$(date +%Y%m%d-%H%M%S)
proof_file="$ROOT_DIR/runtime/smoke/phase3-retry-proof-$stamp.txt"
fixture_log="$ROOT_DIR/runtime/smoke/phase3-retry-fixture-$stamp.log"
ledger_file="$ROOT_DIR/runtime/smoke/phase3-retry-ledger-$stamp.json"
fixture_pid=''
original_deepseek_base_url='http://deepseek-facade:8793/v1'

db() {
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1'
}

db_at() {
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -At -v ON_ERROR_STOP=1'
}

original_deepseek_base_url=$(db_at <<'SQL'
SELECT deepseek_base_url FROM sw_runtime_control WHERE id = 1;
SQL
)

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

cleanup() {
  compose exec -T -e ORIGINAL_DEEPSEEK_BASE_URL="$original_deepseek_base_url" postgres sh -lc \
    'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1 -v original_url="$ORIGINAL_DEEPSEEK_BASE_URL"' <<'SQL' >/dev/null 2>&1 || true
UPDATE sw_runtime_control
SET ingest_enabled = false,
    analysis_enabled = false,
    publisher_enabled = false,
    notify_enabled = false,
    model_provider = 'MOCK',
    deepseek_base_url = :'original_url',
    manual_test_mode = false,
    manual_test_trade_date = NULL,
    manual_test_run_slot = NULL,
    updated_at = now()
WHERE id = 1;
DELETE FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot IN ('10:30', '11:00');
DELETE FROM sw_bar_1m
WHERE symbol = '300750' AND market = 'sz' AND source IN ('LOCAL_RETRY_QA', 'LOCAL_UNKNOWN_QA');
DELETE FROM sw_quote_snapshot
WHERE symbol = '300750' AND market = 'sz' AND provider IN ('PHASE3_RETRY_QA', 'PHASE3_UNKNOWN_QA');
SQL
  if [ -n "$fixture_pid" ]; then
    kill "$fixture_pid" >/dev/null 2>&1 || true
    wait "$fixture_pid" >/dev/null 2>&1 || true
  fi
  rm -f "$ledger_file" "$ledger_file".*.tmp
}
trap cleanup EXIT INT TERM

DEEPSEEK_FACADE_API_KEY="$DEEPSEEK_FACADE_API_KEY" \
  PHASE3_RETRY_LEDGER_PATH="$ledger_file" \
  node "$SCRIPT_DIR/phase3-retry-fixture.js" > "$fixture_log" 2>&1 &
fixture_pid=$!
ready=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS http://127.0.0.1:8794/health >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[ "$ready" = true ]

db <<'SQL' >/dev/null
DELETE FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot = '10:30';

WITH snapshot AS (
  INSERT INTO sw_quote_snapshot (
    symbol, market, quote_time, fetched_at, provider, source, quality_status,
    price, open, day_high, day_low, previous_close,
    cumulative_volume_raw, cumulative_volume_shares, volume_unit,
    cumulative_amount_raw, amount_unit, limitations, raw_payload
  ) VALUES (
    '300750', 'sz', '2026-07-31 02:30:00+00', '2026-07-31 02:30:05+00',
    'PHASE3_RETRY_QA', 'LOCAL_QA_FIXTURE', 'PARTIAL',
    100.60, 100, 101, 98, 99.5, 160, 16000, 'LOT_100_SHARES',
    1600000, 'CNY', '["LOCAL_QA_FIXTURE"]'::jsonb, '{"fixture":true}'::jsonb
  )
  ON CONFLICT (symbol, market, provider, quote_time) DO UPDATE SET price = EXCLUDED.price
  RETURNING id
)
INSERT INTO sw_bar_1m (
  symbol, market, minute_start, open, high, low, close,
  volume_delta_raw, volume_delta_shares, amount_delta, volume_unit, amount_unit,
  vwap, source, quality_status, limitations, last_snapshot_id
)
SELECT
  '300750', 'sz', '2026-07-31 02:00:00+00'::timestamptz + (n || ' minutes')::interval,
  100 + n * 0.01, 100.10 + n * 0.01, 99.90 + n * 0.01, 100 + n * 0.01,
  1, 100, 10000, 'LOT_100_SHARES', 'CNY', NULL,
  'LOCAL_RETRY_QA', 'APPROXIMATE', '["LOCAL_QA_FIXTURE"]'::jsonb, snapshot.id
FROM generate_series(1, 30) AS n
CROSS JOIN snapshot
ON CONFLICT (symbol, market, minute_start) DO UPDATE SET
  close = EXCLUDED.close,
  source = EXCLUDED.source,
  last_snapshot_id = EXCLUDED.last_snapshot_id;

UPDATE sw_runtime_control
SET ingest_enabled = false,
    analysis_enabled = true,
    publisher_enabled = false,
    notify_enabled = false,
    model_provider = 'DEEPSEEK_WEB',
    deepseek_base_url = 'http://host.docker.internal:8794/v1',
    manual_test_mode = true,
    manual_test_trade_date = '2026-07-31',
    manual_test_run_slot = '10:30',
    updated_at = now()
WHERE id = 1;
SQL

execute_success SW20HalfHourAnalysis "$ROOT_DIR/runtime/smoke/phase3-retry-first-$stamp.log"

db_at <<'SQL' | tee "$proof_file"
SELECT 'first_status=' || status FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz' AND trade_date = '2026-07-31' AND run_slot = '10:30';
SELECT 'first_attempt_count=' || attempt_count FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz' AND trade_date = '2026-07-31' AND run_slot = '10:30';
SELECT 'first_error=' || last_error_code FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz' AND trade_date = '2026-07-31' AND run_slot = '10:30';
SQL
grep -q '^first_status=RETRY$' "$proof_file"
grep -q '^first_attempt_count=1$' "$proof_file"
grep -q '^first_error=MODEL_TIMEOUT$' "$proof_file"

sleep 16
execute_success SW20HalfHourAnalysis "$ROOT_DIR/runtime/smoke/phase3-retry-second-$stamp.log"

db <<'SQL' >/dev/null
UPDATE sw_runtime_control
SET analysis_enabled = false, publisher_enabled = true, notify_enabled = false
WHERE id = 1;
SQL
[ "$(db_at <<'SQL'
SELECT count(*)
FROM sw_analysis_job job
WHERE job.status IN ('ANALYZED', 'INSUFFICIENT_DATA')
  AND NOT EXISTS (SELECT 1 FROM sw_protection_report report WHERE report.job_id = job.id)
  AND NOT EXISTS (SELECT 1 FROM sw_report_claim claim WHERE claim.job_id = job.id);
SQL
)" = 1 ]
execute_success SW30StressReportPublisher "$ROOT_DIR/runtime/smoke/phase3-retry-publisher-$stamp.log"

db_at <<'SQL' | tee -a "$proof_file"
WITH target_job AS (
  SELECT * FROM sw_analysis_job
  WHERE symbol = '300750' AND market = 'sz'
    AND trade_date = '2026-07-31' AND run_slot = '10:30'
), proof AS (
  SELECT 'final_status' AS key, status::text AS value FROM target_job
  UNION ALL SELECT 'final_attempt_count', attempt_count::text FROM target_job
  UNION ALL SELECT 'packet_count', count(*)::text
    FROM sw_market_fact_packet packet JOIN target_job job ON job.id = packet.job_id
  UNION ALL SELECT 'model_run_count', count(*)::text
    FROM sw_model_run model JOIN target_job job ON job.id = model.job_id
  UNION ALL SELECT 'accepted_validation_count', count(*)::text
    FROM sw_model_run model
    JOIN target_job job ON job.id = model.job_id
    JOIN sw_structural_validation validation ON validation.model_run_id = model.id
    WHERE validation.status = 'accepted'
  UNION ALL SELECT 'report_count', count(*)::text
    FROM sw_protection_report report JOIN target_job job ON job.id = report.job_id
  UNION ALL SELECT 'report_uses_accepted_model', bool_and(validation.status = 'accepted')::text
    FROM sw_protection_report report
    JOIN target_job job ON job.id = report.job_id
    JOIN sw_model_run model ON model.id = report.model_run_id
    JOIN sw_structural_validation validation ON validation.id = report.validation_id
)
SELECT key || '=' || value FROM proof ORDER BY key;
SQL

ledger_state=$(curl -fsS http://127.0.0.1:8794/count)
attempts=$(printf '%s' "$ledger_state" | jq -r '.attempts')
echo "fixture_request_count=$attempts" | tee -a "$proof_file"
printf '%s' "$ledger_state" | jq -r '"ledger_entry_count=" + (.entries | length | tostring)' | tee -a "$proof_file"
printf '%s' "$ledger_state" | jq -r '"ledger_failed_pre_submit_count=" + ([.entries[] | select(.status == "FAILED_PRE_SUBMIT")] | length | tostring)' | tee -a "$proof_file"
printf '%s' "$ledger_state" | jq -r '"ledger_success_count=" + ([.entries[] | select(.status == "SUCCESS")] | length | tostring)' | tee -a "$proof_file"
printf '%s' "$ledger_state" | jq -r '"ledger_attempt_keys_distinct=" + (([.entries[].submissionKey] | unique | length) == 2 | tostring)' | tee -a "$proof_file"
printf '%s' "$ledger_state" | jq -r '"ledger_attempt_suffixes_present=" + (([.entries[].submissionKey | endswith(":attempt-1")] | any) and ([.entries[].submissionKey | endswith(":attempt-2")] | any) | tostring)' | tee -a "$proof_file"

grep -q '^final_status=SUCCESS$' "$proof_file"
grep -q '^final_attempt_count=2$' "$proof_file"
grep -q '^packet_count=1$' "$proof_file"
grep -q '^model_run_count=1$' "$proof_file"
grep -q '^accepted_validation_count=1$' "$proof_file"
grep -q '^report_count=1$' "$proof_file"
grep -q '^report_uses_accepted_model=true$' "$proof_file"
grep -q '^fixture_request_count=2$' "$proof_file"
grep -q '^ledger_entry_count=2$' "$proof_file"
grep -q '^ledger_failed_pre_submit_count=1$' "$proof_file"
grep -q '^ledger_success_count=1$' "$proof_file"
grep -q '^ledger_attempt_keys_distinct=true$' "$proof_file"
grep -q '^ledger_attempt_suffixes_present=true$' "$proof_file"

db <<'SQL' >/dev/null
DELETE FROM sw_analysis_job
WHERE symbol = '300750' AND market = 'sz'
  AND trade_date = '2026-07-31' AND run_slot = '11:00';

WITH snapshot AS (
  INSERT INTO sw_quote_snapshot (
    symbol, market, quote_time, fetched_at, provider, source, quality_status,
    price, open, day_high, day_low, previous_close,
    cumulative_volume_raw, cumulative_volume_shares, volume_unit,
    cumulative_amount_raw, amount_unit, limitations, raw_payload
  ) VALUES (
    '300750', 'sz', '2026-07-31 03:00:00+00', '2026-07-31 03:00:05+00',
    'PHASE3_UNKNOWN_QA', 'LOCAL_QA_FIXTURE', 'PARTIAL',
    100.90, 100, 101, 98, 99.5, 190, 19000, 'LOT_100_SHARES',
    1900000, 'CNY', '["LOCAL_QA_FIXTURE"]'::jsonb, '{"fixture":true}'::jsonb
  )
  ON CONFLICT (symbol, market, provider, quote_time) DO UPDATE SET price = EXCLUDED.price
  RETURNING id
)
INSERT INTO sw_bar_1m (
  symbol, market, minute_start, open, high, low, close,
  volume_delta_raw, volume_delta_shares, amount_delta, volume_unit, amount_unit,
  vwap, source, quality_status, limitations, last_snapshot_id
)
SELECT
  '300750', 'sz', '2026-07-31 02:30:00+00'::timestamptz + (n || ' minutes')::interval,
  100.60 + n * 0.01, 100.70 + n * 0.01, 100.50 + n * 0.01, 100.60 + n * 0.01,
  1, 100, 10000, 'LOT_100_SHARES', 'CNY', NULL,
  'LOCAL_UNKNOWN_QA', 'APPROXIMATE', '["LOCAL_QA_FIXTURE"]'::jsonb, snapshot.id
FROM generate_series(1, 30) AS n
CROSS JOIN snapshot
ON CONFLICT (symbol, market, minute_start) DO UPDATE SET
  close = EXCLUDED.close,
  source = EXCLUDED.source,
  last_snapshot_id = EXCLUDED.last_snapshot_id;

UPDATE sw_runtime_control
SET analysis_enabled = true,
    publisher_enabled = false,
    notify_enabled = false,
    model_provider = 'DEEPSEEK_WEB',
    deepseek_base_url = 'http://host.docker.internal:8794/v1',
    manual_test_mode = true,
    manual_test_trade_date = '2026-07-31',
    manual_test_run_slot = '11:00',
    updated_at = now()
WHERE id = 1;
SQL

execute_success SW20HalfHourAnalysis "$ROOT_DIR/runtime/smoke/phase3-unknown-first-$stamp.log"
execute_success SW20HalfHourAnalysis "$ROOT_DIR/runtime/smoke/phase3-unknown-second-$stamp.log"

db_at <<'SQL' | tee -a "$proof_file"
WITH target_job AS (
  SELECT * FROM sw_analysis_job
  WHERE symbol = '300750' AND market = 'sz'
    AND trade_date = '2026-07-31' AND run_slot = '11:00'
)
SELECT 'unknown_status=' || status FROM target_job
UNION ALL SELECT 'unknown_attempt_count=' || attempt_count FROM target_job
UNION ALL SELECT 'unknown_error=' || last_error_code FROM target_job
UNION ALL SELECT 'unknown_retryable=' || (validation.validation->>'retryable')
FROM target_job job
JOIN sw_model_run model ON model.job_id = job.id
JOIN sw_structural_validation validation ON validation.model_run_id = model.id;
SQL

unknown_state=$(curl -fsS http://127.0.0.1:8794/count)
printf '%s' "$unknown_state" | jq -r '"unknown_fixture_request_count=" + (.scenarioCounts.unknownRequests | tostring)' | tee -a "$proof_file"
printf '%s' "$unknown_state" | jq -r '"unknown_provider_invocation_count=" + (.scenarioCounts.unknownProviderInvocations | tostring)' | tee -a "$proof_file"
printf '%s' "$unknown_state" | jq -r '"ledger_unknown_count=" + ([.entries[] | select(.status == "UNKNOWN")] | length | tostring)' | tee -a "$proof_file"

grep -q '^unknown_status=FAILED$' "$proof_file"
grep -q '^unknown_attempt_count=1$' "$proof_file"
grep -q '^unknown_error=MODEL_PROVIDER_OUTCOME_UNKNOWN$' "$proof_file"
grep -q '^unknown_retryable=false$' "$proof_file"
grep -q '^unknown_fixture_request_count=1$' "$proof_file"
grep -q '^unknown_provider_invocation_count=1$' "$proof_file"
grep -q '^ledger_unknown_count=1$' "$proof_file"

cleanup
trap - EXIT INT TERM
echo "phase3 retry-to-success smoke passed; proof: $proof_file"
