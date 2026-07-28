#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
"$SCRIPT_DIR/render-phase1-workflow.js"

credential_file=$("$SCRIPT_DIR/create-runtime-credential.sh")
trap 'rm -f "$credential_file"' EXIT INT TERM

compose run --rm n8n-import import:credentials --input=/runtime/credentials/sw-postgres.credentials.json
compose run --rm n8n-import import:workflow --input=/workflows/phase1/SW-01-POC-Manual-Pipeline.json --activeState=false

echo "imported Phase1 workflow and runtime credential"
