#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose exec -T linkchatai python - <<'PY'
from app import storage

handles = [
    "dy-account-01",
    "dy-account-02",
    "dy-account-03",
    "dy-account-04",
    "dy-demo-01",
    "dy-bridge-test",
    "dy-test-account",
]

with storage.get_connection() as conn:
    rows = conn.execute(
        f"SELECT id, handle, display_name FROM accounts WHERE handle IN ({','.join('?' for _ in handles)})",
        handles,
    ).fetchall()
    for row in rows:
        conn.execute("DELETE FROM accounts WHERE id = ?", (row["id"],))
        print(f"deleted {row['handle']} / {row['display_name']}")

print("demo data cleared")
PY
