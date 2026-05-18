from __future__ import annotations

import json
import logging
import os
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
    )


def env(key: str, default: str) -> str:
    value = os.getenv(key)
    return default if value is None or value.strip() == "" else value.strip()


def normalize_base_url(value: str) -> str:
    return value.rstrip("/")


def parse_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


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
