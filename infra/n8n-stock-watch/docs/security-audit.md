# Security Audit

Phase3 static controls:

- n8n binds to `127.0.0.1`.
- all imported workflows have `active=false`.
- runtime ingest, analysis, publisher, notification, and manual QA Gates default off.
- `.env` and `runtime/` secret files are ignored by Git.
- runtime credential import file is deleted after import.
- no webhook trigger is created.
- Java and DeepSeek use separate generated credentials.
- DeepSeek facade rejects missing and incorrect Bearer tokens on `/v1/*` and `/search`.
- the only SMTP credential targets Compose-local Mailpit; recipients must end in `.invalid`.
- Java adapter-only startup disables all Spring schedulers.

Runtime audit output is generated under `runtime/audit/` by:

```bash
infra/n8n-stock-watch/scripts/run-security-audit.sh
```

## Runtime Audit Result

Executed locally on 2026-08-01 against n8n `2.29.10` after the Phase3 workflows,
credentials, and authenticated DeepSeek facade were installed.

n8n audit sections found:

- Credentials not used in any active workflow
- Unused "Query Parameters" fields in SQL nodes
- Official risky nodes
- Outdated instance
- Security settings

Disposition:

- Credential finding is expected because all four workflows remain intentionally inactive.
- SQL query-parameter findings are false positives for the generated PostgreSQL nodes: runtime values use `queryReplacement` and positional `$1::jsonb` parameters. The repeatable Phase3 smoke completed the ingest, analysis, report-claim, and delivery-claim writes.
- Official risky-node findings cover JavaScript Code nodes used for deterministic normalization, validation, and stress calculations. External calls remain in explicit HTTP Request and SMTP nodes, and all workflows are inactive by default.
- Outdated instance remains a Phase3 boundary. The stack is fixed to `2.29.10`; upgrading n8n is outside this phase and requires a separate compatibility pass.
- n8n logs may include a Python runner warning even though these workflows use only JavaScript Code nodes. The Phase3 smoke passed after container recreation.
- No webhook trigger, schedule activation, external SMTP credential, or external email was created.
- The authenticated Compose facade is the n8n security boundary. The existing
  loopback-only host broker on port `8791` still needs a user-session restart to
  load the same all-route authentication rule.

`run-security-audit.sh` allowlists the accepted local audit finding titles and exits non-zero on new titles.
