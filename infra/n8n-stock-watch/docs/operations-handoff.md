# Phase1 Operations Handoff

## Current Runtime

- n8n URL: `http://127.0.0.1:5678`
- n8n container: `ai-meeting-room-sw-n8n`
- Postgres container: `ai-meeting-room-sw-postgres`
- workflow: `SW-01 POC Manual Pipeline`
- workflow state: inactive by default

The previous container `ai-meeting-room-n8n-local` was stopped only to free port `5678`; it was not deleted.

## Start

```bash
infra/n8n-stock-watch/scripts/bootstrap.sh
infra/n8n-stock-watch/scripts/import-workflows.sh
```

## Open UI

Open:

```text
http://127.0.0.1:5678
```

Expected UI state:

- Workflows page is accessible.
- `SW-01 POC Manual Pipeline` is visible.
- The workflow remains inactive unless manually executed.

## Smoke Test

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase1.sh
```

Expected proof:

```text
packet_hash=fnv1a32:7f0edac8
report_type=STRESS_ONLY
report_status=generated
validation_status=accepted
has_t1_locked=true
markdown_forbidden_scan=ok
phase1 smoke passed
```

## CATL Real Quote Smoke Test

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase1-catl-real.sh
```

Expected proof during a closed market session:

```text
symbol=300750
market=sz
stock_name=宁德时代
quote_time=<provider quote timestamp>
fetched_at=<local fetch timestamp>
market_session_state=CLOSED
source_host=push2delay.eastmoney.com
position_fixture=position-catl-t1-locked.json
model_provider=MOCK
fact_price_change=<derived percent>
model_price_change=<same derived percent>
price_claim=<direction consistent with the percent sign>
model_fact_consistency=ok
price_claim_direction=ok
source_kind=eastmoney_real
quality_status=closed_session_snapshot
report_type=STRESS_ONLY
report_status=generated
validation_status=accepted
session_quality_consistency=ok
has_t1_locked=true
markdown_forbidden_scan=ok
workflow=SW01Phase1POC|SW-01 POC Manual Pipeline|false
workflow=SW01Phase1POCCatlReal|SW-01 POC CATL Real Quote Validation|false
phase1 CATL real quote smoke passed
```

During an open session, the expected quality value is `fresh_real`. An open-session
quote older than five minutes is rejected. The script uses a CATL-specific T+1
position fixture and keeps `MODEL_PROVIDER=MOCK`; it does not enable DeepSeek,
the Java bridge, email, or scheduling.

The real-quote smoke imports the temporary snapshot as a separate inactive
workflow, `SW-01 POC CATL Real Quote Validation`. It also imports the default
fixture workflow and verifies that both definitions remain inactive and
independently addressable.

## Inspect Workflow Binding

```bash
infra/n8n-stock-watch/scripts/resolve-workflow-bindings.sh
jq -r '.[0].name, .[0].active, .[0].settings.executionOrder' \
  infra/n8n-stock-watch/runtime/SW-01-POC-Manual-Pipeline.exported.json
```

Expected:

```text
SW-01 POC Manual Pipeline
false
v1
```

## Inspect Database

```bash
docker exec ai-meeting-room-sw-postgres sh -lc '
PGPASSWORD="$SW_DB_PASSWORD" psql -U "$SW_DB_USER" -d "$SW_DB_NAME" -F "|" -Atc "
with p as (
  select id, job_id from sw_market_fact_packet where packet_hash='\''fnv1a32:7f0edac8'\''
)
select '\''packet_rows'\'', count(*) from p
union all select '\''model_rows'\'', count(*) from sw_model_run m join p on p.id=m.packet_id
union all select '\''validation_rows'\'', count(*) from sw_structural_validation v join sw_model_run m on m.id=v.model_run_id join p on p.id=m.packet_id
union all select '\''report_rows'\'', count(*) from sw_protection_report r join p on p.id=r.packet_id
union all select '\''job_mismatch_rows'\'', count(*) from p join sw_model_run m on p.id=m.packet_id join sw_protection_report r on p.id=r.packet_id where p.job_id <> m.job_id or p.job_id <> r.job_id;
"
'
```

Expected:

```text
packet_rows|1
model_rows|1
validation_rows|1
report_rows|1
job_mismatch_rows|0
```

## Stop Phase1 Stack

```bash
cd infra/n8n-stock-watch
docker compose --env-file .env -f docker-compose.yml -p ai-meeting-room-n8n-stock-watch stop
```

This keeps local volumes.

## Restart Previous n8n Container

If you want the previous n8n runtime back on port `5678`:

```bash
cd infra/n8n-stock-watch
docker compose --env-file .env -f docker-compose.yml -p ai-meeting-room-n8n-stock-watch stop n8n
docker start ai-meeting-room-n8n-local
```

## Important Migration Rule

Do not auto-format already-applied files under `infra/n8n-stock-watch/sql/migrations/`.

The migration runner intentionally blocks when a migration file checksum changes. Even an extra or missing trailing blank line changes the recorded SHA-256.
