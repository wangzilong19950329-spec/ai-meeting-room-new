# Phase1 Test Report

Status: fixture and CATL real-quote paths passed locally on 2026-08-01.

## Fixture Tests

Command:

```bash
infra/n8n-stock-watch/scripts/phase1-fixture-tests.js
```

Result:

```text
PASS valid quote passes quality gate
PASS rendered workflow default quote passes quality gate
PASS stale quote is rejected
PASS closed-session real quote retains stale snapshot boundary
PASS open-session stale real quote is rejected
PASS rendered workflow rejects open-session stale real quote
PASS invalid real market session state is rejected
PASS rendered workflow rejects invalid real market session state
PASS packet hash is stable
PASS valid model output is accepted
PASS rendered MOCK derives CATL direction and numeric output from facts
PASS numeric model output inconsistent with fact is rejected
PASS rendered validator rejects numeric output inconsistent with fact
PASS invalid JSON is rejected
PASS unknown fact reference is rejected
PASS forbidden action text is rejected
PASS T plus one fixture produces locked state
PASS position fixture cannot be reused across symbols
PASS rendered workflow rejects cross-symbol position fixture
```

## n8n Smoke

Command:

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase1.sh
```

Result:

```text
packet_hash=fnv1a32:7f0edac8
report_type=STRESS_ONLY
report_status=generated
validation_status=accepted
has_t1_locked=true
markdown_forbidden_scan=ok
phase1 smoke passed
```

## CATL Real Quote n8n Smoke

Command:

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase1-catl-real.sh
```

Observed source evidence on 2026-08-01:

```text
symbol=300750.sz
stock_name=宁德时代
quote_time=2026-07-31T07:34:42.000Z
market_session_state=CLOSED
source_host=push2delay.eastmoney.com
```

The quote time is 2026-07-31 15:34:42 in Asia/Shanghai. The smoke ran on
Saturday, 2026-08-01, so the workflow retained the real source but classified
the data as a closed-session snapshot.

Persisted n8n proof:

```text
symbol=300750
market=sz
stock_name=宁德时代
quote_time=2026-07-31 07:34:42+00
fetched_at=2026-08-01T08:26:04.075Z
market_session_state=CLOSED
source_host=push2delay.eastmoney.com
position_fixture=position-catl-t1-locked.json
model_provider=MOCK
fact_price_change=-1.6373
model_price_change=-1.6373
price_claim=The latest source price is below the previous close.
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

This proves the local Eastmoney-to-n8n-to-PostgreSQL path with a MOCK model and
a CATL-specific position fixture. It does not prove DeepSeek, Java bridge,
email, scheduler, or trading-session freshness.

It also does not satisfy the document's Phase3 Java market-data Gate. Amount
and main-inflow semantics remain explicitly unknown; Java reachability,
delta/reset behavior, and non-interference with the existing Java path remain
future checks.

## Security Audit

Command:

```bash
infra/n8n-stock-watch/scripts/run-security-audit.sh
```

Result on 2026-08-01: the findings matched the existing Phase1 allowlist. The
audit still reports the inactive credential, Code node, and PostgreSQL query
parameter heuristics, plus an available n8n version update. No version upgrade
was performed in this Phase1 change.

## Database Proof

The current packet hash persisted:

```text
packet_rows=1
model_rows=1
validation_rows=1
report_rows=1
job_mismatch_rows=0
```

The latest `packet.model_input` does not contain `stock_name` or `cost_basis`.

## Migration Proof

Repeat migration run returned:

```text
migration already applied: V001__schema_migrations.sql
migration already applied: V002__core_tables.sql
migration already applied: V003__indexes.sql
migration already applied: V004__seed_mock_data.sql
migration already applied: V005__idempotency_and_migration_contract.sql
migration already applied: V006__align_packet_provenance.sql
migration already applied: V007__normalize_migration_versions.sql
migration already applied: V008__normalize_legacy_migration_version_names.sql
migrations are ready
```

Migration metadata has `version`, `filename`, `sha256`, and `applied_at`; each SHA-256 value is 64 characters.
Versions are normalized as `V001` through `V008`.
