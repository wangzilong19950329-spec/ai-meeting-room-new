#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
mkdir -p "$ROOT_DIR/runtime/exported-workflows"
compose run --rm n8n-import export:workflow --all --pretty --separate --output=/runtime/exported-workflows
echo "exported workflows to $ROOT_DIR/runtime/exported-workflows"
