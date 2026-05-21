#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PORT="${LINKCHATAI_HOST_PORT:-8002}"

docker compose up -d --build

printf 'Waiting for LinkChatAI on port %s' "$PORT"
for _ in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    printf '\n'
    break
  fi
  printf '.'
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
  printf '\nLinkChatAI did not become healthy. Showing recent logs:\n' >&2
  docker compose logs --tail=80 linkchatai >&2
  exit 1
fi

LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
fi

echo "LinkChatAI is running:"
echo "  Local: http://127.0.0.1:${PORT}"
if [ -n "$LAN_IP" ]; then
  echo "  LAN:   http://${LAN_IP}:${PORT}"
fi
echo "Bridge:"
echo "  Mode: ${LINKCHATAI_BRIDGE_SEND_MODE:-dry_run}"
echo "  CrowdMasterAI: ${CROWDMASTERAI_BASE_URL:-http://host.docker.internal:8004}"
echo "  Logs: docker compose logs -f bridge"
echo "  Stop: bash scripts/stop_all.sh"
