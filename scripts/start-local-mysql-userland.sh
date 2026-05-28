#!/usr/bin/env bash
set -euo pipefail

MYSQL_HOME="${AI_MEETING_MYSQL_HOME:-$HOME/.aimeeting-room/mysql}"
MYSQL_CURRENT="${MYSQL_HOME}/current"
MYSQL_CNF="${MYSQL_HOME}/my.cnf"
MYSQL_SOCKET="${MYSQL_HOME}/run/mysql.sock"
MYSQL_LOG="${MYSQL_HOME}/log/mysqld.log"

if [[ ! -x "${MYSQL_CURRENT}/bin/mysqld" ]]; then
  echo "[error] mysql binaries not found under ${MYSQL_CURRENT}" >&2
  echo "[hint] run scripts/install-local-mysql-userland.sh first" >&2
  exit 1
fi

if [[ ! -f "${MYSQL_CNF}" ]]; then
  echo "[error] mysql config not found: ${MYSQL_CNF}" >&2
  echo "[hint] run scripts/install-local-mysql-userland.sh first" >&2
  exit 1
fi

if [[ -S "${MYSQL_SOCKET}" ]] && arch -x86_64 "${MYSQL_CURRENT}/bin/mysqladmin" --socket="${MYSQL_SOCKET}" ping >/dev/null 2>&1; then
  echo "[info] mysql already running"
  exit 0
fi

nohup arch -x86_64 "${MYSQL_CURRENT}/bin/mysqld" --defaults-file="${MYSQL_CNF}" >/dev/null 2>&1 &

for _ in $(seq 1 60); do
  if [[ -S "${MYSQL_SOCKET}" ]] && arch -x86_64 "${MYSQL_CURRENT}/bin/mysqladmin" --socket="${MYSQL_SOCKET}" ping >/dev/null 2>&1; then
    echo "local mysql started"
    echo "socket=${MYSQL_SOCKET}"
    exit 0
  fi
  sleep 1
done

echo "[error] mysql did not become ready; last log lines:" >&2
tail -n 80 "${MYSQL_LOG}" >&2 || true
exit 1
