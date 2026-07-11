#!/usr/bin/env bash
set -euo pipefail

RUN_DIR="${AI_MEETING_RUN_DIR:-$HOME/.aimeeting-room/run}"

usage() {
  cat <<USAGE
Usage: scripts/stop-stock-watch-stack.sh

Stops processes that were started by scripts/start-stock-watch-stack.sh and
recorded in ${RUN_DIR}. It refuses to kill a PID if the command line no longer
matches the expected component.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

timestamp() {
  date +"%Y-%m-%d %H:%M:%S"
}

log() {
  echo "[$(timestamp)] $*"
}

stop_pid_file() {
  local name="$1"
  local pattern="$2"
  local file="${RUN_DIR}/${name}.pid"
  if [[ ! -f "$file" ]]; then
    log "${name}: no pid file"
    return 0
  fi

  local pid command
  pid="$(tr -cd '0-9' < "$file")"
  if [[ -z "$pid" ]]; then
    log "${name}: empty pid file, removing"
    rm -f "$file"
    return 0
  fi
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    log "${name}: pid ${pid} is not running, removing stale pid file"
    rm -f "$file"
    return 0
  fi

  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ ! "$command" =~ $pattern ]]; then
    log "WARN: ${name}: pid ${pid} command does not match expected pattern; refusing to kill"
    log "      command: ${command}"
    return 1
  fi

  log "stopping ${name} pid=${pid}"
  kill "$pid" >/dev/null 2>&1 || true
  sleep 2
  if kill -0 "$pid" >/dev/null 2>&1; then
    log "${name}: still running, sending TERM again"
    kill "$pid" >/dev/null 2>&1 || true
  fi
  rm -f "$file"
}

stop_pid_file "frontend" "vite|npm run dev"
stop_pid_file "backend" "AiMeetingApplication|spring-boot|start-local-backend"
stop_pid_file "deepseek-web-bridge" "deepseek-web-bridge|start-deepseek-web-bridge-safe"
