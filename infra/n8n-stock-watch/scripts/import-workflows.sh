#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
"$SCRIPT_DIR/render-phase1-workflow.js"
node "$SCRIPT_DIR/render-phase2-workflows.js"

credential_file=$("$SCRIPT_DIR/create-runtime-credential.sh")
trap 'rm -f "$credential_file"' EXIT INT TERM

compose run --rm n8n-import import:credentials --input=/runtime/credentials/sw-postgres.credentials.json
compose run --rm n8n-import import:workflow --input=/workflows/phase1/SW-01-POC-Manual-Pipeline.json --activeState=false
for workflow_file in "$ROOT_DIR"/workflows/phase2/*.json; do
  compose run --rm n8n-import import:workflow \
    --input="/workflows/phase2/$(basename "$workflow_file")" \
    --activeState=false
done

"$SCRIPT_DIR/resolve-workflow-bindings.sh"

echo "imported Phase1 and Phase2 workflows with runtime credentials"
