# Phase1 Test Report

Status: passed locally on 2026-07-28.

## Fixture Tests

Command:

```bash
infra/n8n-stock-watch/scripts/phase1-fixture-tests.js
```

Result:

```text
PASS valid quote passes quality gate
PASS stale quote is rejected
PASS packet hash is stable
PASS valid model output is accepted
PASS invalid JSON is rejected
PASS unknown fact reference is rejected
PASS forbidden action text is rejected
PASS T plus one fixture produces locked state
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
