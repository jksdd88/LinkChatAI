from __future__ import annotations

from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from app import storage


BASE_DIR = Path(__file__).resolve().parent

app = FastAPI(title="LinkChatAI", version="0.1.0")
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")
templates = Jinja2Templates(directory=BASE_DIR / "templates")


class OutboundMessagePayload(BaseModel):
    body: str = Field(min_length=1, max_length=2000)
    sender_name: str | None = Field(default=None, max_length=40)


class MobileMessagePayload(BaseModel):
    device_serial: str = Field(min_length=1, max_length=120)
    account_handle: str = Field(default="", max_length=120)
    account_display_name: str = Field(default="", max_length=80)
    conversation_external_id: str = Field(min_length=1, max_length=160)
    customer_name: str = Field(min_length=1, max_length=80)
    customer_handle: str = Field(default="", max_length=120)
    direction: Literal["inbound", "outbound", "system"] = "inbound"
    body: str = Field(min_length=1, max_length=4000)
    sender_name: str | None = Field(default=None, max_length=80)
    channel_message_id: str = Field(default="", max_length=180)
    occurred_at: str | None = Field(default=None, max_length=80)


class SendTaskAckPayload(BaseModel):
    status: Literal["queued", "sent", "failed"]
    channel_message_id: str = Field(default="", max_length=180)
    error: str = Field(default="", max_length=1000)


@app.on_event("startup")
def startup() -> None:
    storage.init_db()


@app.get("/", response_class=HTMLResponse)
def index(request: Request) -> HTMLResponse:
    return templates.TemplateResponse("index.html", {"request": request})


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/accounts")
def api_accounts() -> dict[str, object]:
    return {"accounts": storage.list_accounts()}


@app.get("/api/conversations")
def api_conversations(
    account_id: int | None = Query(default=None),
) -> dict[str, object]:
    return {"conversations": storage.list_conversations(account_id=account_id)}


@app.get("/api/conversations/{conversation_id}/messages")
def api_messages(conversation_id: int) -> dict[str, object]:
    conversation = storage.get_conversation(conversation_id)
    if conversation is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    storage.mark_conversation_read(conversation_id)
    conversation = storage.get_conversation(conversation_id)
    return {
        "conversation": conversation,
        "messages": storage.list_messages(conversation_id),
    }


@app.post("/api/conversations/{conversation_id}/messages", status_code=201)
def api_send_message(conversation_id: int, payload: OutboundMessagePayload) -> dict[str, object]:
    body = payload.body.strip()
    sender_name = payload.sender_name.strip() if payload.sender_name else None
    if not body:
        raise HTTPException(status_code=422, detail="Message body cannot be empty")
    message = storage.add_outbound_message(
        conversation_id=conversation_id,
        body=body,
        sender_name=sender_name,
    )
    if message is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return {"message": message}


@app.post("/api/mobile/messages", status_code=201)
def api_mobile_message(payload: MobileMessagePayload) -> dict[str, object]:
    body = payload.body.strip()
    device_serial = payload.device_serial.strip()
    account_handle = payload.account_handle.strip() or storage.auto_mobile_account_handle(device_serial)
    account_display_name = payload.account_display_name.strip() or storage.auto_mobile_account_display_name(account_handle)
    if not body:
        raise HTTPException(status_code=422, detail="Message body cannot be empty")
    return storage.upsert_mobile_message(
        device_serial=device_serial,
        account_handle=account_handle,
        account_display_name=account_display_name,
        conversation_external_id=payload.conversation_external_id.strip(),
        customer_name=payload.customer_name.strip(),
        customer_handle=payload.customer_handle.strip(),
        direction=payload.direction,
        body=body,
        sender_name=payload.sender_name.strip() if payload.sender_name else None,
        channel_message_id=payload.channel_message_id.strip(),
        occurred_at=payload.occurred_at.strip() if payload.occurred_at else None,
    )


@app.get("/api/mobile/send-tasks")
def api_mobile_send_tasks(
    device_serial: str | None = Query(default=None),
    account_handle: str | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
) -> dict[str, object]:
    return {
        "tasks": storage.list_send_tasks(
            device_serial=device_serial.strip() if device_serial else None,
            account_handle=account_handle.strip() if account_handle else None,
            limit=limit,
        )
    }


@app.post("/api/mobile/send-tasks/{task_id}/ack")
def api_mobile_send_task_ack(task_id: int, payload: SendTaskAckPayload) -> dict[str, object]:
    try:
        task = storage.ack_send_task(
            task_id,
            status=payload.status,
            channel_message_id=payload.channel_message_id.strip(),
            error=payload.error.strip(),
        )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    if task is None:
        raise HTTPException(status_code=404, detail="Send task not found")
    return {"task": task}
