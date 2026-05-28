from __future__ import annotations

import json
import logging
import os
import re
import hashlib
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


LOGGER = logging.getLogger("linkchatai.bridge")
SEND_MODES = {"dry_run", "crowdmaster_text_only", "crowdmaster_text_enter"}


class BridgeError(RuntimeError):
    pass


@dataclass(frozen=True)
class BridgeSettings:
    linkchat_base_url: str
    crowdmaster_base_url: str
    poll_seconds: float
    task_limit: int
    send_mode: str
    account_device_map: dict[str, str]
    device_serials: list[str]
    request_timeout: float
    scan_douyin: bool
    scan_chat_rows_limit: int
    auto_open_douyin: bool


def load_settings() -> BridgeSettings:
    send_mode = env("LINKCHATAI_BRIDGE_SEND_MODE", "dry_run")
    if send_mode not in SEND_MODES:
        raise BridgeError(f"unsupported LINKCHATAI_BRIDGE_SEND_MODE={send_mode!r}")

    return BridgeSettings(
        linkchat_base_url=normalize_base_url(env("LINKCHATAI_BASE_URL", "http://linkchatai:8000")),
        crowdmaster_base_url=normalize_base_url(env("CROWDMASTERAI_BASE_URL", "http://host.docker.internal:8004")),
        poll_seconds=max(1.0, float(env("LINKCHATAI_BRIDGE_POLL_SECONDS", "3"))),
        task_limit=max(1, min(int(env("LINKCHATAI_BRIDGE_TASK_LIMIT", "50")), 200)),
        send_mode=send_mode,
        account_device_map=parse_account_device_map(env("LINKCHATAI_BRIDGE_ACCOUNT_DEVICE_MAP", "")),
        device_serials=parse_csv(env("LINKCHATAI_BRIDGE_DEVICE_SERIALS", "")),
        request_timeout=max(1.0, float(env("LINKCHATAI_BRIDGE_REQUEST_TIMEOUT", "8"))),
        scan_douyin=parse_bool(env("LINKCHATAI_BRIDGE_SCAN_DOUYIN", "1")),
        scan_chat_rows_limit=max(1, min(int(env("LINKCHATAI_BRIDGE_SCAN_CHAT_ROWS_LIMIT", "3")), 10)),
        auto_open_douyin=parse_bool(env("LINKCHATAI_BRIDGE_AUTO_OPEN_DOUYIN", "1")),
    )


def env(key: str, default: str) -> str:
    value = os.getenv(key)
    return default if value is None or value.strip() == "" else value.strip()


def normalize_base_url(value: str) -> str:
    return value.rstrip("/")


