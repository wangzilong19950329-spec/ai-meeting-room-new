# n8n Stock Watch MVP - Phase1

This directory contains the local Phase1 implementation for a single manual n8n proof-of-concept workflow.

Phase1 scope:

- local Docker Compose stack
- isolated PostgreSQL 16 instance
- n8n fixed to `2.29.10`
- one inactive manual workflow: `SW-01 POC Manual Pipeline`
- MOCK model provider only
- no Java bridge, no DeepSeek, no email, no schedule activation

## Start

```bash
infra/n8n-stock-watch/scripts/bootstrap.sh
infra/n8n-stock-watch/scripts/import-workflows.sh
infra/n8n-stock-watch/scripts/smoke-test-phase1.sh
```

Run the CATL real-quote variant:

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase1-catl-real.sh
```

This fetches `300750.sz` from Eastmoney, renders a temporary runtime workflow,
executes it in n8n, and verifies the persisted report. The position and model
remain Phase1 fixtures. During a closed market session, the quote is labeled
`closed_session_snapshot` rather than fresh intraday data.

The validation workflow uses the separate inactive ID
`SW01Phase1POCCatlReal`, so the default `SW01Phase1POC` definition remains
available. Its MOCK response is derived from the current fact registry and is
rejected if a numeric output disagrees with the referenced fact.

Open n8n at:

```text
http://127.0.0.1:5678
```

## Safety Defaults

- `.env` is generated locally and ignored by Git.
- Runtime credential import JSON is generated under `runtime/credentials/` and removed after import.
- n8n binds only to `127.0.0.1`.
- The workflow is imported with `active=false`.
- `MODEL_PROVIDER=MOCK` and `NOTIFY_ENABLED=false`.
- The real-quote smoke test does not enable DeepSeek, the Java bridge, email, or scheduling.

## Useful Commands

```bash
infra/n8n-stock-watch/scripts/preflight.sh
infra/n8n-stock-watch/scripts/phase1-fixture-tests.js
infra/n8n-stock-watch/scripts/export-workflows.sh
infra/n8n-stock-watch/scripts/run-security-audit.sh
infra/n8n-stock-watch/scripts/verify-recovery-readiness.sh
```

For handoff and day-to-day operation, see `docs/operations-handoff.md`.
