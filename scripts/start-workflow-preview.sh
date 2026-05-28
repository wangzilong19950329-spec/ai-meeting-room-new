#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_PORT="${1:-8126}"
FRONTEND_PORT="${2:-3002}"
BACKEND_LOG="${AI_MEETING_PREVIEW_BACKEND_LOG:-/tmp/ai-meeting-room-${BACKEND_PORT}.log}"
FRONTEND_LOG="${AI_MEETING_PREVIEW_FRONTEND_LOG:-/tmp/ai-meeting-room-${FRONTEND_PORT}.log}"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

restart_port() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    local pid
    pid="$(lsof -tiTCP:${port} -sTCP:LISTEN || true)"
    if [[ -n "$pid" ]]; then
      kill "$pid" || true
      sleep 1
    fi
  fi
}

start_detached() {
  local log_file="$1"
  shift
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$@" </dev/null >>"$log_file" 2>&1 &
  else
    nohup "$@" </dev/null >>"$log_file" 2>&1 &
  fi
}

wait_port() {
  local port="$1"
  for _ in $(seq 1 40); do
    if command -v lsof >/dev/null 2>&1 && lsof -tiTCP:${port} -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

restart_port "$BACKEND_PORT"
restart_port "$FRONTEND_PORT"

cd "$ROOT_DIR"
: >"$BACKEND_LOG"
start_detached "$BACKEND_LOG" env \
  SPRING_PROFILES_ACTIVE=local \
  mvn -q spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}"

if ! wait_port "$BACKEND_PORT"; then
  echo "preview backend failed on :${BACKEND_PORT}, log=${BACKEND_LOG}" >&2
  tail -n 60 "$BACKEND_LOG" >&2 || true
  exit 1
fi

cd "$ROOT_DIR/frontend"
: >"$FRONTEND_LOG"
start_detached "$FRONTEND_LOG" env VITE_API_PROXY_TARGET="http://127.0.0.1:${BACKEND_PORT}" npm run dev -- --host 127.0.0.1 --port "${FRONTEND_PORT}"

if ! wait_port "$FRONTEND_PORT"; then
  echo "preview frontend failed on :${FRONTEND_PORT}, log=${FRONTEND_LOG}" >&2
  tail -n 60 "$FRONTEND_LOG" >&2 || true
  exit 1
fi

echo "workflow preview ready"
echo "frontend=http://127.0.0.1:${FRONTEND_PORT}/"
echo "backend=http://127.0.0.1:${BACKEND_PORT}"
echo "db=mysql(local profile)"
echo "backend_log=${BACKEND_LOG}"
echo "frontend_log=${FRONTEND_LOG}"
