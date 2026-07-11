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

export DEEPSEEK_WEB_BRIDGE_PORT="${DEEPSEEK_WEB_BRIDGE_PORT:-8789}"
export DEEPSEEK_WEB_DEBUG_PORT="${DEEPSEEK_WEB_DEBUG_PORT:-9333}"
export DEEPSEEK_WEB_PROFILE_ROOT="${DEEPSEEK_WEB_PROFILE_ROOT:-$HOME/.aimeeting-room/deepseek-web-profile}"
export DEEPSEEK_WEB_CHROME_LOG="${DEEPSEEK_WEB_CHROME_LOG:-$HOME/.aimeeting-room/deepseek-web-chrome.log}"

# 保守节奏，优先降低风控特征。
export DEEPSEEK_WEB_WAIT_TIMEOUT_MS="${DEEPSEEK_WEB_WAIT_TIMEOUT_MS:-150000}"
export DEEPSEEK_WEB_QUICK_RETURN_BUDGET_MS="${DEEPSEEK_WEB_QUICK_RETURN_BUDGET_MS:-18000}"
export DEEPSEEK_WEB_LINK_ONLY_RETURN_BUDGET_MS="${DEEPSEEK_WEB_LINK_ONLY_RETURN_BUDGET_MS:-45000}"
export DEEPSEEK_WEB_PAGE_RESET_SETTLE_MS="${DEEPSEEK_WEB_PAGE_RESET_SETTLE_MS:-6000}"
export DEEPSEEK_WEB_MODE_SETTLE_MS="${DEEPSEEK_WEB_MODE_SETTLE_MS:-3000}"
export DEEPSEEK_WEB_PRE_SUBMIT_DELAY_MS="${DEEPSEEK_WEB_PRE_SUBMIT_DELAY_MS:-6000}"
export DEEPSEEK_WEB_POST_SUBMIT_SETTLE_MS="${DEEPSEEK_WEB_POST_SUBMIT_SETTLE_MS:-4000}"
export DEEPSEEK_WEB_POLL_INTERVAL_MS="${DEEPSEEK_WEB_POLL_INTERVAL_MS:-4500}"
export DEEPSEEK_WEB_HUMAN_JITTER_MS="${DEEPSEEK_WEB_HUMAN_JITTER_MS:-2000}"
export DEEPSEEK_WEB_BRIDGE_QUEUE_TIMEOUT_MS="${DEEPSEEK_WEB_BRIDGE_QUEUE_TIMEOUT_MS:-300000}"
export DEEPSEEK_WEB_MIN_REQUEST_INTERVAL_MS="${DEEPSEEK_WEB_MIN_REQUEST_INTERVAL_MS:-90000}"
export DEEPSEEK_WEB_POST_REQUEST_COOLDOWN_MS="${DEEPSEEK_WEB_POST_REQUEST_COOLDOWN_MS:-30000}"

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

exec "$NODE_BIN" scripts/deepseek-web-bridge.js
