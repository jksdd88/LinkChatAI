#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

APK_PATH="${1:-dist/LinkChatBridge-debug.apk}"
CROWDMASTER_CONTAINER="${CROWDMASTER_CONTAINER:-crowdmaster-ai}"
CROWDMASTERAI_LOCAL_URL="${CROWDMASTERAI_LOCAL_URL:-http://127.0.0.1:8004}"
SERIAL="${LINKCHATAI_APK_DEVICE_SERIAL:-}"

if [ ! -f "$APK_PATH" ]; then
  echo "APK not found: $APK_PATH"
  echo "Build it first: ./scripts/build-apk.sh"
  exit 1
fi

if [ -z "$SERIAL" ]; then
  DEVICES_JSON="$(curl -fsS "${CROWDMASTERAI_LOCAL_URL}/api/devices/status")"
  SERIAL="$(printf '%s' "$DEVICES_JSON" | sed -n 's/.*"serial":"\([^"]*\)".*/\1/p' | head -1)"
fi

if [ -z "$SERIAL" ]; then
  echo "No online Android device was found in CrowdMasterAI."
  exit 1
fi

docker cp "$APK_PATH" "${CROWDMASTER_CONTAINER}:/tmp/linkchat-bridge.apk"
if docker exec "$CROWDMASTER_CONTAINER" adb -s "$SERIAL" install -r /tmp/linkchat-bridge.apk; then
  docker exec "$CROWDMASTER_CONTAINER" adb -s "$SERIAL" shell monkey -p ai.linkchat.bridge 1 >/dev/null
  echo "Installed and launched LinkChat Bridge on $SERIAL"
else
  echo "ADB install was blocked by the phone. Pushing APK to Downloads for manual install."
  docker exec "$CROWDMASTER_CONTAINER" adb -s "$SERIAL" push /tmp/linkchat-bridge.apk /sdcard/Download/LinkChatBridge-debug.apk
  echo "APK pushed to phone: /sdcard/Download/LinkChatBridge-debug.apk"
  echo "Open the phone file manager and install LinkChatBridge-debug.apk manually."
fi