def parse_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def parse_bool(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def parse_account_device_map(value: str) -> dict[str, str]:
    if not value.strip():
        return {}
    try:
        raw = json.loads(value)
    except json.JSONDecodeError as exc:
        raise BridgeError("LINKCHATAI_BRIDGE_ACCOUNT_DEVICE_MAP must be a JSON object") from exc
    if not isinstance(raw, dict):
        raise BridgeError("LINKCHATAI_BRIDGE_ACCOUNT_DEVICE_MAP must be a JSON object")
    result: dict[str, str] = {}
    for account_handle, serial in raw.items():
        account = str(account_handle).strip()
        device = str(serial).strip()
        if account and device:
            result[account] = device
    return result


class JsonHttpClient:
    def __init__(self, timeout: float) -> None:
        self.timeout = timeout

    def get(self, url: str) -> dict[str, Any]:
        return self.request("GET", url)

    def post(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self.request("POST", url, payload)

    def request(self, method: str, url: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise BridgeError(f"{method} {url} failed with HTTP {exc.code}: {detail}") from exc
        except urllib.error.URLError as exc:
            raise BridgeError(f"{method} {url} failed: {exc.reason}") from exc
        if not body:
            return {}
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError as exc:
            raise BridgeError(f"{method} {url} returned non-JSON response") from exc
        if not isinstance(parsed, dict):
            raise BridgeError(f"{method} {url} returned unexpected JSON")
        return parsed


class LinkChatClient:
    def __init__(self, base_url: str, http: JsonHttpClient) -> None:
        self.base_url = base_url
        self.http = http

    def health(self) -> dict[str, Any]:
        return self.http.get(f"{self.base_url}/health")

    def list_send_tasks(self, limit: int) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode({"limit": limit})
        data = self.http.get(f"{self.base_url}/api/mobile/send-tasks?{query}")
        tasks = data.get("tasks", [])
        if not isinstance(tasks, list):
            raise BridgeError("LinkChatAI returned invalid send task list")
        return [task for task in tasks if isinstance(task, dict)]

    def ack_task(self, task_id: int, *, status: str, channel_message_id: str = "", error: str = "") -> None:
        self.http.post(
            f"{self.base_url}/api/mobile/send-tasks/{task_id}/ack",
            {"status": status, "channel_message_id": channel_message_id, "error": error},
        )

    def post_mobile_message(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.http.post(f"{self.base_url}/api/mobile/messages", payload)


class CrowdMasterClient:
    def __init__(self, base_url: str, http: JsonHttpClient) -> None:
        self.base_url = base_url
        self.http = http

    def health(self) -> dict[str, Any]:
        return self.http.get(f"{self.base_url}/api/health")

    def online_device_serials(self) -> list[str]:
        data = self.http.get(f"{self.base_url}/api/devices/status")
        devices = data.get("devices", [])
        if not isinstance(devices, list):
            return []
        serials: list[str] = []
        for device in devices:
            if not isinstance(device, dict):
                continue
            serial = str(device.get("serial") or "").strip()
            state = str(device.get("state") or "").strip()
            online = bool(device.get("online")) or state == "device"
            allowed = bool(device.get("allowed", True))
            if serial and online and allowed:
                serials.append(serial)
        return serials

    def input_text(self, serial: str, text: str) -> None:
        quoted_serial = urllib.parse.quote(serial, safe="")
        self.http.post(f"{self.base_url}/api/devices/{quoted_serial}/input/text", {"text": text})

    def keyevent(self, serial: str, key: str) -> None:
        quoted_serial = urllib.parse.quote(serial, safe="")
        self.http.post(f"{self.base_url}/api/devices/{quoted_serial}/input/keyevent", {"key": key})

    def tap(self, serial: str, x: int, y: int) -> None:
        quoted_serial = urllib.parse.quote(serial, safe="")
        self.http.post(f"{self.base_url}/api/devices/{quoted_serial}/input/tap", {"x": x, "y": y})

    def current_app(self, serial: str) -> dict[str, Any]:
        quoted_serial = urllib.parse.quote(serial, safe="")
        data = self.http.get(f"{self.base_url}/api/devices/{quoted_serial}/apps/current")
        result = data.get("result")
        return result if isinstance(result, dict) else {}

    def open_app(self, serial: str, package: str) -> None:
        quoted_serial = urllib.parse.quote(serial, safe="")
        self.http.post(f"{self.base_url}/api/devices/{quoted_serial}/apps/open", {"package": package})

    def ui_nodes(self, serial: str) -> list[dict[str, Any]]:
        quoted_serial = urllib.parse.quote(serial, safe="")
        data = self.http.get(f"{self.base_url}/api/devices/{quoted_serial}/ui/nodes")
        result = data.get("result")
        return [node for node in result if isinstance(node, dict)] if isinstance(result, list) else []


DOUYIN_PACKAGES = {"com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite"}
LINKCHAT_BRIDGE_PACKAGES = {"ai.linkchat.bridge", "com.android.vpndialogs", "com.android.settings"}
IGNORED_INBOX_NAMES = {
    "互动消息",
    "陌生人消息",
    "智能助手",
    "豆包",
    "系统通知",
    "服务通知",
    "限时日常",
    "状态设置",
}
IGNORED_CHAT_TEXTS = {
    "发送消息",
    "在线",
    "晚上好",
    "比心",
    "[赞] 赞",
    "[捂脸] 捂脸",
    "[玫瑰] 玫瑰",
}


@dataclass(frozen=True)
class InboxRow:
    customer_name: str
    preview: str
    center_x: int
    center_y: int
    row_index: int
    raw_text: str


@dataclass(frozen=True)
class ChatMessage:
    body: str
    direction: str
    occurrence: int


def stable_token(value: str, length: int = 12) -> str:
    return hashlib.sha1(value.encode("utf-8", errors="ignore")).hexdigest()[:length]


def node_text(node: dict[str, Any]) -> str:
    return str(node.get("text") or "").strip()


def node_desc(node: dict[str, Any]) -> str:
    return str(node.get("content_desc") or "").strip()


def node_label(node: dict[str, Any]) -> str:
    return node_text(node) or node_desc(node)


def node_bounds(node: dict[str, Any]) -> list[int]:
    bounds = node.get("bounds")
    if not isinstance(bounds, list) or len(bounds) != 4:
        return [0, 0, 0, 0]
    result: list[int] = []
    for value in bounds:
        try:
            result.append(int(value))
        except (TypeError, ValueError):
            result.append(0)
    return result


def node_center(node: dict[str, Any]) -> tuple[int, int]:
    try:
        return int(node.get("center_x") or 0), int(node.get("center_y") or 0)
    except (TypeError, ValueError):
        left, top, right, bottom = node_bounds(node)
        return (left + right) // 2, (top + bottom) // 2


def all_visible_labels(nodes: list[dict[str, Any]]) -> list[str]:
    labels: list[str] = []
    for node in nodes:
        for value in (node_text(node), node_desc(node)):
            if value:
                labels.append(value)
    return labels


def is_douyin_foreground(app_info: dict[str, Any]) -> bool:
    return str(app_info.get("package") or "").strip() in DOUYIN_PACKAGES


def is_message_tab(nodes: list[dict[str, Any]]) -> bool:
    labels = all_visible_labels(nodes)
    if not any(label == "消息" for label in labels):
        return False
    return any(label in {"首页", "首页，按钮"} for label in labels) and any(label in {"我", "我，按钮"} for label in labels)


def parse_inbox_row_desc(desc: str) -> tuple[str, str] | None:
    parts = [part.strip() for part in re.split(r"[,，]", desc) if part.strip()]
    if not parts:
        return None
    name = parts[0]
    if name in IGNORED_INBOX_NAMES or name.endswith("消息"):
        return None
    preview_parts = [
        part
        for part in parts[1:]
        if part
        and not part.startswith("未读")
        and not part.endswith("在线")
        and not re.fullmatch(r"\d+分钟内在线\d+分钟前", part)
        and not re.fullmatch(r"\d+分钟前", part)
        and not re.fullmatch(r"\d{1,2}:\d{2}", part)
        and not re.fullmatch(r"\d{1,2}/\d{1,2}", part)
        and not re.fullmatch(r"\d{2}/\d{2}", part)
    ]
    return name, "，".join(preview_parts)


def parse_inbox_rows(nodes: list[dict[str, Any]]) -> list[InboxRow]:
    rows: list[InboxRow] = []
    for node in nodes:
        if str(node.get("class_name") or "") != "android.widget.Button":
            continue
        if not bool(node.get("clickable")):
            continue
        left, top, right, bottom = node_bounds(node)
        if left > 20 or right < 900 or top < 500 or bottom > 2200 or bottom - top < 100 or bottom - top > 260:
            continue
        desc = node_desc(node)
        if "," not in desc and "，" not in desc:
            continue
        parsed = parse_inbox_row_desc(desc)
        if parsed is None:
            continue
        name, preview = parsed
        center_x, center_y = node_center(node)
        rows.append(InboxRow(name, preview, center_x, center_y, len(rows), desc))
    return rows


def is_chat_page(nodes: list[dict[str, Any]]) -> bool:
    labels = all_visible_labels(nodes)
    has_input = any(label == "发送消息" for label in labels)
    has_back = any(label == "返回" for label in labels)
    return has_input and has_back


def chat_order_floor(nodes: list[dict[str, Any]]) -> int:
    orders: list[int] = []
    for node in nodes:
        if node_desc(node) != "返回":
            continue
        _, top, _, bottom = node_bounds(node)
        if top <= 250 and bottom <= 320:
            try:
                orders.append(int(node.get("order") or 0))
            except (TypeError, ValueError):
                orders.append(0)
    return min(orders) if orders else 0


def chat_customer_name(nodes: list[dict[str, Any]], fallback: str = "抖音用户") -> str:
    order_floor = chat_order_floor(nodes)
    candidates: list[tuple[int, str]] = []
    for node in nodes:
        if int(node.get("order") or 0) < order_floor:
            continue
        text = node_text(node)
        if not text or text in {"消息", "在线", "返回"}:
            continue
        left, top, right, bottom = node_bounds(node)
        if 80 <= top <= 220 and 150 <= left <= 760:
            candidates.append((top, text))
    if candidates:
        return sorted(candidates)[0][1]
    return fallback


def is_chat_noise(text: str) -> bool:
    if not text or text in IGNORED_CHAT_TEXTS:
        return True
    if text.startswith("再连续互聊"):
        return True
    if text == "刚刚" or re.fullmatch(r"\d+分钟前", text) or text.startswith("昨天"):
        return True
    if re.fullmatch(r"\d{1,2}:\d{2}", text) or re.fullmatch(r"\d{2}/\d{2}\s+\d{1,2}:\d{2}", text):
        return True
    if re.fullmatch(r"\d{1,2}月\d{1,2}日\d{1,2}时\d{1,2}分", text):
        return True
    if re.fullmatch(r"\d{4}-\d{1,2}-\d{1,2}", text):
        return True
    return False


def parse_chat_messages(nodes: list[dict[str, Any]], customer_name: str) -> list[ChatMessage]:
    order_floor = chat_order_floor(nodes)
    raw_messages: list[tuple[int, str, str]] = []
    for node in nodes:
        if int(node.get("order") or 0) < order_floor:
            continue
        text = node_text(node)
        if is_chat_noise(text) or text == customer_name:
            continue
        left, top, right, bottom = node_bounds(node)
        if top < 300 or bottom > 2100:
            continue
        if right - left > 760:
            continue
        class_name = str(node.get("class_name") or "")
        if "TextView" not in class_name and "DmtTextView" not in class_name:
            continue
        center_x, _ = node_center(node)
        direction = "outbound" if center_x >= 540 else "inbound"
        raw_messages.append((top, direction, text))

    messages: list[ChatMessage] = []
    occurrences: dict[tuple[str, str], int] = {}
    for _, direction, text in sorted(raw_messages):
        key = (direction, text)
        occurrence = occurrences.get(key, 0) + 1
        occurrences[key] = occurrence
        messages.append(ChatMessage(text, direction, occurrence))
    return messages


def account_handle_for_device(serial: str) -> str:
    return f"dy-device-{stable_token(serial, 8)}"


def account_display_name_for_device(serial: str) -> str:
    return f"抖音账号 {stable_token(serial, 4)}"


def conversation_external_id(serial: str, customer_name: str, row_index: int = 0) -> str:
    return f"douyin-ui-chat-{stable_token(serial + ':' + customer_name + ':' + str(row_index), 16)}"


def channel_message_id(serial: str, customer_name: str, message: ChatMessage) -> str:
    key = f"{serial}\n{customer_name}\n{message.direction}\n{message.body}\n{message.occurrence}"
    return f"douyin-ui-message-{stable_token(key, 24)}"


def post_chat_messages(
    *,
    linkchat: LinkChatClient,
    serial: str,
    customer_name: str,
    row_index: int,
    messages: list[ChatMessage],
    posted_ids: set[str],
) -> int:
    posted = 0
    account_handle = account_handle_for_device(serial)
    for message in messages:
        msg_id = channel_message_id(serial, customer_name, message)
        if msg_id in posted_ids:
            continue
        linkchat.post_mobile_message(
            {
                "device_serial": serial,
                "account_handle": account_handle,
                "account_display_name": account_display_name_for_device(serial),
                "conversation_external_id": conversation_external_id(serial, customer_name, row_index),
                "customer_name": customer_name,
                "customer_handle": f"douyin-ui-user-{stable_token(customer_name, 12)}",
                "direction": message.direction,
                "body": message.body,
                "channel_message_id": msg_id,
            }
        )
        posted_ids.add(msg_id)
        posted += 1
    return posted


def scan_open_chat(
    *,
    serial: str,
    linkchat: LinkChatClient,
    nodes: list[dict[str, Any]],
    posted_ids: set[str],
    fallback_customer_name: str = "抖音用户",
    row_index: int = 0,
) -> int:
    customer_name = chat_customer_name(nodes, fallback_customer_name)
    messages = parse_chat_messages(nodes, customer_name)
    if not messages:
        return 0
    return post_chat_messages(
        linkchat=linkchat,
        serial=serial,
        customer_name=customer_name,
        row_index=row_index,
        messages=messages,
        posted_ids=posted_ids,
    )


def scan_douyin_visible_messages(
    *,
    serial: str,
    settings: BridgeSettings,
    linkchat: LinkChatClient,
    crowdmaster: CrowdMasterClient,
    posted_ids: set[str],
) -> None:
    app_info = crowdmaster.current_app(serial)
    if not is_douyin_foreground(app_info):
        if str(app_info.get("package") or "").strip() in LINKCHAT_BRIDGE_PACKAGES:
            return
        if settings.auto_open_douyin:
            crowdmaster.open_app(serial, "com.ss.android.ugc.aweme")
            LOGGER.info("Opened Douyin on device=%s because foreground app was %s", serial, app_info.get("package"))
        return

    nodes = crowdmaster.ui_nodes(serial)
    if is_chat_page(nodes):
        posted = scan_open_chat(serial=serial, linkchat=linkchat, nodes=nodes, posted_ids=posted_ids)
        if posted:
            LOGGER.info("Scanned %s visible Douyin chat messages from device=%s", posted, serial)
        return

    if not is_message_tab(nodes):
        return

    rows = parse_inbox_rows(nodes)[: settings.scan_chat_rows_limit]
    if not rows:
        return
    LOGGER.info("Scanning %s visible Douyin inbox rows on device=%s", len(rows), serial)
    for row in rows:
        try:
            crowdmaster.tap(serial, row.center_x, row.center_y)
            time.sleep(0.9)
            chat_nodes = crowdmaster.ui_nodes(serial)
            if is_chat_page(chat_nodes):
                posted = scan_open_chat(
                    serial=serial,
                    linkchat=linkchat,
                    nodes=chat_nodes,
                    posted_ids=posted_ids,
                    fallback_customer_name=row.customer_name,
                    row_index=row.row_index,
                )
                if posted:
                    LOGGER.info("Synced %s visible messages for Douyin conversation %s", posted, row.customer_name)
            crowdmaster.keyevent(serial, "BACK")
            time.sleep(0.5)
        except BridgeError as exc:
            LOGGER.warning("Failed to scan Douyin row %s on %s: %s", row.customer_name, serial, exc)


def resolve_device_serial(
    task: dict[str, Any],
    *,
    configured_serials: list[str],
    online_serials: list[str],
    account_device_map: dict[str, str],
) -> tuple[str | None, str]:
    task_serial = str(task.get("device_serial") or "").strip()
    if task_serial:
        return task_serial, "task.device_serial"

    account_handle = str(task.get("account_handle") or "").strip()
    mapped_serial = account_device_map.get(account_handle)
    if mapped_serial:
        return mapped_serial, "account map"

    if len(configured_serials) == 1:
        return configured_serials[0], "single configured device"

    if not configured_serials and len(online_serials) == 1:
        return online_serials[0], "single online device"

    return None, "no unique device mapping"


def should_process_task(task: dict[str, Any], processed_task_ids: set[int]) -> bool:
    try:
        task_id = int(task["id"])
    except (KeyError, TypeError, ValueError):
        return False
    return task_id not in processed_task_ids


def process_task(
    task: dict[str, Any],
    *,
    settings: BridgeSettings,
    linkchat: LinkChatClient,
    crowdmaster: CrowdMasterClient,
    online_serials: list[str],
    processed_task_ids: set[int],
) -> None:
    try:
        task_id = int(task["id"])
    except (KeyError, TypeError, ValueError):
        LOGGER.warning("Skipping malformed send task without numeric id: %s", task)
        return

    serial, serial_source = resolve_device_serial(
        task,
        configured_serials=settings.device_serials,
        online_serials=online_serials,
        account_device_map=settings.account_device_map,
    )
    if serial is None:
        LOGGER.info(
            "Task %s is waiting for a device mapping; account=%s conversation=%s",
            task_id,
            task.get("account_handle"),
            task.get("conversation_external_id"),
        )
        return

    body = str(task.get("body") or "")
    if not body.strip():
        linkchat.ack_task(task_id, status="failed", error="empty outbound task body")
        processed_task_ids.add(task_id)
        return

    if settings.send_mode == "dry_run":
        LOGGER.info(
            "Dry run task %s -> device=%s (%s), account=%s, body=%r",
            task_id,
            serial,
            serial_source,
            task.get("account_handle"),
            body[:120],
        )
        processed_task_ids.add(task_id)
        return

    if settings.send_mode == "crowdmaster_text_only":
        crowdmaster.input_text(serial, body)
        LOGGER.info("Task %s text was inserted on device=%s; send confirmation is manual", task_id, serial)
        processed_task_ids.add(task_id)
        return

    if settings.send_mode == "crowdmaster_text_enter":
        crowdmaster.input_text(serial, body)
        crowdmaster.keyevent(serial, "ENTER")
        linkchat.ack_task(task_id, status="sent", channel_message_id=f"crowdmaster:{serial}:{task_id}")
        LOGGER.info("Task %s sent through CrowdMasterAI device=%s", task_id, serial)
        processed_task_ids.add(task_id)
        return

    raise BridgeError(f"unsupported send mode: {settings.send_mode}")


def run_loop(settings: BridgeSettings) -> None:
    http = JsonHttpClient(settings.request_timeout)
    linkchat = LinkChatClient(settings.linkchat_base_url, http)
    crowdmaster = CrowdMasterClient(settings.crowdmaster_base_url, http)
    processed_task_ids: set[int] = set()
    posted_douyin_message_ids: set[str] = set()

    LOGGER.info(
        "Bridge started: linkchat=%s crowdmaster=%s mode=%s poll=%ss",
        settings.linkchat_base_url,
        settings.crowdmaster_base_url,
        settings.send_mode,
        settings.poll_seconds,
    )

    while True:
        try:
            linkchat.health()
            try:
                online_serials = crowdmaster.online_device_serials()
            except BridgeError as exc:
                LOGGER.warning("CrowdMasterAI is not available yet: %s", exc)
                online_serials = []

            tasks = linkchat.list_send_tasks(settings.task_limit)
            for task in tasks:
                if should_process_task(task, processed_task_ids):
                    process_task(
                        task,
                        settings=settings,
                        linkchat=linkchat,
                        crowdmaster=crowdmaster,
                        online_serials=online_serials,
                        processed_task_ids=processed_task_ids,
                    )

            if settings.scan_douyin:
                for serial in online_serials:
                    scan_douyin_visible_messages(
                        serial=serial,
                        settings=settings,
                        linkchat=linkchat,
                        crowdmaster=crowdmaster,
                        posted_ids=posted_douyin_message_ids,
                    )
        except BridgeError as exc:
            LOGGER.warning("Bridge loop failed: %s", exc)
        except Exception:
            LOGGER.exception("Unexpected bridge error")
        time.sleep(settings.poll_seconds)


def main() -> None:
    logging.basicConfig(
        level=env("LINKCHATAI_BRIDGE_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    run_loop(load_settings())


if __name__ == "__main__":
    main()
