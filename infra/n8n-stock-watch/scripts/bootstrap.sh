#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

require_cmd docker

if [ ! -f "$ENV_FILE" ]; then
  umask 077
  sed \
    -e "s/replace-with-local-admin-password/$(random_hex 24)/" \
    -e "s/replace-with-local-n8n-password/$(random_hex 24)/" \
    -e "s/replace-with-local-stock-watch-password/$(random_hex 24)/" \
    -e "s/replace-with-local-jwt-secret/$(random_hex 32)/" \
    -e "s/replace-with-32-plus-random-characters/$(random_hex 32)/" \
    "$ROOT_DIR/.env.example" > "$ENV_FILE"
  echo "created local env: $ENV_FILE"
fi

load_env
port="${N8N_HOST_PORT:-5678}"

if command -v lsof >/dev/null 2>&1; then
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/tmp/n8n-stock-watch-port-listener.txt 2>/dev/null; then
    if docker ps --filter 'name=^/ai-meeting-room-sw-n8n$' --format '{{.Names}}' | grep -qx 'ai-meeting-room-sw-n8n'; then
      echo "127.0.0.1:$port is already served by ai-meeting-room-sw-n8n"
    else
    echo "[error] 127.0.0.1:$port is already in use. Stop the existing listener or set N8N_HOST_PORT in $ENV_FILE." >&2
      cat /tmp/n8n-stock-watch-port-listener.txt >&2 || true
    exit 3
    fi
  fi
fi

"$SCRIPT_DIR/render-phase1-workflow.js"
compose up -d postgres db-bootstrap db-migrate n8n
compose ps

echo "n8n local URL: http://127.0.0.1:${port}"
