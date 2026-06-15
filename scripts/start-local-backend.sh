#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${1:-8116}"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
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
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}"
