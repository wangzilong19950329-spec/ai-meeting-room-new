#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-ai-meeting-room-n8n-stock-watch}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[error] missing command: $1" >&2
    exit 127
  }
}

load_env() {
  if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" -p "$PROJECT_NAME" "$@"
}

random_hex() {
  bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c "$((bytes * 2))"
    printf '\n'
  fi
}

ensure_local_env() {
  if [ ! -f "$ENV_FILE" ]; then
    umask 077
    sed \
      -e "s/replace-with-local-admin-password/$(random_hex 24)/" \
      -e "s/replace-with-local-n8n-password/$(random_hex 24)/" \
      -e "s/replace-with-local-stock-watch-password/$(random_hex 24)/" \
      -e "s/replace-with-local-jwt-secret/$(random_hex 32)/" \
      -e "s/replace-with-32-plus-random-characters/$(random_hex 32)/" \
      "$ROOT_DIR/.env.example" > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    echo "created local env: $ENV_FILE"
  fi

  if ! grep -q '^JAVA_API_TOKEN=.' "$ENV_FILE"; then
    java_token=$(random_hex 32)
    tmp_env=$(mktemp)
    awk -F= '$1 != "JAVA_API_TOKEN" { print }' "$ENV_FILE" > "$tmp_env"
    printf 'JAVA_API_TOKEN=%s\n' "$java_token" >> "$tmp_env"
    chmod 600 "$tmp_env"
    mv "$tmp_env" "$ENV_FILE"
    unset java_token
    echo "created dedicated Java adapter token in local n8n env"
  fi

  if ! grep -q '^DEEPSEEK_FACADE_API_KEY=.' "$ENV_FILE"; then
    deepseek_token=$(random_hex 32)
    tmp_env=$(mktemp)
    awk -F= '$1 != "DEEPSEEK_FACADE_API_KEY" && $1 != "DEEPSEEK_API_KEY" { print }' "$ENV_FILE" > "$tmp_env"
    printf 'DEEPSEEK_FACADE_API_KEY=%s\n' "$deepseek_token" >> "$tmp_env"
    chmod 600 "$tmp_env"
    mv "$tmp_env" "$ENV_FILE"
    unset deepseek_token
    echo "created dedicated DeepSeek facade token in local n8n env"
  fi

  if ! grep -q '^MEETING_EVIDENCE_WEBHOOK_TOKEN=.' "$ENV_FILE"; then
    meeting_evidence_token=$(random_hex 32)
    tmp_env=$(mktemp)
    awk -F= '$1 != "MEETING_EVIDENCE_WEBHOOK_TOKEN" { print }' "$ENV_FILE" > "$tmp_env"
    printf 'MEETING_EVIDENCE_WEBHOOK_TOKEN=%s\n' "$meeting_evidence_token" >> "$tmp_env"
    chmod 600 "$tmp_env"
    mv "$tmp_env" "$ENV_FILE"
    unset meeting_evidence_token
    echo "created dedicated meeting evidence webhook token in local n8n env"
  fi
}

sql_literal() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/''/g")"
}
