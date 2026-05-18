# LinkChatAI

LinkChatAI is a Dockerized aggregated private-message console scaffold for managing multiple social accounts from one web page.

The current version includes:

- Account list
- Conversation inbox
- Chat detail panel
- Mobile message bridge API
- Outbound send-task queue for phone-side workers
- Android notification and accessibility bridge scaffold
- Optional seed demo data
- A clean integration boundary for future channel adapters

It does not implement Douyin client scraping or reverse engineering. Phone-side adapters can push normalized messages into the bridge API and pull outbound send tasks.

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
- `POST /api/mobile/messages`
- `GET /api/mobile/send-tasks`
- `POST /api/mobile/send-tasks/{task_id}/ack`

## Mobile Bridge

Phone-side adapters should normalize Douyin messages and submit them to:

```bash
curl -X POST http://127.0.0.1:8002/api/mobile/messages \
  -H 'Content-Type: application/json' \
  --data '{
    "device_serial": "phone-001",
    "account_handle": "dy-account-01",
    "account_display_name": "抖音号 01",
    "conversation_external_id": "douyin-conversation-001",
    "customer_name": "用户 A",
    "customer_handle": "douyin-user-a",
    "direction": "inbound",
    "body": "你好，我想咨询一下",
    "channel_message_id": "douyin-msg-001"
  }'
```

The webpage creates queued send tasks when the current account sends a reply. Phone-side adapters can poll:

```bash
curl 'http://127.0.0.1:8002/api/mobile/send-tasks?device_serial=phone-001&account_handle=dy-account-01'
```

After the phone-side adapter finishes sending, acknowledge the task:

```bash
curl -X POST http://127.0.0.1:8002/api/mobile/send-tasks/1/ack \
  -H 'Content-Type: application/json' \
  --data '{"status":"sent","channel_message_id":"douyin-sent-001"}'
```

## CrowdMasterAI Bridge

Docker Compose also starts a sidecar bridge worker named `linkchatai-bridge`.

The bridge polls LinkChatAI for queued webpage replies, resolves each task to an Android device, and then sends the action to CrowdMasterAI. By default it runs in `dry_run` mode, so it only logs what it would send and will not touch the phone:

```bash
docker compose logs -f bridge
```

By default the bridge connects to CrowdMasterAI at `http://host.docker.internal:8004`. Override it if your CrowdMasterAI port is different:

```bash
CROWDMASTERAI_BASE_URL=http://host.docker.internal:8004 ./start.sh
```

Useful configuration:

```bash
# Recommended first run: observe tasks only, no phone input.
LINKCHATAI_BRIDGE_SEND_MODE=dry_run ./start.sh

# Map Douyin account handles to CrowdMasterAI device serials.
LINKCHATAI_BRIDGE_ACCOUNT_DEVICE_MAP='{"dy-account-01":"192.168.3.205:5555"}' ./start.sh

# If only one phone is used for testing, force that device.
LINKCHATAI_BRIDGE_DEVICE_SERIALS='192.168.3.205:5555' ./start.sh

# Advanced: type the webpage reply into the focused phone input, but do not press send.
LINKCHATAI_BRIDGE_SEND_MODE=crowdmaster_text_only ./start.sh

# Advanced: type the reply and send ENTER through CrowdMasterAI.
LINKCHATAI_BRIDGE_SEND_MODE=crowdmaster_text_enter ./start.sh
```

`crowdmaster_text_only` and `crowdmaster_text_enter` assume the phone is already on the correct Douyin conversation and the input box is focused. The bridge does not implement Douyin screen scraping, reverse engineering, or anti-risk-control behavior.

## Trial Run

Check that the two services and the Android device bridge are reachable:

```bash
./scripts/check-bridge.sh
```

Create a simulated phone-side Douyin private message:

```bash
./scripts/demo-message.sh
```

If the old scaffold data is cluttering the page, clear it first:

```bash
./scripts/clear-demo-data.sh
```

Then open `http://127.0.0.1:8002`, reply to the new conversation, and watch the bridge:

```bash
docker compose logs -f bridge
```

The first real-device test should use `crowdmaster_text_only`, so the bridge only types into the focused phone input box and does not send:

```bash
LINKCHATAI_BRIDGE_DEVICE_SERIALS='192.168.3.205:5555' \
LINKCHATAI_BRIDGE_SEND_MODE=crowdmaster_text_only \
./start.sh
```

## Android APK

The phone-side APK lives under `android/LinkChatBridge/`. It uses Android's notification listener permission to forward domestic Douyin private-message notifications to LinkChatAI, and an accessibility service to read visible unread rows on the Douyin message tab plus send queued webpage replies through the currently open Douyin chat page. It does not reverse engineer Douyin, inspect encrypted traffic, or bypass platform risk controls.

Build the debug APK:

```bash
./scripts/build-apk.sh
```

The build script defaults to domestic mirrors where possible:

- Gradle distribution: `https://mirrors.cloud.tencent.com/gradle/`
- Android Gradle plugin and Maven dependencies: Aliyun Maven mirrors
- Official Gradle and Google/Maven Central repositories remain as fallback sources

You can override the Gradle mirror if needed:

```bash
GRADLE_DOWNLOAD_URL=https://mirrors.cloud.tencent.com/gradle/gradle-8.10.2-bin.zip ./scripts/build-apk.sh
```

The APK is generated at:

```text
dist/LinkChatBridge-debug.apk
```

Install it to the first phone reported by CrowdMasterAI:

```bash
./scripts/install-apk.sh
```

After the APK opens on the phone:

- Set `LinkChatAI 地址` to the LAN address printed by `./start.sh`, for example `http://192.168.163.175:8002`.
- Set `CrowdMasterAI 设备 serial` to the phone serial, for example `192.168.163.130:5555`.
- Keep `账号标识` aligned with the web account handle, for example `dy-demo-01`.
- Tap `保存配置`.
- Tap `打开通知使用权设置`, then enable `LinkChat Bridge 抖音通知桥接`.
- Tap `打开无障碍设置`, then enable `LinkChat Bridge 抖音回复代发`.
- Tap `发送测试私信到 LinkChatAI` to verify the phone can reach the backend.
- Tap `打开抖音`, keep this phone logged in to the Douyin account represented by `账号标识`.

For a real Douyin private message test:

1. Keep Douyin notifications enabled for that account.
2. Send a private message to this phone's Douyin account from another Douyin account.
3. When the phone receives the Douyin notification, the APK parses the notification title/body and forwards it into LinkChatAI as an inbound message.

The notification bridge only sees notifications that Android exposes. It can receive new message notifications, but it cannot backfill old Douyin chat history from inside the Douyin app.

If Douyin only shows an in-app red dot and does not post an Android notification, keep the Douyin message tab visible. The accessibility bridge reads visible unread conversation rows and forwards the customer name plus latest preview into LinkChatAI.

For a real reply test:

1. In LinkChatAI, open a conversation that came from this phone's Douyin notification.
2. On the phone, open the matching Douyin chat page so the customer's nickname is visible.
3. Send a reply from the webpage.
4. The accessibility bridge polls `/api/mobile/send-tasks`, verifies the visible Douyin page contains that customer nickname, fills the input box, taps `发送`, and acknowledges the send task.

The first reply bridge intentionally requires the matching chat page to be open before sending. If the visible Douyin page does not match the queued task's customer name, the APK leaves the task queued and waits.

## Integration Boundary

Future channel-specific code belongs under `app/integrations/`.

The UI and database are intentionally channel-neutral. A real adapter should normalize external events into these internal records:

- account
- conversation
- message
- delivery status
