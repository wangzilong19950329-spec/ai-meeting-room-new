# Credential Strategy

Phase1 uses credential Path A.

Credential:

- name: `SW PostgreSQL`
- type: `postgres`
- id: `SWPostgresLocal`
- host: `postgres`
- database: `${SW_DB_NAME}`
- user: `${SW_DB_USER}`
- password: `${SW_DB_PASSWORD}`

Safety rules:

- The credential import file is generated only under `runtime/credentials/`.
- `runtime/` is ignored except `README.md` and `.gitkeep`.
- `import-workflows.sh` deletes the generated credential JSON through a shell trap.
- Secrets are not printed by the scripts.
