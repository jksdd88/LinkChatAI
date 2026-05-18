from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class OutboundMessage:
    account_id: int
    conversation_id: int
    text: str
    sender_name: str


class ChannelAdapter(Protocol):
    """Boundary for future message channels.

    The initial scaffold stores outbound messages locally. A real adapter can
    later send normalized messages through a phone-side adapter and return a
    delivery result without changing the web UI.
    """

    name: str

    def send_text(self, message: OutboundMessage) -> str:
        """Send text and return a channel delivery status."""
        raise NotImplementedError


class LocalOnlyAdapter:
    name = "local_only"

    def send_text(self, message: OutboundMessage) -> str:
        return "local_only"
