#!/usr/bin/env bash
# Installs manus-tv.apk on the TV (adb over Wi-Fi at $TV_IP) and launches it. Run in Git Bash.
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.env ] && { set -a; . ./local.env; set +a; }
: "${TV_IP:?Set TV_IP (the TV's address) in local.env or the environment}"
LA=$(cygpath -u "${LOCALAPPDATA:-$HOME/AppData/Local}")
ADB=$(ls "$LA"/Microsoft/WinGet/Packages/Google.PlatformTools_*/platform-tools/adb.exe | head -1)
"$ADB" connect "$TV_IP:5555"
"$ADB" install -r manus-tv.apk
"$ADB" shell monkey -p lk.manus.iptv -c android.intent.category.LEANBACK_LAUNCHER 1
