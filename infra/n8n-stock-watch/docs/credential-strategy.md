# Credential Strategy

Credentials are generated from the ignored local `.env`, imported into n8n,
and then removed from `runtime/credentials/`.

Credentials:

- `SW PostgreSQL` / `SWPostgresLocal`: PostgreSQL application role
- `SW Java Adapter Header` / `SWJavaAdapterHeaderAuth`: dedicated `X-N8N-Token`
- `SW DeepSeek Facade Header` / `SWDeepSeekFacadeHeaderAuth`: dedicated Bearer token
- `SW Mailpit Local` / `SWMailpitLocal`: unauthenticated SMTP only inside the local Compose network

Safety rules:

- The credential import file is generated only under `runtime/credentials/`.
- `runtime/` is ignored except `README.md` and `.gitkeep`.
- `import-workflows.sh` deletes the generated credential JSON through a shell trap.
- Secrets are not printed by the scripts.
- Java and DeepSeek do not share a token.
- No production SMTP credential is imported.
