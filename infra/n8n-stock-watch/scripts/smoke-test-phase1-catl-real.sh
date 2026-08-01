#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env

quote_file="$ROOT_DIR/runtime/quotes/quote-real-300750-sz.json"
workflow_file="$ROOT_DIR/runtime/workflows/SW-01-POC-Manual-Pipeline.real-catl.json"
workflow_id="SW01Phase1POCCatlReal"
workflow_name="SW-01 POC CATL Real Quote Validation"

"$SCRIPT_DIR/fetch-eastmoney-quote.js" 300750 sz "$quote_file"
PHASE1_QUOTE_FIXTURE_PATH="$quote_file" \
PHASE1_POSITION_FIXTURE_PATH="fixtures/position-catl-t1-locked.json" \
PHASE1_WORKFLOW_OUTPUT="$workflow_file" \
PHASE1_WORKFLOW_ID="$workflow_id" \
PHASE1_WORKFLOW_NAME="$workflow_name" \
  "$SCRIPT_DIR/render-phase1-workflow.js"
expected_fetched_at=$(node -e "console.log(require(process.argv[1]).fetched_at)" "$quote_file")

"$SCRIPT_DIR/bootstrap.sh"

credential_file=$("$SCRIPT_DIR/create-runtime-credential.sh")
trap 'rm -f "$credential_file"' EXIT INT TERM

compose run --rm n8n-import import:credentials --input=/runtime/credentials/sw-postgres.credentials.json
compose run --rm n8n-import import:workflow \
  --input=/workflows/phase1/SW-01-POC-Manual-Pipeline.json \
  --activeState=false
compose run --rm n8n-import import:workflow \
  --input=/runtime/workflows/SW-01-POC-Manual-Pipeline.real-catl.json \
  --activeState=false

mkdir -p "$ROOT_DIR/runtime/smoke"
stamp=$(date +%Y%m%d-%H%M%S)
execute_log="$ROOT_DIR/runtime/smoke/phase1-catl-execute-$stamp.log"
db_proof="$ROOT_DIR/runtime/smoke/phase1-catl-db-proof-$stamp.txt"

if compose run --rm n8n-import execute --id="$workflow_id" --rawOutput > "$execute_log" 2>&1; then
  echo "workflow execution log: $execute_log"
else
  echo "[error] CATL workflow execution failed; tail follows" >&2
  tail -n 220 "$execute_log" >&2 || true
  exit 10
fi

compose exec -T -e EXPECTED_FETCHED_AT="$expected_fetched_at" postgres sh -lc \
  'PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -v ON_ERROR_STOP=1 -v expected_fetched_at="$EXPECTED_FETCHED_AT"' <<'SQL' | tee "$db_proof"
