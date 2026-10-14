#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
: "${SW_DB_NAME:?SW_DB_NAME is required}"
: "${SW_DB_USER:?SW_DB_USER is required}"
: "${SW_DB_PASSWORD:?SW_DB_PASSWORD is required}"
: "${JAVA_API_TOKEN:?JAVA_API_TOKEN is required for Phase2 Java adapter credential}"
: "${DEEPSEEK_FACADE_API_KEY:?DEEPSEEK_FACADE_API_KEY is required for Phase3 DeepSeek credential}"
: "${MEETING_EVIDENCE_WEBHOOK_TOKEN:?MEETING_EVIDENCE_WEBHOOK_TOKEN is required for meeting evidence webhook}"

mkdir -p "$ROOT_DIR/runtime/credentials"
credential_file="$ROOT_DIR/runtime/credentials/sw-postgres.credentials.json"
umask 077
node > "$credential_file" <<'NODE'
const credentials = [
  {
    id: 'SWPostgresLocal',
    name: 'SW PostgreSQL',
    type: 'postgres',
    data: {
      host: 'postgres',
      database: process.env.SW_DB_NAME,
      user: process.env.SW_DB_USER,
      password: process.env.SW_DB_PASSWORD,
      port: 5432,
      ssl: 'disable',
      allowUnauthorizedCerts: false
    }
  },
  {
    id: 'SWJavaAdapterHeaderAuth',
    name: 'SW Java Adapter Header',
    type: 'httpHeaderAuth',
    data: {
      name: 'X-N8N-Token',
      value: process.env.JAVA_API_TOKEN
    }
  },
  {
    id: 'SWDeepSeekFacadeHeaderAuth',
    name: 'SW DeepSeek Facade Header',
    type: 'httpHeaderAuth',
    data: {
      name: 'Authorization',
      value: `Bearer ${process.env.DEEPSEEK_FACADE_API_KEY}`
    }
  },
  {
    id: 'MeetingEvidenceWebhookHeaderAuth',
    name: 'Meeting Evidence Webhook Header',
    type: 'httpHeaderAuth',
    data: {
      name: 'X-Meeting-Evidence-Token',
      value: process.env.MEETING_EVIDENCE_WEBHOOK_TOKEN
    }
  },
  {
    id: 'SWMailpitLocal',
    name: 'SW Mailpit Local',
    type: 'smtp',
    data: {
      user: '',
      password: '',
      host: 'mailpit',
      port: 1025,
      secure: false,
      disableStartTls: true,
      hostName: 'n8n.local'
    }
  }
];
process.stdout.write(`${JSON.stringify(credentials, null, 2)}\n`);
NODE
printf '%s\n' "$credential_file"
