#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

mkdir -p "$HOME/.aimeeting-room"

export DEEPSEEK_WEB_BRIDGE_PORT="${DEEPSEEK_OPENAI_ADAPTER_MOCK_PORT:-18789}"
export DEEPSEEK_WEB_BRIDGE_HOST="${DEEPSEEK_OPENAI_ADAPTER_MOCK_HOST:-127.0.0.1}"
export DEEPSEEK_OPENAI_ADAPTER_MOCK=1
export DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT="${DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT:-deepseek-openai-adapter mock response}"

cd "$ROOT_DIR"
NODE_BIN="${NODE_BIN:-}"
if [[ -z "$NODE_BIN" ]]; then
  if command -v node >/dev/null 2>&1; then
    NODE_BIN="$(command -v node)"
  elif [[ -x "/opt/homebrew/bin/node" ]]; then
    NODE_BIN="/opt/homebrew/bin/node"
  elif [[ -x "/usr/local/bin/node" ]]; then
    NODE_BIN="/usr/local/bin/node"
  else
    NODE_BIN="$(find "$HOME/.nvm/versions/node" -path '*/bin/node' -type f 2>/dev/null | sort | tail -n 1 || true)"
  fi
fi

if [[ -z "$NODE_BIN" || ! -x "$NODE_BIN" ]]; then
  echo "[error] node runtime not found. Set NODE_BIN=/absolute/path/to/node in $ENV_FILE" >&2
  exit 127
fi

echo "[adapter-mock] Listen: ${DEEPSEEK_WEB_BRIDGE_HOST}:${DEEPSEEK_WEB_BRIDGE_PORT}" >&2
echo "[adapter-mock] Host Base URL: http://127.0.0.1:${DEEPSEEK_WEB_BRIDGE_PORT}/v1" >&2
echo "[adapter-mock] Docker Base URL: http://host.docker.internal:${DEEPSEEK_WEB_BRIDGE_PORT}/v1" >&2
echo "[adapter-mock] Mock text: ${DEEPSEEK_OPENAI_ADAPTER_MOCK_TEXT}" >&2
exec -a deepseek-openai-adapter-mock "$NODE_BIN" scripts/deepseek-openai-adapter-mock-server.js
