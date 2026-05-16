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


class ConversationPatchPayload(BaseModel):
    status: Literal["open", "pending", "closed"] | None = None
    priority: Literal["low", "normal", "high"] | None = None
    note: str | None = Field(default=None, max_length=1000)


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
    status: str | None = Query(default="all"),
) -> dict[str, object]:
    return {"conversations": storage.list_conversations(account_id=account_id, status=status)}


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


@app.patch("/api/conversations/{conversation_id}")
def api_update_conversation(conversation_id: int, payload: ConversationPatchPayload) -> dict[str, object]:
    conversation = storage.update_conversation(
        conversation_id=conversation_id,
        status=payload.status,
        priority=payload.priority,
        note=payload.note,
    )
    if conversation is None:
        raise HTTPException(status_code=404, detail="Conversation not found")
    return {"conversation": conversation}
