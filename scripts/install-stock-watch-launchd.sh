#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="${AI_MEETING_LAUNCHD_LABEL:-com.aimeeting.stock-watch-stack}"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
LOG_DIR="${AI_MEETING_LOG_DIR:-$HOME/.aimeeting-room/logs}"

usage() {
  cat <<USAGE
Usage: scripts/install-stock-watch-launchd.sh <install|uninstall|status|print-plist>

install      Write and load a per-user launchd agent that runs
             scripts/start-stock-watch-stack.sh --watch at login.
uninstall    Unload and remove the launchd agent.
status       Print launchd status for ${LABEL}.
print-plist  Print the plist that would be installed.
USAGE
}

xml_escape() {
  python3 -c 'import html, sys; print(html.escape(sys.argv[1], quote=True))' "$1"
}

render_plist() {
  local root log_dir label
  root="$(xml_escape "$ROOT_DIR")"
  log_dir="$(xml_escape "$LOG_DIR")"
  label="$(xml_escape "$LABEL")"
  cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/zsh</string>
    <string>-lc</string>
    <string>cd "${root}" &amp;&amp; exec "${root}/scripts/start-stock-watch-stack.sh" --watch</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>WorkingDirectory</key>
  <string>${root}</string>
  <key>StandardOutPath</key>
  <string>${log_dir}/stock-watch-stack-launchd.out.log</string>
  <key>StandardErrorPath</key>
  <string>${log_dir}/stock-watch-stack-launchd.err.log</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
</dict>
</plist>
PLIST
}

install_agent() {
  mkdir -p "$(dirname "$PLIST")" "$LOG_DIR"
  render_plist > "$PLIST"
  plutil -lint "$PLIST" >/dev/null
  launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$(id -u)" "$PLIST"
  launchctl kickstart -k "gui/$(id -u)/${LABEL}" >/dev/null 2>&1 || true
  echo "installed and loaded: ${PLIST}"
  echo "status: launchctl print gui/$(id -u)/${LABEL}"
}

uninstall_agent() {
  launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
  rm -f "$PLIST"
  echo "uninstalled: ${LABEL}"
}

status_agent() {
  launchctl print "gui/$(id -u)/${LABEL}" 2>/dev/null || {
    echo "${LABEL} is not loaded"
    return 1
  }
}

case "${1:-}" in
  install)
    install_agent
    ;;
  uninstall)
    uninstall_agent
    ;;
  status)
    status_agent
    ;;
  print-plist)
    render_plist
    ;;
  -h|--help|"")
    usage
    ;;
  *)
    echo "Unknown command: $1" >&2
    usage >&2
    exit 2
    ;;
esac
