# Security Audit

Phase1 static controls:

- n8n binds to `127.0.0.1`.
- workflow is imported with `active=false`.
- `MODEL_PROVIDER=MOCK`.
- `NOTIFY_ENABLED=false`.
- `.env` and `runtime/` secret files are ignored by Git.
- runtime credential import file is deleted after import.
- no webhook trigger is created.
- no email credential or SMTP node is created.
- no Java bridge or DeepSeek credential is created.

Runtime audit output is generated under `runtime/audit/` by:

```bash
infra/n8n-stock-watch/scripts/run-security-audit.sh
```

## Runtime Audit Result

Executed locally on 2026-07-28 against n8n `2.29.10`.

n8n audit sections found:

- Credentials not used in any active workflow
- Unused "Query Parameters" fields in SQL nodes
- Official risky nodes
- Outdated instance
- Security settings

Disposition:

- Credential finding is expected for Phase1 because the workflow is intentionally inactive.
- SQL query-parameter finding is a false positive for this workflow export: `Persist Phase1 Records` contains `options.queryReplacement = "={{ JSON.stringify($json.persist_payload) }}"` and runtime smoke succeeded through parameterized `$1::jsonb`.
- Code node finding is accepted as a Phase1 local POC risk. The code is generated from repo-local fixtures and performs no network calls.
- Outdated instance remains a Phase1 boundary. The stack is fixed to `2.29.10`; audit reported newer versions. A `2.31.7` pull attempt was started but aborted due download time.
- n8n logs still include a Python runner warning even though this workflow uses only JavaScript Code nodes. Phase1 smoke passed after container recreation.
- No webhook trigger, email node, schedule activation, Java bridge, or DeepSeek credential was created.

`run-security-audit.sh` now allowlists the accepted Phase1 audit finding titles and exits non-zero on new titles.
