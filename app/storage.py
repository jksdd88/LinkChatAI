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
                status TEXT NOT NULL DEFAULT 'open',
                priority TEXT NOT NULL DEFAULT 'normal',
                unread_count INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
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
            """
        )
        seed_demo_data(conn)


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
        ("u-10001", "用户 A", "想问一下今天还能安排吗？", "open", "high", 2),
        ("u-10002", "用户 B", "价格可以发我一份吗", "open", "normal", 1),
        ("u-10003", "用户 C", "我昨天咨询过，想继续了解。", "pending", "normal", 0),
        ("u-10004", "用户 D", "收到，我晚点再确认。", "closed", "low", 0),
    ]

    for index, account in enumerate(account_rows):
        for offset, sample in enumerate(samples[:3 if index == 0 else 2]):
            external_id, customer_name, preview, status, priority, unread = sample
            conversation_key = f"{external_id}-{account['id']}"
            created = utc_now()
            cursor = conn.execute(
                """
                INSERT INTO conversations(
                    account_id, external_id, customer_name, customer_handle,
                    customer_avatar_color, status, priority, unread_count, note,
                    last_message_preview, last_message_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account["id"],
                    conversation_key,
                    customer_name,
                    f"open-{1000 + offset}",
                    ["#8a5f4d", "#5d728a", "#687a4e", "#7b607f"][offset],
                    status,
                    priority,
                    unread,
                    "重点跟进" if priority == "high" else "",
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


def list_conversations(account_id: int | None = None, status: str | None = None) -> list[dict[str, Any]]:
    clauses: list[str] = []
    params: list[Any] = []
    if account_id:
        clauses.append("c.account_id = ?")
        params.append(account_id)
    if status and status != "all":
        clauses.append("c.status = ?")
        params.append(status)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""

    with get_connection() as conn:
        rows = conn.execute(
            f"""
            SELECT
                c.*,
                a.display_name AS account_name,
                a.handle AS account_handle,
                a.avatar_color AS account_avatar_color
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
            """
            SELECT
                c.*,
                a.display_name AS account_name,
                a.handle AS account_handle,
                a.avatar_color AS account_avatar_color
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
            VALUES (?, ?, 'outbound', ?, ?, 'local_only', ?)
            """,
            (
                conversation_id,
                conversation["account_id"],
                final_sender_name,
                body,
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
        row = conn.execute("SELECT * FROM messages WHERE id = ?", (cursor.lastrowid,)).fetchone()
        return row_to_dict(row)


def update_conversation(conversation_id: int, status: str | None, priority: str | None, note: str | None) -> dict[str, Any] | None:
    conversation = get_conversation(conversation_id)
    if conversation is None:
        return None

    next_status = status if status is not None else conversation["status"]
    next_priority = priority if priority is not None else conversation["priority"]
    next_note = note if note is not None else conversation["note"]

    with get_connection() as conn:
        conn.execute(
            """
            UPDATE conversations
            SET status = ?, priority = ?, note = ?, updated_at = ?
            WHERE id = ?
            """,
            (next_status, next_priority, next_note, utc_now(), conversation_id),
        )
    return get_conversation(conversation_id)
