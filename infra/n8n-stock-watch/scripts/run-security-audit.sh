#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

load_env
mkdir -p "$ROOT_DIR/runtime/audit"
if compose exec -T n8n n8n audit --help >/dev/null 2>&1; then
  compose exec -T n8n n8n audit > "$ROOT_DIR/runtime/audit/n8n-audit.txt" 2>&1 || true
  sed -n '1,220p' "$ROOT_DIR/runtime/audit/n8n-audit.txt"
  if command -v jq >/dev/null 2>&1 && jq empty "$ROOT_DIR/runtime/audit/n8n-audit.txt" >/dev/null 2>&1; then
    jq -r 'to_entries[] as $report | $report.value.sections[]? | "\($report.key) | \(.title)"' \
      "$ROOT_DIR/runtime/audit/n8n-audit.txt" > "$ROOT_DIR/runtime/audit/n8n-audit-findings.txt"
    unexpected=$(grep -Ev '^(Credentials Risk Report \| Credentials not used in any active workflow|Database Risk Report \| Unused "Query Parameters" fields in SQL nodes|Nodes Risk Report \| Official risky nodes|Instance Risk Report \| Outdated instance|Instance Risk Report \| Security settings)$' "$ROOT_DIR/runtime/audit/n8n-audit-findings.txt" || true)
    if [ -n "$unexpected" ]; then
      echo "[error] unexpected n8n audit finding(s):" >&2
      printf '%s\n' "$unexpected" >&2
      exit 30
    fi
    echo "n8n audit findings match the current local allowlist"
  else
    echo "[warning] jq unavailable or audit output is not JSON; allowlist gate skipped"
  fi
else
  echo "n8n audit command not available for configured image" | tee "$ROOT_DIR/runtime/audit/n8n-audit.txt"
fi
