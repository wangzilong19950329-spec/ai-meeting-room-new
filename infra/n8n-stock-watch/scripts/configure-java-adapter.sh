#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

ensure_local_env
load_env
: "${JAVA_API_TOKEN:?JAVA_API_TOKEN is required}"

target_env="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"
mkdir -p "$(dirname "$target_env")"
touch "$target_env"
chmod 600 "$target_env"

upsert_env() {
  key="$1"
  value="$2"
  tmp=$(mktemp)
  awk -F= -v target="$key" '$1 != target { print }' "$target_env" > "$tmp"
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$target_env"
}

upsert_env STOCK_WATCH_N8N_ADAPTER_ENABLED true
upsert_env STOCK_WATCH_N8N_API_TOKEN "$JAVA_API_TOKEN"
upsert_env STOCK_WATCH_N8N_MAX_QUOTE_AGE_SECONDS 180
upsert_env STOCK_WATCH_N8N_FUTURE_TOLERANCE_SECONDS 5
upsert_env AI_SCHEDULING_ENABLED false
upsert_env STOCK_WATCH_PRICE_FAILURE_RESEARCH_ENABLED false

echo "configured the token-protected Java adapter with all Spring schedulers disabled"
