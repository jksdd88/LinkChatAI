#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

LINKCHATAI_HOST_PORT="${LINKCHATAI_HOST_PORT:-8002}"
CROWDMASTERAI_LOCAL_URL="${CROWDMASTERAI_LOCAL_URL:-http://127.0.0.1:8004}"
DEVICE_SERIAL="${LINKCHATAI_DEMO_DEVICE_SERIAL:-}"
ACCOUNT_HANDLE="${LINKCHATAI_DEMO_ACCOUNT_HANDLE:-dy-demo-01}"
ACCOUNT_DISPLAY_NAME="${LINKCHATAI_DEMO_ACCOUNT_DISPLAY_NAME:-抖音试用号 01}"
CUSTOMER_NAME="${LINKCHATAI_DEMO_CUSTOMER_NAME:-试用用户}"
CUSTOMER_HANDLE="${LINKCHATAI_DEMO_CUSTOMER_HANDLE:-dy-user-demo}"
NOW="$(date +%Y%m%d%H%M%S)"
CONVERSATION_EXTERNAL_ID="${LINKCHATAI_DEMO_CONVERSATION_EXTERNAL_ID:-douyin-thread-demo-${NOW}}"
CHANNEL_MESSAGE_ID="${LINKCHATAI_DEMO_CHANNEL_MESSAGE_ID:-dy-msg-demo-${NOW}}"
BODY="${LINKCHATAI_DEMO_BODY:-你好，我想咨询一下，这是手机侧模拟上报的抖音私信。}"

if [ -z "${DEVICE_SERIAL}" ]; then
  DEVICES_JSON="$(curl -fsS "${CROWDMASTERAI_LOCAL_URL}/api/devices/status" 2>/dev/null || true)"
  DEVICE_SERIAL="$(printf '%s' "${DEVICES_JSON}" | sed -n 's/.*"serial":"\([^"]*\)".*/\1/p' | head -1)"
fi

if [ -z "${DEVICE_SERIAL}" ]; then
  DEVICE_SERIAL="phone-demo-001"
  echo "No CrowdMasterAI device was detected. Using demo device serial: ${DEVICE_SERIAL}" >&2
fi

curl -fsS -X POST "http://127.0.0.1:${LINKCHATAI_HOST_PORT}/api/mobile/messages" \
  -H 'Content-Type: application/json' \
  --data @- <<JSON
{
  "device_serial": "${DEVICE_SERIAL}",
  "account_handle": "${ACCOUNT_HANDLE}",
  "account_display_name": "${ACCOUNT_DISPLAY_NAME}",
  "conversation_external_id": "${CONVERSATION_EXTERNAL_ID}",
  "customer_name": "${CUSTOMER_NAME}",
  "customer_handle": "${CUSTOMER_HANDLE}",
  "direction": "inbound",
  "body": "${BODY}",
  "channel_message_id": "${CHANNEL_MESSAGE_ID}"
}
JSON

echo
echo "Demo message created."
echo "Device serial: ${DEVICE_SERIAL}"
echo "Open http://127.0.0.1:${LINKCHATAI_HOST_PORT}, select ${ACCOUNT_DISPLAY_NAME}, reply in the page, then watch:"
echo "  docker compose logs -f bridge"
