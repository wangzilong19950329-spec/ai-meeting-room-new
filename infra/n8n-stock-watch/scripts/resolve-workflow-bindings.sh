#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
compose run --rm n8n-import export:workflow --id=SW01Phase1POC --pretty --output=/runtime/SW-01-POC-Manual-Pipeline.exported.json
echo "exported imported workflow to $ROOT_DIR/runtime/SW-01-POC-Manual-Pipeline.exported.json"
