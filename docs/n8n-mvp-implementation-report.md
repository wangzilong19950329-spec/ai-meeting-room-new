# n8n Stock Watch MVP Phase1 Implementation Report

Status: Phase1 local POC completed on 2026-07-28.

## Scope

- Phase1 local manual POC only.
- One workflow: `SW-01 POC Manual Pipeline`.
- n8n fixed to `2.29.10`.
- Isolated PostgreSQL 16 stack.
- `MODEL_PROVIDER=MOCK`.
- `NOTIFY_ENABLED=false`.
- No Java bridge.
- No DeepSeek.
- No email.
- No active schedule.

## Runtime

- n8n URL: `http://127.0.0.1:5678`
- running containers:
  - `ai-meeting-room-sw-n8n`
  - `ai-meeting-room-sw-postgres`

The previous local container `ai-meeting-room-n8n-local` was stopped to free `127.0.0.1:5678`. It was not deleted and its volume was not removed.

## Implemented Files

- `infra/n8n-stock-watch/docker-compose.yml`
- `infra/n8n-stock-watch/sql/bootstrap/000_create_databases_and_roles.sh`
- `infra/n8n-stock-watch/sql/migrations/V001__schema_migrations.sql`
- `infra/n8n-stock-watch/sql/migrations/V002__core_tables.sql`
- `infra/n8n-stock-watch/sql/migrations/V003__indexes.sql`
- `infra/n8n-stock-watch/sql/migrations/V004__seed_mock_data.sql`
- `infra/n8n-stock-watch/sql/migrations/V005__idempotency_and_migration_contract.sql`
- `infra/n8n-stock-watch/sql/migrations/V006__align_packet_provenance.sql`
- `infra/n8n-stock-watch/sql/migrations/V007__normalize_migration_versions.sql`
- `infra/n8n-stock-watch/sql/migrations/V008__normalize_legacy_migration_version_names.sql`
- `infra/n8n-stock-watch/fixtures/*`
- `infra/n8n-stock-watch/prompts/*`
- `infra/n8n-stock-watch/schemas/*`
- `infra/n8n-stock-watch/scripts/*`
- `infra/n8n-stock-watch/workflows/phase1/SW-01-POC-Manual-Pipeline.json`

## Verification

Fixture validation:

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

n8n smoke proof:

```text
packet_hash=fnv1a32:7f0edac8
report_type=STRESS_ONLY
report_status=generated
validation_status=accepted
has_t1_locked=true
markdown_forbidden_scan=ok
phase1 smoke passed
```

Database proof:

```text
packet_rows=1
model_rows=1
validation_rows=1
report_rows=1
job_mismatch_rows=0
```

Idempotency proof:

- The latest packet hash `fnv1a32:7f0edac8` was executed twice.
- The current packet has one packet row, one model run, one validation row, one protection report, and zero job provenance mismatches.

DB isolation proof:

```text
sw_app connecting to n8n database: permission denied
```

Workflow export proof:

```text
active=false
executionOrder=v1
postgres credential=SW PostgreSQL
```

Migration proof:

- `sw_schema_migrations` contains `version`, `filename`, `sha256`, and `applied_at`.
- Applied migration SHA-256 values are 64 characters.
- Versions are normalized as `V001` through `V008`.
- Each migration file is applied in a single transaction by `apply-migrations.sh`.

## Audit Boundary

n8n audit found expected Phase1 findings:

- inactive workflow credential is unused by active workflows
- Code nodes are classified as risky by n8n audit
- SQL query parameter audit reported a false positive, while exported workflow contains `queryReplacement`
- n8n `2.29.10` is reported outdated by audit metadata

These findings are documented in `infra/n8n-stock-watch/docs/security-audit.md`.

Challenge/Optimize review result:

- Challenge initially returned partial pass and flagged Phase0 documentation, migration metadata, idempotency, and `.env.example` gaps.
- Optimize recommended keeping the single parameterized Postgres payload but fixing packet/job provenance and wiring approximate bars into the packet.
- The implementation was updated for those points before final verification.

## Remaining Boundary

- Phase2 four-workflow MVP is not implemented.
- Real Java API bridge is not implemented.
- Real DeepSeek bridge is not implemented.
- Email notification is not implemented.
- Schedule activation is not enabled.
- Current implementation uses fixture-derived approximate bars only.
