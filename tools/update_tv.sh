#!/usr/bin/env bash
# Installs/updates Photos Frame on the TV over Wi-Fi. Mac/Linux.
# Usage: ./update_tv.sh [TV-IP] [path/to/PhotosFrame.apk]
# Run it from the folder that holds adb (platform-tools) or with adb on your PATH.
# The TV's IP is remembered in tv_ip.txt after the first run.
set -euo pipefail
cd "$(dirname "$0")"
ADB=adb; [ -x ./adb ] && ADB=./adb
IP="${1:-$(cat tv_ip.txt 2>/dev/null || true)}"
APK="${2:-PhotosFrame.apk}"
[ -n "$IP" ] || { echo "Give the TV's IP the first time: ./update_tv.sh 192.168.1.42"; exit 1; }
[ -f "$APK" ] || { echo "Can't find $APK next to this script."; exit 1; }
echo "$IP" > tv_ip.txt
echo "Connecting to the TV at $IP ..."
"$ADB" connect "$IP:5555" | tee /tmp/pf_adb.txt
if ! grep -q "connected" /tmp/pf_adb.txt; then
  echo "Couldn't reach the TV. Check it's on, on the same Wi-Fi, and that USB/network debugging"
  echo "is still on (Settings > System > Developer options). If the TV shows an 'Allow' prompt, accept it and run this again."
  exit 1
fi
"$ADB" -s "$IP:5555" install -r "$APK"
"$ADB" -s "$IP:5555" shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
"$ADB" -s "$IP:5555" shell settings put secure screensaver_enabled 1
"$ADB" -s "$IP:5555" shell am start -n com.gary.photosframe/.MainActivity >/dev/null
echo "Done: Photos Frame updated and opened on the TV."
