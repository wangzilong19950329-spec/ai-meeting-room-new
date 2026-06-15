#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8116}"
LOG_FILE="${2:-/tmp/ai-meeting-room-${PORT}.log}"
PREV_LOG_FILE="${LOG_FILE}.prev"

if command -v lsof >/dev/null 2>&1; then
  LISTEN_PID="$(lsof -tiTCP:"${PORT}" -sTCP:LISTEN || true)"
  if [[ -n "${LISTEN_PID}" ]]; then
    kill "${LISTEN_PID}"
    sleep 1
  fi
fi

cd "$ROOT_DIR"
if [[ -f "$LOG_FILE" ]]; then
  mv "$LOG_FILE" "$PREV_LOG_FILE"
fi
: >"$LOG_FILE"

nohup "$ROOT_DIR/scripts/start-local-backend.sh" "$PORT" </dev/null >"$LOG_FILE" 2>&1 &
START_PID=$!

for _ in $(seq 1 30); do
  if command -v lsof >/dev/null 2>&1 && lsof -tiTCP:"${PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "started backend on :${PORT}, pid=${START_PID}, log=${LOG_FILE}, prev_log=${PREV_LOG_FILE}"
    exit 0
  fi
  if ! kill -0 "${START_PID}" 2>/dev/null; then
    echo "backend failed to stay up on :${PORT}, log=${LOG_FILE}" >&2
    tail -n 40 "$LOG_FILE" >&2 || true
    exit 1
  fi
  sleep 1
done

echo "backend launch timed out waiting for :${PORT}, log=${LOG_FILE}" >&2
tail -n 40 "$LOG_FILE" >&2 || true
exit 1
