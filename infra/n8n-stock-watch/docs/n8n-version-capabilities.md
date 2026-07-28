# n8n Version Capabilities

Verified local container capabilities:

- n8n version: `2.29.10`
- `import:workflow` supports `--input` and `--activeState`
- `export:workflow` supports `--all`, `--id`, `--output`, `--pretty`, and `--separate`
- `import:credentials` supports `--input`
- `execute` supports `--id` and `--rawOutput`
- `audit` is available in the container CLI

Node decisions:

- Manual Trigger: `n8n-nodes-base.manualTrigger`, typeVersion `1`
- Code: `n8n-nodes-base.code`, typeVersion `2`
- Postgres: `n8n-nodes-base.postgres`, typeVersion `2.6`
- Postgres `executeQuery` uses `options.queryReplacement` for query parameters.

Runtime audit boundary:

- `n8n audit` reported that `2.29.10` is outdated and listed newer patch/minor releases.
- This Phase1 delivery keeps the fixed, locally verified version rather than changing the runtime mid-implementation.
- Updating to a newer n8n version should rerun node-source verification, workflow import/export, credential import, and Phase1 smoke.
