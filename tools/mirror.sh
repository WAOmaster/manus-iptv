#!/bin/bash
# Live PC mirror of Manus TV via scrcpy; restarts itself when the connection drops.
# Stop: touch /tmp/m3uapk/mirror.stop, then close the "Manus TV" window.
cd "$(dirname "$0")/.." && [ -f local.env ] && { set -a; . ./local.env; set +a; }
: "${TV_IP:?Set TV_IP in local.env}"; : "${SCRCPY_DIR:?Set SCRCPY_DIR (folder with scrcpy.exe and adb.exe, /mnt/c/... path) in local.env}"
D=$SCRCPY_DIR
mkdir -p /tmp/m3uapk; cd "$D" || exit 1
while true; do
  ./adb.exe connect $TV_IP:5555 >/dev/null 2>&1
  ./scrcpy.exe -s $TV_IP:5555 --no-audio --max-size 960 --max-fps 15 --video-encoder=c2.android.avc.encoder --window-title "Manus TV" >>/tmp/m3uapk/scrcpy.log 2>&1
  [ -f /tmp/m3uapk/mirror.stop ] && exit 0
  sleep 3
done
