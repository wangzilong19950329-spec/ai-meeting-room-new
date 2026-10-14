#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

require_cmd docker

ensure_local_env

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
node "$SCRIPT_DIR/render-phase2-workflows.js"
compose up -d postgres db-bootstrap db-migrate mailpit n8n
compose ps

echo "n8n local URL: http://127.0.0.1:${port}"
echo "Mailpit local inbox: http://127.0.0.1:${MAILPIT_UI_PORT:-8025}"
