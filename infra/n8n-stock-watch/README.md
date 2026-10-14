# n8n Stock Watch MVP

This directory contains the local n8n stock-watch implementation through Phase3.

Implemented:

- PostgreSQL migrations `V001` through `V011`
- inactive `SW-00`, `SW-10`, `SW-20`, and `SW-30` workflows
- token-protected Java quote adapter
- authenticated DeepSeek OpenAI-compatible facade on `127.0.0.1:8793`
- strict, fail-closed model validation and de-identified fact packets
- deterministic stress reports with position, T+1, and risk-budget inputs
- local-only Mailpit delivery with database claims and deduplication
- explicit runtime gates; all workflow, ingest, analysis, publisher, and notification switches default off

## Start

```bash
infra/n8n-stock-watch/scripts/configure-deepseek-facade.sh
scripts/start-stock-watch-deepseek-bridge-safe.sh
```

After the logged-in broker is healthy on `127.0.0.1:8791`, run in another
terminal:

```bash
infra/n8n-stock-watch/scripts/bootstrap.sh
infra/n8n-stock-watch/scripts/configure-java-adapter.sh
infra/n8n-stock-watch/scripts/import-workflows.sh
```

Start the Java adapter in a separate terminal after configuration:

```bash
scripts/start-local-backend.sh 8127
```

`configure-java-adapter.sh` sets `AI_SCHEDULING_ENABLED=false`, so this local
adapter process does not run Spring `@Scheduled` tasks.

Open:

- n8n: `http://127.0.0.1:5678`
- Mailpit: `http://127.0.0.1:8025`
- authenticated DeepSeek facade: `http://127.0.0.1:8793/v1`

## Verify

```bash
infra/n8n-stock-watch/scripts/smoke-test-phase2.sh
infra/n8n-stock-watch/scripts/smoke-test-phase3-retry.sh
```

The smoke tests check Java and DeepSeek authentication, Java stale-data
rejection, MOCK analysis, de-identification, VWAP fail-closed behavior, report
claims, eight stress scenarios, local email deduplication, attempt fencing, and
a real `504 -> RETRY -> accepted -> SW-30` recovery. They restore all runtime
switches to off even when a check fails.

Provider failures enter the bounded retry path only when the upstream response
explicitly proves `retrySafe=true`. Unknown provider outcomes, unproven timeout
safety, authentication, schema, capability, and policy failures remain terminal.
Fact packet identity stays stable across retries, while each provider attempt
uses a distinct submission key so broker ledger tombstones cannot block the
next bounded attempt.

The real DeepSeek canary is intentionally separate from the repeatable smoke.
The validated Phase3 run retained one rejected numeric-policy response and then
accepted prompt v2 as `STRUCTURE_VALID` before producing one local report email.

## Safety Defaults

- `.env` and generated runtime credentials are ignored by Git.
- n8n, Mailpit, and the facade bind only to `127.0.0.1` on the host.
- The facade requires a generated Bearer token from n8n and rejects missing or incorrect tokens.
- The logged-in host broker uses the same token after it is restarted with the current source.
- The Java endpoint requires a separate `X-N8N-Token`.
- All workflows remain `active=false`; schedule triggers have not been activated.
- Notification transport is constrained to Mailpit and recipients ending in `.invalid`.
- No external SMTP message was sent or tested.
- Eastmoney `f5/f6` units remain an empirical A-share contract, so VWAP stays unavailable.

For operational details and known boundaries, see `docs/operations-handoff.md`.
