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

LOG_DIR="${AI_MEETING_LOG_DIR:-$HOME/.aimeeting-room/logs}"
RUN_DIR="${AI_MEETING_RUN_DIR:-$HOME/.aimeeting-room/run}"
BACKEND_PORT="${AI_MEETING_BACKEND_PORT:-${BACKEND_PORT:-8127}}"
FRONTEND_PORT="${AI_MEETING_FRONTEND_PORT:-${FRONTEND_PORT:-3009}}"
STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT="${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT:-8791}"
STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT="${STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT:-9344}"
STOCK_WATCH_DEEPSEEK_WEB_SEARCH_URL="${STOCK_WATCH_DEEPSEEK_WEB_SEARCH_URL:-http://127.0.0.1:${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT}/search}"
TECH_DEEPSEEK_WEB_BRIDGE_PORT="${TECH_DEEPSEEK_WEB_BRIDGE_PORT:-8789}"
TECH_DEEPSEEK_WEB_DEBUG_PORT="${TECH_DEEPSEEK_WEB_DEBUG_PORT:-9333}"
TECH_DEEPSEEK_WEB_SEARCH_URL="${TECH_DEEPSEEK_WEB_SEARCH_URL:-http://127.0.0.1:${TECH_DEEPSEEK_WEB_BRIDGE_PORT}/search}"
STACK_WATCH_INTERVAL_SECONDS="${STACK_WATCH_INTERVAL_SECONDS:-30}"
STACK_WAIT_TIMEOUT_SECONDS="${STACK_WAIT_TIMEOUT_SECONDS:-120}"

BRIDGE_HEALTH_URL="http://127.0.0.1:${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT}/health"
TECH_BRIDGE_HEALTH_URL="http://127.0.0.1:${TECH_DEEPSEEK_WEB_BRIDGE_PORT}/health"
BACKEND_BASE_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_BASE_URL="http://127.0.0.1:${FRONTEND_PORT}"

export STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT
export STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT
export TECH_DEEPSEEK_WEB_BRIDGE_PORT
export TECH_DEEPSEEK_WEB_DEBUG_PORT
export STOCK_WATCH_TECH_BRIDGE_URL="$TECH_DEEPSEEK_WEB_SEARCH_URL"
export STOCK_WATCH_AI_RULES_BRIDGE_URL="$STOCK_WATCH_DEEPSEEK_WEB_SEARCH_URL"
export STOCK_WATCH_TECH_DEBUG_PORT="$TECH_DEEPSEEK_WEB_DEBUG_PORT"

mkdir -p "$LOG_DIR" "$RUN_DIR"

WATCH_MODE=false
NODE_BIN="${NODE_BIN:-}"
VITE_BIN="${VITE_BIN:-}"

usage() {
  cat <<USAGE
Usage: scripts/start-stock-watch-stack.sh [--watch]

Starts the local stock-watch development stack:
  - Stock DeepSeek bridge: http://127.0.0.1:${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT}/health
  - Tech DeepSeek bridge : http://127.0.0.1:${TECH_DEEPSEEK_WEB_BRIDGE_PORT}/health
  - Spring Boot backend  : ${BACKEND_BASE_URL}
  - Vite frontend        : ${FRONTEND_BASE_URL}

Options:
  --watch   Keep a small supervisor in the foreground and restart missing
            components. This mode is suitable for launchd.
  -h, --help

Environment overrides:
  AI_MEETING_BACKEND_PORT, AI_MEETING_FRONTEND_PORT,
  STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT, STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT,
  TECH_DEEPSEEK_WEB_BRIDGE_PORT, TECH_DEEPSEEK_WEB_DEBUG_PORT,
  STOCK_WATCH_DEEPSEEK_WEB_PROFILE_ROOT, STOCK_WATCH_DEEPSEEK_WEB_SESSION_STORE,
  STOCK_WATCH_DEEPSEEK_WEB_CHROME_LOG, AI_MEETING_ENV_FILE, AI_MEETING_LOG_DIR,
  AI_MEETING_RUN_DIR, STACK_WATCH_INTERVAL_SECONDS, STACK_WAIT_TIMEOUT_SECONDS.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --watch)
      WATCH_MODE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

timestamp() {
  date +"%Y-%m-%d %H:%M:%S"
}

