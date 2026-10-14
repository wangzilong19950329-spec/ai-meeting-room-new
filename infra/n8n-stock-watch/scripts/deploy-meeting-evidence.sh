#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

ensure_local_env
load_env
node "$SCRIPT_DIR/render-general-search-workflow.js" >/dev/null
credential_file=$("$SCRIPT_DIR/create-runtime-credential.sh")
trap 'rm -f "$credential_file"' EXIT INT TERM
compose run --rm n8n-import import:credentials --input=/runtime/credentials/sw-postgres.credentials.json >/dev/null
compose run --rm n8n-import import:workflow \
  --input=/workflows/general/GEN-10-General-Evidence-Search.json --activeState=false >/dev/null
compose run --rm n8n-import publish:workflow --id=GEN10GeneralEvidenceSearch >/dev/null
compose restart n8n >/dev/null
ready=false
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if curl -fsS http://127.0.0.1:${N8N_HOST_PORT:-5678}/healthz >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
[ "$ready" = true ] || { echo '[error] n8n did not become healthy after webhook registration' >&2; exit 1; }
echo "published authenticated GEN-10 meeting webhook"
