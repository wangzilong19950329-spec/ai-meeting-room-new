#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
fi

MYSQL_DB="${AI_MEETING_MYSQL_DB:-ai_meeting}"
MYSQL_USER="${AI_MEETING_MYSQL_APP_USER:-ai_meeting}"
MYSQL_PASSWORD="${AI_MEETING_MYSQL_APP_PASSWORD:-${AI_MEETING_LOCAL_DB_PASSWORD:-ai_meeting_local_dev_only}}"
MYSQL_HOST="${AI_MEETING_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${AI_MEETING_MYSQL_PORT:-3306}"

if ! command -v brew >/dev/null 2>&1; then
  echo "[error] Homebrew not found" >&2
  exit 1
fi

BREW_PREFIX="$(brew --prefix mysql 2>/dev/null || true)"
if [[ -z "${BREW_PREFIX}" || ! -x "${BREW_PREFIX}/bin/mysql" ]]; then
  echo "[error] mysql is not installed via Homebrew yet" >&2
  echo "[hint] run: brew install mysql" >&2
  exit 1
fi

MYSQL_BIN="${BREW_PREFIX}/bin/mysql"
MYSQLADMIN_BIN="${BREW_PREFIX}/bin/mysqladmin"

echo "[info] starting brew mysql service"
brew services start mysql >/dev/null

echo "[info] waiting for mysql"
for _ in $(seq 1 60); do
  if "${MYSQLADMIN_BIN}" -u root ping >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! "${MYSQLADMIN_BIN}" -u root ping >/dev/null 2>&1; then
  echo "[error] mysql did not become ready" >&2
  exit 1
fi

echo "[info] creating database and app user"
"${MYSQL_BIN}" -u root <<EOF
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DB}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'localhost' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DB}\`.* TO '${MYSQL_USER}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`${MYSQL_DB}\`.* TO '${MYSQL_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF

echo "[info] importing schema and seed data"
"${MYSQL_BIN}" -u root < "${ROOT_DIR}/src/main/resources/sql/init.sql"
"${MYSQL_BIN}" -u root "${MYSQL_DB}" <<'SQL'
CREATE TABLE IF NOT EXISTS ai_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(128),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_ai_user_username ON ai_user(username);

CREATE TABLE IF NOT EXISTS ai_member_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    persona TEXT,
    skills VARCHAR(1024),
    personality VARCHAR(1024),
    model_type VARCHAR(64),
    tags VARCHAR(1024),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modify TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX idx_member_template_gmt_create ON ai_member_template(gmt_create);
SQL
"${MYSQL_BIN}" -u root "${MYSQL_DB}" < "${ROOT_DIR}/src/main/resources/sql/data-local.sql"

mkdir -p "$(dirname "${ENV_FILE}")"
touch "${ENV_FILE}"

python3 - "${ENV_FILE}" "${MYSQL_HOST}" "${MYSQL_PORT}" "${MYSQL_DB}" "${MYSQL_USER}" "${MYSQL_PASSWORD}" <<'PY'
import pathlib
import sys

env_path = pathlib.Path(sys.argv[1])
host, port, db, user, password = sys.argv[2:]
url = f"jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"

lines = env_path.read_text().splitlines() if env_path.exists() else []
replacements = {
    "export AI_MEETING_LOCAL_DB_URL=": f"export AI_MEETING_LOCAL_DB_URL='{url}'",
    "export AI_MEETING_LOCAL_DB_USERNAME=": f"export AI_MEETING_LOCAL_DB_USERNAME='{user}'",
    "export AI_MEETING_LOCAL_DB_PASSWORD=": f"export AI_MEETING_LOCAL_DB_PASSWORD='{password}'",
}
seen = set()
new_lines = []
for line in lines:
    replaced = False
    for prefix, value in replacements.items():
        if line.startswith(prefix):
            new_lines.append(value)
            seen.add(prefix)
            replaced = True
            break
    if not replaced:
        new_lines.append(line)
for prefix, value in replacements.items():
    if prefix not in seen:
        new_lines.append(value)
env_path.write_text("\n".join(new_lines).rstrip() + "\n")
PY

echo
echo "brew mysql ready"
echo "host=${MYSQL_HOST}"
echo "port=${MYSQL_PORT}"
echo "db=${MYSQL_DB}"
echo "user=${MYSQL_USER}"
echo "env_file=${ENV_FILE}"
