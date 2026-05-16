# LinkChatAI

LinkChatAI is a Dockerized customer-service console scaffold for managing multiple social accounts from one web page.

The current version includes:

- Account list
- Conversation inbox
- Chat detail panel
- Local reply recording
- Status labels and customer notes
- Seed demo data
- A clean integration boundary for future channel adapters

It does not connect to Douyin, ADB, or any third-party message channel yet.

## Start

```bash
./start.sh
```

Open:

```text
http://127.0.0.1:8002
```

To use another host port:

```bash
LINKCHATAI_HOST_PORT=8012 ./start.sh
```

## Stop

```bash
./stop.sh
```

`stop.sh` pauses the Docker service without deleting the SQLite database under `data/`.

## API

- `GET /health`
- `GET /api/accounts`
- `GET /api/conversations`
- `GET /api/conversations/{conversation_id}/messages`
- `POST /api/conversations/{conversation_id}/messages`
- `PATCH /api/conversations/{conversation_id}`

## Integration Boundary

Future channel-specific code belongs under `app/integrations/`.

The UI and database are intentionally channel-neutral. A real adapter should normalize external events into these internal records:

- account
- conversation
- message
- delivery status
