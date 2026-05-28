#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MYSQL_ENV_FILE="${AI_MEETING_ENV_FILE:-$HOME/.aimeeting-room/local.env}"

if [[ -f "$MYSQL_ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$MYSQL_ENV_FILE"
  set +a
fi

MYSQL_HOME="${AI_MEETING_MYSQL_HOME:-$HOME/.aimeeting-room/mysql}"
MYSQL_SRC="${MYSQL_HOME}/src"
MYSQL_PORT="${AI_MEETING_MYSQL_PORT:-3307}"
MYSQL_USER="${AI_MEETING_MYSQL_APP_USER:-ai_meeting}"
MYSQL_PASSWORD="${AI_MEETING_MYSQL_APP_PASSWORD:-${AI_MEETING_LOCAL_DB_PASSWORD:-ai_meeting_local_dev_only}}"
MYSQL_DB="${AI_MEETING_MYSQL_DB:-ai_meeting}"
MYSQL_SOCKET="${MYSQL_HOME}/run/mysql.sock"
MYSQL_PID="${MYSQL_HOME}/run/mysql.pid"
MYSQL_LOG="${MYSQL_HOME}/log/mysqld.log"
MYSQL_DATA="${MYSQL_HOME}/data"
MYSQL_DIST_DIR="${MYSQL_HOME}/dist"
MYSQL_VERSION="${AI_MEETING_MYSQL_VERSION:-8.4.9}"
MYSQL_ARCHIVE="mysql-${MYSQL_VERSION}-macos15-x86_64.tar.gz"
MYSQL_URL="${AI_MEETING_MYSQL_URL:-https://cdn.mysql.com/Downloads/MySQL-8.4/${MYSQL_ARCHIVE}}"
MYSQL_EXTRACTED_DIR="${MYSQL_DIST_DIR}/mysql-${MYSQL_VERSION}-macos15-x86_64"
MYSQL_CURRENT="${MYSQL_HOME}/current"
MYSQL_CNF="${MYSQL_HOME}/my.cnf"

mkdir -p "${MYSQL_HOME}" "${MYSQL_SRC}" "${MYSQL_DIST_DIR}" "${MYSQL_HOME}/run" "${MYSQL_HOME}/log"

download_archive() {
  local archive_path="${MYSQL_SRC}/${MYSQL_ARCHIVE}"
  if [[ -f "${archive_path}" ]]; then
    echo "[info] using cached archive: ${archive_path}"
    return 0
  fi
  echo "[info] downloading ${MYSQL_URL}"
  curl -L --fail -o "${archive_path}" "${MYSQL_URL}"
}

extract_archive() {
  if [[ -x "${MYSQL_EXTRACTED_DIR}/bin/mysql" ]]; then
    echo "[info] using extracted mysql: ${MYSQL_EXTRACTED_DIR}"
  else
    echo "[info] extracting mysql archive"
    tar -xzf "${MYSQL_SRC}/${MYSQL_ARCHIVE}" -C "${MYSQL_DIST_DIR}"
  fi
  ln -sfn "${MYSQL_EXTRACTED_DIR}" "${MYSQL_CURRENT}"
}

write_my_cnf() {
  cat >"${MYSQL_CNF}" <<EOF
[mysqld]
port=${MYSQL_PORT}
basedir=${MYSQL_CURRENT}
datadir=${MYSQL_DATA}
socket=${MYSQL_SOCKET}
pid-file=${MYSQL_PID}
log-error=${MYSQL_LOG}
bind-address=127.0.0.1
skip-name-resolve
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
default-time-zone=+08:00

[client]
port=${MYSQL_PORT}
socket=${MYSQL_SOCKET}
default-character-set=utf8mb4
EOF
}

initialize_data_dir() {
  if [[ -d "${MYSQL_DATA}/mysql" ]]; then
    echo "[info] mysql data directory already initialized"
    return 0
  fi

  rm -rf "${MYSQL_DATA}"
  mkdir -p "${MYSQL_DATA}"
  echo "[info] initializing mysql data directory"
  arch -x86_64 "${MYSQL_CURRENT}/bin/mysqld" \
    --defaults-file="${MYSQL_CNF}" \
    --initialize-insecure
}

start_mysql() {
  if [[ -S "${MYSQL_SOCKET}" ]] && arch -x86_64 "${MYSQL_CURRENT}/bin/mysqladmin" --socket="${MYSQL_SOCKET}" ping >/dev/null 2>&1; then
    echo "[info] mysql already running on socket ${MYSQL_SOCKET}"
    return 0
  fi

  echo "[info] starting mysql"
  nohup arch -x86_64 "${MYSQL_CURRENT}/bin/mysqld" --defaults-file="${MYSQL_CNF}" >/dev/null 2>&1 &

  for _ in $(seq 1 60); do
    if [[ -S "${MYSQL_SOCKET}" ]] && arch -x86_64 "${MYSQL_CURRENT}/bin/mysqladmin" --socket="${MYSQL_SOCKET}" ping >/dev/null 2>&1; then
      echo "[info] mysql is ready"
      return 0
    fi
    sleep 1
  done

  echo "[error] mysql did not become ready; last log lines:" >&2
  tail -n 80 "${MYSQL_LOG}" >&2 || true
  return 1
}

bootstrap_database() {
  echo "[info] creating database and app user"
  arch -x86_64 "${MYSQL_CURRENT}/bin/mysql" --socket="${MYSQL_SOCKET}" -u root <<EOF
CREATE DATABASE IF NOT EXISTS \`${MYSQL_DB}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'localhost' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DB}\`.* TO '${MYSQL_USER}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON \`${MYSQL_DB}\`.* TO '${MYSQL_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF
}

import_schema() {
  echo "[info] importing schema and seed data from init.sql"
  arch -x86_64 "${MYSQL_CURRENT}/bin/mysql" --socket="${MYSQL_SOCKET}" -u root < "${ROOT_DIR}/src/main/resources/sql/init.sql"
}

write_app_env() {
  mkdir -p "$(dirname "${MYSQL_ENV_FILE}")"
  touch "${MYSQL_ENV_FILE}"

  python3 - "${MYSQL_ENV_FILE}" "${MYSQL_PORT}" "${MYSQL_DB}" "${MYSQL_USER}" "${MYSQL_PASSWORD}" <<'PY'
import pathlib
import sys

env_path = pathlib.Path(sys.argv[1])
port = sys.argv[2]
db = sys.argv[3]
user = sys.argv[4]
password = sys.argv[5]
url = f"jdbc:mysql://127.0.0.1:{port}/{db}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"

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

  echo "[info] updated ${MYSQL_ENV_FILE}"
}

download_archive
extract_archive
write_my_cnf
initialize_data_dir
start_mysql
bootstrap_database
import_schema
write_app_env

echo
echo "local mysql ready"
echo "home=${MYSQL_HOME}"
echo "socket=${MYSQL_SOCKET}"
echo "port=${MYSQL_PORT}"
echo "db=${MYSQL_DB}"
echo "user=${MYSQL_USER}"
echo "env_file=${MYSQL_ENV_FILE}"
