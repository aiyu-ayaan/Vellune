#!/usr/bin/env bash
# Dev gateway: make the local Vellune server reachable from every connected phone/emulator.
# Usage: scripts/android-gateway.sh [port]   (default 3000, the dev server)
set -euo pipefail
PORT="${1:-3000}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
command -v "$ADB" >/dev/null || ADB=adb

serials="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')"
[ -n "$serials" ] || { echo "No device. Plug in a phone (USB debugging) or start an emulator." >&2; exit 0; }

for s in $serials; do
  "$ADB" -s "$s" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 || true
  echo "Device $s: reverse port tcp:$PORT mapped"
done
