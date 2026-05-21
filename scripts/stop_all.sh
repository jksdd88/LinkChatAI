#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

docker compose stop

echo "LinkChatAI has been stopped. Data remains in ./data."
echo "Restart: bash scripts/start_all.sh"
