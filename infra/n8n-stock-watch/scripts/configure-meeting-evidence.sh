#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

ensure_local_env
load_env
: "${MEETING_EVIDENCE_WEBHOOK_TOKEN:?MEETING_EVIDENCE_WEBHOOK_TOKEN is required}"

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

upsert_env AI_MEETING_REVIEWED_SEARCH_TOKEN "$MEETING_EVIDENCE_WEBHOOK_TOKEN"
upsert_env AI_MEETING_REVIEWED_SEARCH_URL http://127.0.0.1:5678/webhook/meeting-evidence-v1
upsert_env AI_MEETING_REVIEWED_SEARCH_ENABLED true
upsert_env AI_MEETING_DEEPSEEK_WEB_BRIDGE_URL http://127.0.0.1:8795/search
echo "configured authenticated reviewed meeting search in the local Java environment"
