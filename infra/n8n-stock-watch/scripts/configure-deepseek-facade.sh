#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

ensure_local_env
load_env
: "${DEEPSEEK_FACADE_API_KEY:?DEEPSEEK_FACADE_API_KEY is required}"

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

remove_legacy_env() {
  key="$1"
  legacy_value="$2"
  current_value=$(awk -F= -v target="$key" '$1 == target { sub(/^[^=]*=/, ""); print; exit }' "$target_env")
  [ "$current_value" = "$legacy_value" ] || return 0
  tmp=$(mktemp)
  awk -F= -v target="$key" '$1 != target { print }' "$target_env" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$target_env"
}

upsert_env DEEPSEEK_OPENAI_FACADE_API_KEY "$DEEPSEEK_FACADE_API_KEY"
remove_legacy_env DEEPSEEK_BROKER_PROXY_HOST 0.0.0.0
remove_legacy_env DEEPSEEK_BROKER_PROXY_PORT "${DEEPSEEK_FACADE_HOST_PORT:-8793}"
remove_legacy_env DEEPSEEK_BROKER_UPSTREAM http://127.0.0.1:8791

echo "synchronized the authenticated Docker facade token with the local DeepSeek broker"