log() {
  echo "[$(timestamp)] $*"
}

resolve_node_runtime() {
  if [[ -n "$NODE_BIN" && -x "$NODE_BIN" ]]; then
    return 0
  fi
  if command -v node >/dev/null 2>&1; then
    NODE_BIN="$(command -v node)"
  elif [[ -x "/opt/homebrew/bin/node" ]]; then
    NODE_BIN="/opt/homebrew/bin/node"
  elif [[ -x "/usr/local/bin/node" ]]; then
    NODE_BIN="/usr/local/bin/node"
  else
    NODE_BIN="$(find "$HOME/.nvm/versions/node" -path '*/bin/node' -type f 2>/dev/null | sort | tail -n 1 || true)"
  fi
}

resolve_frontend_runtime() {
  resolve_node_runtime
  if [[ -n "$NODE_BIN" && -x "$NODE_BIN" ]]; then
    export PATH="$(dirname "$NODE_BIN"):$PATH"
  fi
  if [[ -n "$VITE_BIN" && -f "$VITE_BIN" ]]; then
    return 0
  fi
  if [[ -f "$ROOT_DIR/frontend/node_modules/vite/bin/vite.js" ]]; then
    VITE_BIN="$ROOT_DIR/frontend/node_modules/vite/bin/vite.js"
  fi
}

port_listening() {
  local port="$1"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

http_responds() {
  local url="$1"
  curl -sS --max-time 2 -o /dev/null "$url" >/dev/null 2>&1
}

bridge_healthy() {
  local body
  body="$(curl -fsS --max-time 2 "$BRIDGE_HEALTH_URL" 2>/dev/null)" || return 1
  printf '%s' "$body" | python3 -c '
import json
import sys

expected = int(sys.argv[1])
try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if payload.get("debugPort") == expected else 1)
' "$STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT"
}

technical_bridge_healthy() {
  local body
  body="$(curl -fsS --max-time 2 "$TECH_BRIDGE_HEALTH_URL" 2>/dev/null)" || return 1
  printf '%s' "$body" | python3 -c '
import json
import sys

expected = int(sys.argv[1])
try:
    payload = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if payload.get("debugPort") == expected else 1)
' "$TECH_DEEPSEEK_WEB_DEBUG_PORT"
}

wait_until() {
  local name="$1"
  local check_cmd="$2"
  local timeout="$3"
  local start now
  start="$(date +%s)"
  while true; do
    if eval "$check_cmd"; then
      log "${name} ready"
      return 0
    fi
    now="$(date +%s)"
    if (( now - start >= timeout )); then
      log "WARN: ${name} not ready after ${timeout}s"
      return 1
    fi
    sleep 2
  done
}

write_pid() {
  local name="$1"
  local pid="$2"
  echo "$pid" > "${RUN_DIR}/${name}.pid"
}

start_bridge() {
  if bridge_healthy; then
    log "Stock DeepSeek bridge already healthy on :${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT} (Chrome debug :${STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT})"
    return 0
  fi
  if port_listening "$STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT"; then
    log "WARN: port ${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT} is listening, but ${BRIDGE_HEALTH_URL} is not healthy for stock debug :${STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT}"
    return 1
  fi

  log "starting Stock DeepSeek bridge on :${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT} (Chrome debug :${STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT})"
  (
    cd "$ROOT_DIR"
    nohup "$ROOT_DIR/scripts/start-stock-watch-deepseek-bridge-safe.sh" >>"${LOG_DIR}/deepseek-web-stock-bridge.log" 2>&1 &
    write_pid "deepseek-web-stock-bridge" "$!"
  )
}

