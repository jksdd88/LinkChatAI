#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

LINKCHATAI_HOST_PORT="${LINKCHATAI_HOST_PORT:-8002}"
CROWDMASTERAI_LOCAL_URL="${CROWDMASTERAI_LOCAL_URL:-http://127.0.0.1:8004}"

echo "== Containers =="
docker compose ps

echo
echo "== LinkChatAI =="
if curl -fsS "http://127.0.0.1:${LINKCHATAI_HOST_PORT}/health"; then
  echo
else
  echo "LinkChatAI is not reachable on port ${LINKCHATAI_HOST_PORT}."
  exit 1
fi

echo
echo "== CrowdMasterAI =="
if curl -fsS "${CROWDMASTERAI_LOCAL_URL}/api/health"; then
  echo
else
  echo "CrowdMasterAI is not reachable at ${CROWDMASTERAI_LOCAL_URL}."
  echo "Start CrowdMasterAI first, or set CROWDMASTERAI_LOCAL_URL."
  exit 1
fi

echo
echo "== CrowdMasterAI devices =="
DEVICES_JSON="$(curl -fsS "${CROWDMASTERAI_LOCAL_URL}/api/devices/status")"
echo "${DEVICES_JSON}"
echo
if printf '%s' "${DEVICES_JSON}" | grep -q '"devices":\[\]'; then
  echo "No Android device is online in CrowdMasterAI yet."
  echo "Connect a phone first, then run this script again."
else
  echo "CrowdMasterAI has at least one reported device."
fi

echo
echo "== Bridge logs =="
docker compose logs --tail=25 bridge

