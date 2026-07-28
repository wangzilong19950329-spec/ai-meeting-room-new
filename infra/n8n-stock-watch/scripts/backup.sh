#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
mkdir -p "$ROOT_DIR/runtime/backups"
stamp=$(date +%Y%m%d-%H%M%S)
compose exec -T postgres sh -lc 'PGPASSWORD="$SW_DB_PASSWORD" pg_dump -U "$SW_DB_USER" -d "$SW_DB_NAME" --schema=public' > "$ROOT_DIR/runtime/backups/stock-watch-mvp-$stamp.sql"
compose run --rm n8n-import export:workflow --all --pretty --separate --output=/runtime/backups/workflows-$stamp
echo "backup written under $ROOT_DIR/runtime/backups"