start_technical_bridge() {
  if technical_bridge_healthy; then
    log "Tech DeepSeek bridge already healthy on :${TECH_DEEPSEEK_WEB_BRIDGE_PORT} (Chrome debug :${TECH_DEEPSEEK_WEB_DEBUG_PORT})"
    return 0
  fi
  if port_listening "$TECH_DEEPSEEK_WEB_BRIDGE_PORT"; then
    log "WARN: port ${TECH_DEEPSEEK_WEB_BRIDGE_PORT} is listening, but ${TECH_BRIDGE_HEALTH_URL} is not healthy for tech debug :${TECH_DEEPSEEK_WEB_DEBUG_PORT}"
    return 1
  fi

  log "starting Tech DeepSeek bridge on :${TECH_DEEPSEEK_WEB_BRIDGE_PORT} (Chrome debug :${TECH_DEEPSEEK_WEB_DEBUG_PORT})"
  (
    cd "$ROOT_DIR"
    DEEPSEEK_WEB_BRIDGE_PORT="$TECH_DEEPSEEK_WEB_BRIDGE_PORT" \
      DEEPSEEK_WEB_DEBUG_PORT="$TECH_DEEPSEEK_WEB_DEBUG_PORT" \
      nohup "$ROOT_DIR/scripts/start-deepseek-web-bridge-safe.sh" >>"${LOG_DIR}/deepseek-web-primary-bridge.log" 2>&1 &
    write_pid "deepseek-web-primary-bridge" "$!"
  )
}

start_backend() {
  if port_listening "$BACKEND_PORT"; then
    log "backend already listening on :${BACKEND_PORT}"
    return 0
  fi

  log "starting backend on :${BACKEND_PORT}"
  (
    cd "$ROOT_DIR"
    nohup "$ROOT_DIR/scripts/start-local-backend.sh" "$BACKEND_PORT" >>"${LOG_DIR}/backend.log" 2>&1 &
    write_pid "backend" "$!"
  )
}

start_frontend() {
  if port_listening "$FRONTEND_PORT"; then
    log "frontend already listening on :${FRONTEND_PORT}"
    return 0
  fi

  resolve_frontend_runtime
  if [[ -z "$NODE_BIN" || ! -x "$NODE_BIN" ]]; then
    log "WARN: node runtime not found. Set NODE_BIN=/absolute/path/to/node in ${ENV_FILE}"
    return 1
  fi
  if [[ -z "$VITE_BIN" || ! -f "$VITE_BIN" ]]; then
    log "WARN: Vite runtime not found. Run npm install in ${ROOT_DIR}/frontend or set VITE_BIN=/absolute/path/to/vite.js in ${ENV_FILE}"
    return 1
  fi

  log "starting frontend on :${FRONTEND_PORT}, proxy -> ${BACKEND_BASE_URL} (node=${NODE_BIN})"
  (
    cd "$ROOT_DIR/frontend"
    VITE_API_PROXY_TARGET="${VITE_API_PROXY_TARGET:-${BACKEND_BASE_URL}}" \
      nohup "$NODE_BIN" "$VITE_BIN" --host 127.0.0.1 --port "$FRONTEND_PORT" </dev/null >>"${LOG_DIR}/frontend.log" 2>&1 &
    write_pid "frontend" "$!"
  )
}

ensure_stack_once() {
  start_bridge || true
  start_technical_bridge || true
  start_backend || true
  start_frontend || true

  wait_until "DeepSeek bridge" "bridge_healthy" "$STACK_WAIT_TIMEOUT_SECONDS" || true
  wait_until "Tech DeepSeek bridge" "technical_bridge_healthy" "$STACK_WAIT_TIMEOUT_SECONDS" || true
  wait_until "backend" "http_responds '${BACKEND_BASE_URL}/api/auth/login'" "$STACK_WAIT_TIMEOUT_SECONDS" || true
  wait_until "frontend" "http_responds '${FRONTEND_BASE_URL}/'" "$STACK_WAIT_TIMEOUT_SECONDS" || true

  log "stock-watch stack:"
  log "  frontend: ${FRONTEND_BASE_URL}/stock-watch"
  log "  backend : ${BACKEND_BASE_URL}"
  log "  bridge  : ${BRIDGE_HEALTH_URL}"
  log "  tech    : ${TECH_BRIDGE_HEALTH_URL}"
  log "  logs    : ${LOG_DIR}"
}

cleanup_watch_children() {
  if [[ "$WATCH_MODE" != "true" ]]; then
    return 0
  fi
  log "watch supervisor exiting; child processes started by this supervisor may keep running unless stopped separately"
}

trap cleanup_watch_children EXIT

ensure_stack_once

if [[ "$WATCH_MODE" == "true" ]]; then
  log "watch mode enabled; checking every ${STACK_WATCH_INTERVAL_SECONDS}s"
  while true; do
    sleep "$STACK_WATCH_INTERVAL_SECONDS"
    ensure_stack_once
  done
fi
