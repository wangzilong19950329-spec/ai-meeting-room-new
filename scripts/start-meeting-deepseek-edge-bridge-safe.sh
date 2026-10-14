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

if [[ -z "${DEEPSEEK_OPENAI_FACADE_API_KEY:-}" ]]; then
  echo '[error] DEEPSEEK_OPENAI_FACADE_API_KEY is required' >&2
  exit 78
fi

export DEEPSEEK_WEB_BROWSER_BIN="${DEEPSEEK_WEB_BROWSER_BIN:-/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge}"
if [[ ! -x "$DEEPSEEK_WEB_BROWSER_BIN" ]]; then
  echo '[error] Microsoft Edge executable not found' >&2
  exit 127
fi

export DEEPSEEK_WEB_BRIDGE_PORT="${MEETING_DEEPSEEK_EDGE_BRIDGE_PORT:-8795}"
export DEEPSEEK_WEB_BRIDGE_HOST=127.0.0.1
export DEEPSEEK_WEB_DEBUG_PORT="${MEETING_DEEPSEEK_EDGE_DEBUG_PORT:-9355}"
export DEEPSEEK_WEB_DEFAULT_ACCOUNT_KEY=meeting
export DEEPSEEK_WEB_BROKER_ACCOUNTS=meeting
export DEEPSEEK_WEB_ACCOUNT_MEETING_CAPABILITIES=general.analysis
export DEEPSEEK_WEB_PROFILE_ROOT="${MEETING_DEEPSEEK_EDGE_PROFILE_ROOT:-$HOME/.aimeeting-room/deepseek-web-meeting-edge-profile}"
export DEEPSEEK_WEB_SESSION_STORE="${MEETING_DEEPSEEK_EDGE_SESSION_STORE:-$HOME/.aimeeting-room/deepseek-web-meeting-edge-sessions.json}"
export DEEPSEEK_WEB_CHROME_LOG="${MEETING_DEEPSEEK_EDGE_BROWSER_LOG:-$HOME/.aimeeting-room/deepseek-web-meeting-edge-browser.log}"
export DEEPSEEK_WEB_REQUEST_LEDGER="${MEETING_DEEPSEEK_EDGE_REQUEST_LEDGER:-$HOME/.aimeeting-room/deepseek-web-meeting-edge-requests.json}"
export DEEPSEEK_WEB_BROKER_STATE="${MEETING_DEEPSEEK_EDGE_BROKER_STATE:-$HOME/.aimeeting-room/deepseek-web-meeting-edge-broker-state.json}"
export DEEPSEEK_WEB_PROFILE_BOOTSTRAP=empty

mkdir -p "$DEEPSEEK_WEB_PROFILE_ROOT/Default"
cd "$ROOT_DIR"
exec -a deepseek-web-meeting-edge-bridge "$(command -v node)" scripts/deepseek-web-bridge.js
