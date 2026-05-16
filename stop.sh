#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

docker compose stop

echo "LinkChatAI has been stopped. Data remains in ./data."
