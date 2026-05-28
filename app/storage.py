from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


DATABASE_PATH = Path(os.getenv("LINKCHATAI_DATABASE", "./data/linkchatai.sqlite3"))


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def get_connection() -> Iterator[sqlite3.Connection]:
    DATABASE_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DATABASE_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    if row is None:
        return None
    return {key: row[key] for key in row.keys()}


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [row_to_dict(row) or {} for row in rows]


CONVERSATION_SELECT = """
    c.id,
    c.account_id,
    c.external_id,
    c.customer_name,
    c.customer_handle,
    c.customer_avatar_color,
    c.unread_count,
    c.last_message_preview,
    c.last_message_at,
    c.created_at,
    c.updated_at,
    a.display_name AS account_name,
    a.handle AS account_handle,
    a.device_serial AS account_device_serial,
    a.avatar_color AS account_avatar_color
"""


def init_db() -> None:
    with get_connection() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                handle TEXT NOT NULL UNIQUE,
                channel TEXT NOT NULL DEFAULT 'douyin',
                avatar_color TEXT NOT NULL DEFAULT '#3f7f7a',
                status TEXT NOT NULL DEFAULT 'manual',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS conversations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                external_id TEXT NOT NULL,
                customer_name TEXT NOT NULL,
                customer_handle TEXT NOT NULL DEFAULT '',
                customer_avatar_color TEXT NOT NULL DEFAULT '#6f6a52',
                unread_count INTEGER NOT NULL DEFAULT 0,
                last_message_preview TEXT NOT NULL DEFAULT '',
                last_message_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(account_id, external_id)
            );

            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                direction TEXT NOT NULL CHECK(direction IN ('inbound', 'outbound', 'system')),
                sender_name TEXT NOT NULL,
                body TEXT NOT NULL,
                delivery_status TEXT NOT NULL DEFAULT 'received',
                channel_message_id TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS send_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                device_serial TEXT NOT NULL DEFAULT '',
                account_handle TEXT NOT NULL,
                conversation_external_id TEXT NOT NULL,
                body TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued', 'sent', 'failed')),
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                channel_message_id TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                delivered_at TEXT NOT NULL DEFAULT ''
            );
            """
        )
        migrate_schema(conn)
        if should_seed_demo_data():
            seed_demo_data(conn)


def should_seed_demo_data() -> bool:
    return os.getenv("LINKCHATAI_SEED_DEMO_DATA", "").strip().lower() in {"1", "true", "yes", "on"}


def migrate_schema(conn: sqlite3.Connection) -> None:
    ensure_column(conn, "accounts", "device_serial", "TEXT NOT NULL DEFAULT ''")


def ensure_column(conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
    columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in columns:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")


MOBILE_CONVERSATION_PREFIXES = (
    "douyin-notification-",
    "douyin-notification-thread-",
    "douyin-accessibility-",
    "douyin-accessibility-row-",
    "douyin-conversation-",
)


def is_mobile_conversation_external_id(value: str) -> bool:
    external_id = (value or "").strip()
    return any(external_id.startswith(prefix) for prefix in MOBILE_CONVERSATION_PREFIXES)


def java_string_hash_hex(value: str) -> str:
    result = 0
    for char in value:
        result = (31 * result + ord(char)) & 0xFFFFFFFF
    return f"{result:x}"


def canonical_mobile_external_id(external_id: str, customer_name: str) -> str:
    return (external_id or "").strip()


def canonical_mobile_customer_handle(customer_handle: str, customer_name: str) -> str:
    return (customer_handle or "").strip()


def auto_mobile_account_handle(device_serial: str) -> str:
    token = java_string_hash_hex((device_serial or "").strip() or "unknown")
    return f"dy-device-{token}"


def auto_mobile_account_display_name(account_handle: str) -> str:
    token = java_string_hash_hex((account_handle or "").strip() or "unknown")
    return f"抖音账号 {token[-4:]}"


def merge_mobile_conversation_aliases(conn: sqlite3.Connection) -> None:
    rows = conn.execute(
        """
        SELECT *
        FROM conversations
        WHERE external_id LIKE 'douyin-notification-%'
           OR external_id LIKE 'douyin-accessibility-%'
           OR external_id LIKE 'douyin-conversation-%'
        ORDER BY account_id, customer_name, id
        """
    ).fetchall()
    groups: dict[tuple[int, str], list[sqlite3.Row]] = {}
    for row in rows:
        customer_name = str(row["customer_name"] or "").strip()
        if not customer_name:
            continue
        groups.setdefault((int(row["account_id"]), customer_name), []).append(row)

    for (_, customer_name), group in groups.items():
        if len(group) < 2:
            continue
        canonical = choose_canonical_conversation(group)
        duplicate_rows = [row for row in group if row["id"] != canonical["id"]]
        if not duplicate_rows:
            continue

        canonical_id = canonical["id"]
        canonical_external_id = canonical_mobile_external_id(canonical["external_id"], customer_name)
        canonical_handle = canonical_mobile_customer_handle(canonical["customer_handle"], customer_name)
        for duplicate in duplicate_rows:
            duplicate_id = duplicate["id"]
            conn.execute("UPDATE messages SET conversation_id = ? WHERE conversation_id = ?", (canonical_id, duplicate_id))
            conn.execute("UPDATE send_tasks SET conversation_id = ? WHERE conversation_id = ?", (canonical_id, duplicate_id))
            conn.execute("DELETE FROM conversations WHERE id = ?", (duplicate_id,))

        maybe_update_conversation_external_id(conn, canonical_id, canonical["account_id"], canonical_external_id)
        latest_message = conn.execute(
            """
            SELECT body, created_at
            FROM messages
            WHERE conversation_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """,
            (canonical_id,),
        ).fetchone()
        unread_total = sum(int(row["unread_count"] or 0) for row in group)
        preview = latest_message["body"] if latest_message is not None else canonical["last_message_preview"]
        last_at = latest_message["created_at"] if latest_message is not None else canonical["last_message_at"]
        conn.execute(
            """
            UPDATE conversations
            SET customer_name = ?,
                customer_handle = ?,
                unread_count = ?,
                last_message_preview = ?,
                last_message_at = ?,
                updated_at = ?
            WHERE id = ?
            """,
            (customer_name, canonical_handle, unread_total, preview, last_at, utc_now(), canonical_id),
        )


def choose_canonical_conversation(rows: list[sqlite3.Row]) -> sqlite3.Row:
    def sort_key(row: sqlite3.Row) -> tuple[int, int, str, int]:
        external_id = str(row["external_id"] or "")
        if external_id.startswith("douyin-conversation-"):
            priority = 0
        elif external_id.startswith("douyin-accessibility-"):
            priority = 1
        elif external_id.startswith("douyin-notification-"):
            priority = 2
        else:
            priority = 3
        return (priority, -int(row["id"]), str(row["last_message_at"] or ""), int(row["id"]))

    return sorted(rows, key=sort_key)[0]


def maybe_update_conversation_external_id(
    conn: sqlite3.Connection,
    conversation_id: int,
    account_id: int,
    external_id: str,
) -> None:
    if not external_id:
        return
    existing = conn.execute(
        """
        SELECT id
        FROM conversations
        WHERE account_id = ? AND external_id = ? AND id != ?
        """,
        (account_id, external_id, conversation_id),
    ).fetchone()
    if existing is None:
        conn.execute("UPDATE conversations SET external_id = ? WHERE id = ?", (external_id, conversation_id))


def seed_demo_data(conn: sqlite3.Connection) -> None:
    existing = conn.execute("SELECT COUNT(*) AS count FROM accounts").fetchone()
    if existing and existing["count"] > 0:
        return

    now = utc_now()
    accounts = [
        ("抖音号 01", "dy-account-01", "#2f6f73", "manual"),
        ("抖音号 02", "dy-account-02", "#835c3b", "manual"),
        ("抖音号 03", "dy-account-03", "#5f6f46", "manual"),
        ("抖音号 04", "dy-account-04", "#6c5d86", "manual"),
    ]
    conn.executemany(
        """
        INSERT INTO accounts(display_name, handle, channel, avatar_color, status, created_at, updated_at)
        VALUES (?, ?, 'douyin', ?, ?, ?, ?)
        """,
        [(name, handle, color, status, now, now) for name, handle, color, status in accounts],
    )

    account_rows = conn.execute("SELECT id, display_name FROM accounts ORDER BY id").fetchall()
    samples = [
        ("u-10001", "用户 A", "想问一下今天还能安排吗？", 2),
        ("u-10002", "用户 B", "价格可以发我一份吗", 1),
        ("u-10003", "用户 C", "我昨天咨询过，想继续了解。", 0),
        ("u-10004", "用户 D", "收到，我晚点再确认。", 0),
    ]

    for index, account in enumerate(account_rows):
        for offset, sample in enumerate(samples[:3 if index == 0 else 2]):
            external_id, customer_name, preview, unread = sample
            conversation_key = f"{external_id}-{account['id']}"
            created = utc_now()
            cursor = conn.execute(
                """
                INSERT INTO conversations(
                    account_id, external_id, customer_name, customer_handle,
                    customer_avatar_color, unread_count,
                    last_message_preview, last_message_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account["id"],
                    conversation_key,
                    customer_name,
                    f"open-{1000 + offset}",
                    ["#8a5f4d", "#5d728a", "#687a4e", "#7b607f"][offset],
                    unread,
                    preview,
                    created,
                    created,
                    created,
                ),
            )
            conversation_id = cursor.lastrowid
            conn.executemany(
                """
                INSERT INTO messages(conversation_id, account_id, direction, sender_name, body, delivery_status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        conversation_id,
                        account["id"],
                        "inbound",
                        customer_name,
                        "你好，我从抖音看到你们的信息，想先咨询一下。",
                        "received",
                        created,
                    ),
                    (
                        conversation_id,
                        account["id"],
                        "outbound",
                        account["display_name"],
                        "你好，可以的。我先帮你记录一下需求。",
                        "local_only",
                        created,
                    ),
                    (
                        conversation_id,
                        account["id"],
                        "inbound",
                        customer_name,
                        preview,
                        "received",
                        created,
                    ),
                ],
            )


def list_accounts() -> list[dict[str, Any]]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT
                a.*,
                COUNT(c.id) AS conversation_count,
                COALESCE(SUM(c.unread_count), 0) AS unread_count
            FROM accounts a
            LEFT JOIN conversations c ON c.account_id = a.id
            GROUP BY a.id
            ORDER BY a.id
            """
        ).fetchall()
        return rows_to_dicts(rows)


