#!/usr/bin/env bash
set -euo pipefail

RUN_DIR="${AI_MEETING_RUN_DIR:-$HOME/.aimeeting-room/run}"
INCLUDE_LEGACY_BRIDGE=false

usage() {
  cat <<USAGE
Usage: scripts/stop-stock-watch-stack.sh [--include-legacy-bridge]

Stops processes that were started by scripts/start-stock-watch-stack.sh and
recorded in ${RUN_DIR}. It refuses to kill a PID if the command line no longer
matches the expected component.

By default this only stops the stock-watch dedicated DeepSeek bridge. The old
deepseek-web-bridge.pid may point at the shared meeting/manual bridge and is
only stopped when --include-legacy-bridge is explicitly provided.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --include-legacy-bridge)
      INCLUDE_LEGACY_BRIDGE=true
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

inspect_legacy_bridge_pid_file() {
  local file="${RUN_DIR}/deepseek-web-bridge.pid"
  if [[ ! -f "$file" ]]; then
    log "legacy deepseek-web-bridge: no pid file"
    return 0
  fi

  local pid
  pid="$(tr -cd '0-9' < "$file")"
  if [[ -z "$pid" ]]; then
    log "legacy deepseek-web-bridge: empty pid file, removing"
    rm -f "$file"
    return 0
  fi
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    log "legacy deepseek-web-bridge: pid ${pid} is not running, removing stale pid file"
    rm -f "$file"
    return 0
  fi

  if [[ "$INCLUDE_LEGACY_BRIDGE" == "true" ]]; then
    log "WARN: --include-legacy-bridge requested; this may stop the shared meeting/manual DeepSeek bridge"
    stop_pid_file "deepseek-web-bridge" "deepseek-web-bridge|start-deepseek-web-bridge-safe"
    return $?
  fi

  log "legacy deepseek-web-bridge: pid ${pid} is running; not stopping without --include-legacy-bridge"
}

stop_pid_file "frontend" "vite|npm run dev"
stop_pid_file "backend" "AiMeetingApplication|spring-boot|start-local-backend"
stop_pid_file "deepseek-web-stock-bridge" "deepseek-web-stock-bridge|start-stock-watch-deepseek-bridge-safe"
inspect_legacy_bridge_pid_file
