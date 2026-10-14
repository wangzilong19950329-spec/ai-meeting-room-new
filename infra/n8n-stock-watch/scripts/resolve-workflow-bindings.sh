#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
mkdir -p "$ROOT_DIR/runtime/workflow-exports"

query_db() {
  compose exec -T postgres sh -lc \
    'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -At -v ON_ERROR_STOP=1'
}

counts=$(query_db <<'SQL'
SELECT name || '=' || count(*)
FROM workflow_entity
WHERE name IN (
  'SW-00 Error Handler',
  'SW-10 Minute Quote Ingest',
  'SW-20 Half-hour Analysis Pipeline',
  'SW-30 Stress Report Publisher'
)
GROUP BY name
ORDER BY name;
SQL
)

for workflow_name in \
  'SW-00 Error Handler' \
  'SW-10 Minute Quote Ingest' \
  'SW-20 Half-hour Analysis Pipeline' \
  'SW-30 Stress Report Publisher'; do
  printf '%s\n' "$counts" | grep -Fqx "$workflow_name=1" || {
    echo "[error] expected exactly one imported workflow named $workflow_name" >&2
    exit 4
  }
done

error_id=$(query_db <<'SQL'
SELECT id FROM workflow_entity WHERE name = 'SW-00 Error Handler';
SQL
)

compose exec -T -e SW_ERROR_WORKFLOW_ID="$error_id" postgres sh -lc \
  'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -At -v ON_ERROR_STOP=1 -v error_id="$SW_ERROR_WORKFLOW_ID"' <<'SQL' >/dev/null
UPDATE workflow_entity
SET settings = jsonb_set(COALESCE(settings::jsonb, '{}'::jsonb), '{errorWorkflow}', to_jsonb(:'error_id'::text), true)
WHERE name IN (
  'SW-10 Minute Quote Ingest',
  'SW-20 Half-hour Analysis Pipeline',
  'SW-30 Stress Report Publisher'
);
SQL

map_file="$ROOT_DIR/runtime/workflow-id-map.txt"
query_db <<'SQL' > "$map_file"
SELECT name || '=' || id || '|active=' || active::text || '|errorWorkflow=' || COALESCE(settings->>'errorWorkflow', '')
FROM workflow_entity
WHERE name IN (
  'SW-00 Error Handler',
  'SW-10 Minute Quote Ingest',
  'SW-20 Half-hour Analysis Pipeline',
  'SW-30 Stress Report Publisher'
)
ORDER BY name;
SQL

grep -Fqx "SW-00 Error Handler=$error_id|active=false|errorWorkflow=" "$map_file"
for workflow_name in \
  'SW-10 Minute Quote Ingest' \
  'SW-20 Half-hour Analysis Pipeline' \
  'SW-30 Stress Report Publisher'; do
  grep -Eq "^${workflow_name}=[^|]+\\|active=false\\|errorWorkflow=${error_id}$" "$map_file"
done

while IFS='=' read -r workflow_name details; do
  workflow_id=${details%%|*}
  export_name=$(printf '%s' "$workflow_name" | tr ' /' '__')
  compose run --rm n8n-import export:workflow \
    --id="$workflow_id" \
    --pretty \
    --output="/runtime/workflow-exports/$export_name.json" </dev/null
done < "$map_file"

echo "verified workflow IDs, inactive state, and Error Workflow bindings: $map_file"