def list_conversations(account_id: int | None = None) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if account_id:
        clauses.append("c.account_id = ?")
        params.append(account_id)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""

    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT {CONVERSATION_SELECT}
            FROM conversations c
            JOIN accounts a ON a.id = c.account_id
            {where}
            ORDER BY c.unread_count DESC, c.last_message_at DESC, c.id DESC
            """,
            params,
        ).fetchall()
        return rows_to_dicts(rows)


def get_conversation(conversation_id: int) -> dict[str, Any] | None:
    with get_connection() as conn:
        row = conn.execute(
            f"""
            SELECT {CONVERSATION_SELECT}
            FROM conversations c
            JOIN accounts a ON a.id = c.account_id
            WHERE c.id = ?
            """,
            (conversation_id,),
        ).fetchone()
        return row_to_dict(row)


def list_messages(conversation_id: int) -> list[dict[str, Any]]:
    with get_connection() as conn:
        rows = conn.execute(
            """
            SELECT *
            FROM messages
            WHERE conversation_id = ?
            ORDER BY created_at ASC, id ASC
            """,
            (conversation_id,),
        ).fetchall()
        return rows_to_dicts(rows)


def mark_conversation_read(conversation_id: int) -> None:
    with get_connection() as conn:
        conn.execute(
            """
            UPDATE conversations
            SET unread_count = 0, updated_at = ?
            WHERE id = ?
            """,
            (utc_now(), conversation_id),
        )


def add_outbound_message(conversation_id: int, body: str, sender_name: str | None = None) -> dict[str, Any] | None:
    conversation = get_conversation(conversation_id)
    if conversation is None:
        return None

    created = utc_now()
    final_sender_name = (sender_name or conversation["account_name"]).strip()
    with get_connection() as conn:
        cursor = conn.execute(
            """
            INSERT INTO messages(conversation_id, account_id, direction, sender_name, body, delivery_status, created_at)
            VALUES (?, ?, 'outbound', ?, ?, 'queued', ?)
            """,
            (
                conversation_id,
                conversation["account_id"],
                final_sender_name,
                body,
                created,
            ),
        )
        message_id = cursor.lastrowid
        conn.execute(
            """
            INSERT INTO send_tasks(
                message_id, conversation_id, account_id, device_serial, account_handle,
                conversation_external_id, body, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?)
            """,
            (
                message_id,
                conversation_id,
                conversation["account_id"],
                conversation.get("account_device_serial") or "",
                conversation["account_handle"],
                conversation["external_id"],
                body,
                created,
                created,
            ),
        )
        conn.execute(
            """
            UPDATE conversations
            SET unread_count = 0,
                last_message_preview = ?,
                last_message_at = ?,
                updated_at = ?
            WHERE id = ?
            """,
            (body, created, created, conversation_id),
        )
        row = conn.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
        return row_to_dict(row)


def upsert_mobile_message(
    *,
    device_serial: str,
    account_handle: str,
    account_display_name: str,
    conversation_external_id: str,
    customer_name: str,
    customer_handle: str,
    direction: str,
    body: str,
    sender_name: str | None = None,
    channel_message_id: str = "",
    occurred_at: str | None = None,
) -> dict[str, Any]:
    now = utc_now()
    created_at = occurred_at or now
    normalized_direction = direction if direction in {"inbound", "outbound", "system"} else "inbound"
    display_name = account_display_name or account_handle
    normalized_customer_name = customer_name.strip()
    normalized_external_id = canonical_mobile_external_id(conversation_external_id, normalized_customer_name)
    normalized_customer_handle = canonical_mobile_customer_handle(customer_handle, normalized_customer_name)
    account_color = stable_color(account_handle)
    customer_color = stable_color(normalized_customer_handle or normalized_customer_name)

    with get_connection() as conn:
        account = conn.execute("SELECT * FROM accounts WHERE handle = ?", (account_handle,)).fetchone()
        if account is None:
            cursor = conn.execute(
                """
                INSERT INTO accounts(display_name, handle, channel, avatar_color, status, device_serial, created_at, updated_at)
                VALUES (?, ?, 'douyin', ?, 'mobile_bridge', ?, ?, ?)
                """,
                (display_name, account_handle, account_color, device_serial, now, now),
            )
            account_id = cursor.lastrowid
        else:
            account_id = account["id"]
            conn.execute(
                """
                UPDATE accounts
                SET display_name = ?, device_serial = ?, updated_at = ?
                WHERE id = ?
                """,
                (display_name, device_serial, now, account_id),
            )

        if channel_message_id:
            existing = conn.execute(
                """
                SELECT *
                FROM messages
                WHERE account_id = ? AND channel_message_id = ?
                """,
                (account_id, channel_message_id),
            ).fetchone()
            if existing is not None:
                conversation_row = conn.execute(
                    f"""
                    SELECT {CONVERSATION_SELECT}
                    FROM conversations c
                    JOIN accounts a ON a.id = c.account_id
                    WHERE c.id = ?
                    """,
                    (existing["conversation_id"],),
                ).fetchone()
                return {
                    "conversation": row_to_dict(conversation_row),
                    "message": row_to_dict(existing),
                    "duplicate": True,
                }

        conversation = conn.execute(
            """
            SELECT *
            FROM conversations
            WHERE account_id = ? AND external_id = ?
            """,
            (account_id, normalized_external_id),
        ).fetchone()

        unread_delta = 1 if normalized_direction == "inbound" else 0
        if conversation is None:
            cursor = conn.execute(
                """
                INSERT INTO conversations(
                    account_id, external_id, customer_name, customer_handle,
                    customer_avatar_color, unread_count,
                    last_message_preview, last_message_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account_id,
                    normalized_external_id,
                    normalized_customer_name,
                    normalized_customer_handle,
                    customer_color,
                    unread_delta,
                    body,
                    created_at,
                    now,
                    now,
                ),
            )
            conversation_id = cursor.lastrowid
        else:
            conversation_id = conversation["id"]
            conn.execute(
                """
                UPDATE conversations
                SET customer_name = ?,
                    customer_handle = ?,
                    unread_count = unread_count + ?,
                    last_message_preview = ?,
                    last_message_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    normalized_customer_name,
                    normalized_customer_handle,
                    unread_delta,
                    body,
                    created_at,
                    now,
                    conversation_id,
                ),
            )

        final_sender = sender_name or (normalized_customer_name if normalized_direction == "inbound" else display_name)
        delivery_status = "received" if normalized_direction == "inbound" else "sent"
        cursor = conn.execute(
            """
            INSERT INTO messages(
                conversation_id, account_id, direction, sender_name, body,
                delivery_status, channel_message_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                conversation_id,
                account_id,
                normalized_direction,
                final_sender,
                body,
                delivery_status,
                channel_message_id,
                created_at,
            ),
        )
        message = conn.execute("SELECT * FROM messages WHERE id = ?", (cursor.lastrowid,)).fetchone()
        conversation_row = conn.execute(
            f"""
            SELECT {CONVERSATION_SELECT}
            FROM conversations c
            JOIN accounts a ON a.id = c.account_id
            WHERE c.id = ?
            """,
            (conversation_id,),
        ).fetchone()
        return {
            "conversation": row_to_dict(conversation_row),
            "message": row_to_dict(message),
            "duplicate": False,
        }


