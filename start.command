#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
./start.sh
printf '\nPress any key to close this window...'
read -r -n 1
