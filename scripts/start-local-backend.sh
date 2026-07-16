#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8116}"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

PARENT_HAS_STOCK_WATCH_TECH_BRIDGE_URL="${STOCK_WATCH_TECH_BRIDGE_URL+x}"
PARENT_STOCK_WATCH_TECH_BRIDGE_URL="${STOCK_WATCH_TECH_BRIDGE_URL-}"
PARENT_HAS_STOCK_WATCH_TECH_DEBUG_PORT="${STOCK_WATCH_TECH_DEBUG_PORT+x}"
PARENT_STOCK_WATCH_TECH_DEBUG_PORT="${STOCK_WATCH_TECH_DEBUG_PORT-}"
PARENT_HAS_STOCK_WATCH_AI_RULES_BRIDGE_URL="${STOCK_WATCH_AI_RULES_BRIDGE_URL+x}"
PARENT_STOCK_WATCH_AI_RULES_BRIDGE_URL="${STOCK_WATCH_AI_RULES_BRIDGE_URL-}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

if [[ -n "$PARENT_HAS_STOCK_WATCH_TECH_BRIDGE_URL" ]]; then
  export STOCK_WATCH_TECH_BRIDGE_URL="$PARENT_STOCK_WATCH_TECH_BRIDGE_URL"
fi
if [[ -n "$PARENT_HAS_STOCK_WATCH_TECH_DEBUG_PORT" ]]; then
  export STOCK_WATCH_TECH_DEBUG_PORT="$PARENT_STOCK_WATCH_TECH_DEBUG_PORT"
fi
if [[ -n "$PARENT_HAS_STOCK_WATCH_AI_RULES_BRIDGE_URL" ]]; then
  export STOCK_WATCH_AI_RULES_BRIDGE_URL="$PARENT_STOCK_WATCH_AI_RULES_BRIDGE_URL"
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

if [[ -z "${ZHIPU_API_KEY:-}" ]]; then
  echo "[warn] ZHIPU_API_KEY is empty. zhipu_web_search will be unavailable." >&2
  echo "[warn] Put 'export ZHIPU_API_KEY=...' into $ENV_FILE to keep local restarts stable." >&2
fi

if [[ -n "${AI_MEETING_LOCAL_DB_URL:-}" ]]; then
  echo "[info] local profile DB url: ${AI_MEETING_LOCAL_DB_URL}" >&2
else
  echo "[info] local profile DB url: jdbc:mysql://127.0.0.1:3307/ai_meeting" >&2
fi

cd "$ROOT_DIR"
echo "[info] stock-watch full stack command: scripts/start-stock-watch-stack.sh" >&2
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}"