def list_send_tasks(
    *,
    device_serial: str | None = None,
    account_handle: str | None = None,
    limit: int = 50,
) -> list[dict[str, Any]]:
    clauses = ["t.status = 'queued'"]
    params: list[Any] = []
    if device_serial:
        clauses.append("(t.device_serial = ? OR t.device_serial = '')")
        params.append(device_serial)
    if account_handle:
        clauses.append("t.account_handle = ?")
        params.append(account_handle)
    params.append(max(1, min(limit, 200)))
    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT
                t.*,
                m.sender_name,
                c.customer_name,
                c.customer_handle,
                a.display_name AS account_name
            FROM send_tasks t
            JOIN messages m ON m.id = t.message_id
            JOIN conversations c ON c.id = t.conversation_id
            JOIN accounts a ON a.id = t.account_id
            WHERE {' AND '.join(clauses)}
            ORDER BY t.created_at ASC, t.id ASC
            LIMIT ?
            """,
            params,
        ).fetchall()
        return rows_to_dicts(rows)


def ack_send_task(
    task_id: int,
    *,
    status: str,
    channel_message_id: str = "",
    error: str = "",
) -> dict[str, Any] | None:
    if status not in {"queued", "sent", "failed"}:
        raise ValueError("unsupported send task status")
    now = utc_now()
    with get_connection() as conn:
        task = conn.execute("SELECT * FROM send_tasks WHERE id = ?", (task_id,)).fetchone()
        if task is None:
            return None

        delivered_at = now if status == "sent" else task["delivered_at"]
        attempt_increment = 1 if status in {"sent", "failed"} else 0
        conn.execute(
            """
            UPDATE send_tasks
            SET status = ?,
                attempt_count = attempt_count + ?,
                last_error = ?,
                channel_message_id = ?,
                updated_at = ?,
                delivered_at = ?
            WHERE id = ?
            """,
            (status, attempt_increment, error, channel_message_id, now, delivered_at, task_id),
        )
        conn.execute(
            """
            UPDATE messages
            SET delivery_status = ?,
                channel_message_id = CASE WHEN ? != '' THEN ? ELSE channel_message_id END
            WHERE id = ?
            """,
            (status, channel_message_id, channel_message_id, task["message_id"]),
        )
        row = conn.execute("SELECT * FROM send_tasks WHERE id = ?", (task_id,)).fetchone()
        return row_to_dict(row)


def stable_color(value: str) -> str:
    palette = ["#2f6f73", "#835c3b", "#5f6f46", "#6c5d86", "#5d728a", "#8a5f4d"]
    total = sum(ord(char) for char in value)
    return palette[total % len(palette)]
