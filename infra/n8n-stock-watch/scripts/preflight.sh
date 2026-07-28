#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

require_cmd docker
require_cmd git

echo "cwd: $(pwd)"
echo "git branch: $(git branch --show-current)"
echo "git head: $(git rev-parse HEAD)"
echo "git status:"
git status --short
echo

echo "docker:"
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
docker compose version
echo

if [ -f "$ENV_FILE" ]; then
  load_env
else
  echo "env: missing $ENV_FILE"
fi

port="${N8N_HOST_PORT:-5678}"
if command -v lsof >/dev/null 2>&1; then
  echo "port $port listeners:"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN || true
else
  echo "lsof not available; skipping port listener check"
fi