WITH latest AS (
  SELECT
    report.report_type,
    report.status,
    report.risk_items,
    report.markdown,
    validation.status AS validation_status,
    packet.source_kind,
    packet.quality_status,
    packet.symbol,
    packet.market,
    packet.quote_time::text AS quote_time,
    packet.packet->'source_metadata'->>'stock_name' AS stock_name,
    packet.packet->'source_metadata'->>'fetched_at' AS fetched_at,
    packet.packet->'source_metadata'->>'market_session_state' AS market_session_state,
    split_part(
      split_part(packet.packet->'source_metadata'->>'source_url', '://', 2),
      '/',
      1
    ) AS source_host,
    packet.fact_registry->'fact.position_total_quantity'->>'source' AS position_fixture,
    packet.fact_registry->'fact.price_change_pct'->>'value' AS fact_price_change,
    model.raw_response->'numeric_outputs'->0->>'value' AS model_price_change,
    model.raw_response->'claim_set'->0->>'statement' AS price_claim,
    model.provider AS model_provider
  FROM sw_protection_report report
  JOIN sw_market_fact_packet packet ON packet.id = report.packet_id
  JOIN sw_model_run model ON model.id = report.model_run_id
  JOIN sw_structural_validation validation ON validation.id = report.validation_id
  WHERE packet.symbol = '300750'
    AND packet.market = 'sz'
    AND packet.source_kind = 'eastmoney_real'
    AND packet.packet->'source_metadata'->>'fetched_at' = :'expected_fetched_at'
  ORDER BY report.created_at DESC
  LIMIT 1
),
proof AS (
  SELECT 'symbol' AS key, symbol AS value FROM latest
  UNION ALL
  SELECT 'market', market FROM latest
  UNION ALL
  SELECT 'stock_name', stock_name FROM latest
  UNION ALL
  SELECT 'quote_time', quote_time FROM latest
  UNION ALL
  SELECT 'fetched_at', fetched_at FROM latest
  UNION ALL
  SELECT 'market_session_state', market_session_state FROM latest
  UNION ALL
  SELECT 'source_host', source_host FROM latest
  UNION ALL
  SELECT 'position_fixture', position_fixture FROM latest
  UNION ALL
  SELECT 'model_provider', model_provider FROM latest
  UNION ALL
  SELECT 'fact_price_change', fact_price_change FROM latest
  UNION ALL
  SELECT 'model_price_change', model_price_change FROM latest
  UNION ALL
  SELECT 'price_claim', price_claim FROM latest
  UNION ALL
  SELECT
    'model_fact_consistency',
    CASE
      WHEN model_price_change::numeric = fact_price_change::numeric THEN 'ok'
      ELSE 'fail'
    END
  FROM latest
  UNION ALL
  SELECT
    'price_claim_direction',
    CASE
      WHEN fact_price_change::numeric > 0 AND price_claim LIKE '% above %' THEN 'ok'
      WHEN fact_price_change::numeric < 0 AND price_claim LIKE '% below %' THEN 'ok'
      WHEN fact_price_change::numeric = 0 AND price_claim LIKE '% equal to %' THEN 'ok'
      ELSE 'fail'
    END
  FROM latest
  UNION ALL
  SELECT 'source_kind', source_kind FROM latest
  UNION ALL
  SELECT 'quality_status', quality_status FROM latest
  UNION ALL
  SELECT 'report_type', report_type FROM latest
  UNION ALL
  SELECT 'report_status', status FROM latest
  UNION ALL
  SELECT 'validation_status', validation_status FROM latest
  UNION ALL
  SELECT
    'session_quality_consistency',
    CASE
      WHEN quality_status = 'fresh_real' AND market_session_state = 'OPEN' THEN 'ok'
      WHEN quality_status = 'closed_session_snapshot' AND market_session_state IN ('MIDDAY_BREAK', 'CLOSED') THEN 'ok'
      ELSE 'fail'
    END
  FROM latest
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

compose exec -T postgres sh -lc \
  'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -v ON_ERROR_STOP=1' <<'SQL' | tee -a "$db_proof"
SELECT 'workflow=' || id || '|' || name || '|' || active::text
FROM workflow_entity
WHERE id IN ('SW01Phase1POC', 'SW01Phase1POCCatlReal')
ORDER BY id;
SQL

grep -q 'symbol=300750' "$db_proof"
grep -q 'market=sz' "$db_proof"
grep -q 'stock_name=宁德时代' "$db_proof"
grep -q 'quote_time=20' "$db_proof"
grep -Fq "fetched_at=$expected_fetched_at" "$db_proof"
grep -Eq 'market_session_state=(OPEN|MIDDAY_BREAK|CLOSED)' "$db_proof"
grep -Eq 'source_host=(push2delay|push2)\.eastmoney\.com' "$db_proof"
grep -q 'position_fixture=position-catl-t1-locked.json' "$db_proof"
grep -q 'model_provider=MOCK' "$db_proof"
grep -q 'model_fact_consistency=ok' "$db_proof"
grep -q 'price_claim_direction=ok' "$db_proof"
grep -q 'source_kind=eastmoney_real' "$db_proof"
grep -Eq 'quality_status=(fresh_real|closed_session_snapshot)' "$db_proof"
grep -q 'report_type=STRESS_ONLY' "$db_proof"
grep -q 'validation_status=accepted' "$db_proof"
grep -q 'session_quality_consistency=ok' "$db_proof"
grep -q 'has_t1_locked=true' "$db_proof"
grep -q 'markdown_forbidden_scan=ok' "$db_proof"
grep -q 'workflow=SW01Phase1POC|SW-01 POC Manual Pipeline|false' "$db_proof"
grep -q 'workflow=SW01Phase1POCCatlReal|SW-01 POC CATL Real Quote Validation|false' "$db_proof"

echo "phase1 CATL real quote smoke passed"
