#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"
ACTION="${1:-preview}"

case "$ACTION" in
  schema|preview|migrate|bootstrap) ;;
  *)
    echo "usage: $0 [schema|preview|migrate|bootstrap]" >&2
    exit 2
    ;;
esac

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

BACKEND_PORT="${AI_MEETING_BACKEND_PORT:-${BACKEND_PORT:-8127}}"
BRIDGE_PORT="${STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT:-8791}"

if [[ "$ACTION" == "migrate" || "$ACTION" == "bootstrap" ]]; then
  if lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "refusing migration: backend port $BACKEND_PORT is still listening" >&2
    exit 1
  fi
  launch_label="com.aimeeting.manual.backend"
  launch_domain="gui/$(id -u)"
  if launchctl print "${launch_domain}/${launch_label}" >/dev/null 2>&1; then
    disabled_state="$(launchctl print-disabled "$launch_domain" 2>/dev/null || true)"
    if ! printf '%s\n' "$disabled_state" | grep -Eq "\"${launch_label}\" => (disabled|true)"; then
      echo "refusing migration: $launch_label is still loaded and enabled in launchd" >&2
      exit 1
    fi
    echo "migration quiescence: $launch_label remains loaded but is disabled and backend port is idle" >&2
  fi
  health="$(curl -fsS --max-time 5 "http://127.0.0.1:${BRIDGE_PORT}/health")"
  printf '%s' "$health" | python3 -c '
import json, sys
payload = json.load(sys.stdin)
if not payload.get("ok") or payload.get("busy") or payload.get("activeRequest"):
    raise SystemExit("refusing migration: bridge is not idle")
'
fi

cd "$ROOT_DIR"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
exec mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=com.aimeeting.room.StockWatchV6MigrationApplication \
  -Dexec.args="--spring.main.web-application-type=none --stock-watch.technical-analysis.v6-mode=migration --stock-watch.technical-analysis.v6-migration-action=${ACTION} --stock-watch.monitor.enabled=false --stock-watch.technical-analysis.enabled=false --stock-watch.delivery.enabled=false --ai.tracking.refresh.enabled=false"
