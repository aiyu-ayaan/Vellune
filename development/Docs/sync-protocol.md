# Vellune Synchronization Protocol

> **Specification:** Version 1.0  
> **Transport:** WebSocket (`/api/v1/sync`) + HTTP Delta Polling (`/api/v1/pages/{id}/updates`)

---

> **Implemented wire format (v1):** JSON frames on `GET /api/v1/sync`, served by `vellune-api`. See [`api.md` §5](api.md#5-websocket-sync--get-apiv1sync) and [ADR 0002](adr/0002-e2e-encrypted-sync.md).
> - Auth comes from the session cookie on upgrade, so there is no handshake frame. Anonymous sockets may read public pages only.
> - Payloads are AES-GCM-encrypted Yjs updates. The server never decodes them.
> - The cursor is the per-update `seq`. Clients persist the cursor, Yjs state, and unacked ops in IndexedDB (`vellune-cloud`), resubscribe with `after = cursor`, and resend pending ops; `op_id` makes the resends idempotent.
> - The server pings every 30 s and closes after 90 s idle. A client may send at most 300 frames per 10 s. Updates are limited by the admin setting `max_update_bytes`.
> - Local edits are coalesced for 250 ms per page before they are encrypted and pushed.
>
> The handshake-based design below is the original proposal and is kept for history.

## 1. Overview

The Vellune sync engine coordinates conflict-free document and canvas state propagation across offline devices. It follows a **causal log with bounded cursors** model backed by CRDT operations.

---

## 2. Session Lifecycle & Handshake

Connecting clients initiate synchronization via a JSON handshake message over a secure WebSocket connection:

```mermaid
sequenceDiagram
    autonumber
    actor Client as Vellune Client
    participant Gateway as vellune-sync Gateway
    participant DB as PostgreSQL Storage

    Client->>Gateway: ClientSyncMessage::Handshake (client_id, workspace_id, token)
    Gateway->>Gateway: Authenticate & Authorize Workspace
    Gateway-->>Client: ServerSyncMessage::HandshakeAck (session_id, server_version)

    Client->>Gateway: ClientSyncMessage::SubscribePage (page_id, last_cursor)
    Gateway->>DB: Fetch updates > last_cursor
    Gateway-->>Client: ServerSyncMessage::PageUpdates (updates, new_cursor)

    loop Live Collaboration
        Client->>Gateway: ClientSyncMessage::PushUpdates (updates)
        Gateway->>DB: Persist updates atomically
        Gateway-->>Client: ServerSyncMessage::PageUpdates (ack)
        Gateway-->>Client (Peers): Fanout PageUpdates
    end
```

---

## 3. Wire Message Schema

### Client Messages (`ClientSyncMessage`)
- `Handshake`: Establishes protocol compatibility and authentication credentials.
- `SubscribePage`: Requests live subscription to a document or canvas channel with optional catch-up cursor.
- `PushUpdates`: Sends batched CRDT operation deltas to the server.
- `Heartbeat`: Periodic ping to detect stalled TCP connections (interval: 30s).

### Server Messages (`ServerSyncMessage`)
- `HandshakeAck`: Confirms successful handshake and assigns active session ID.
- `PageUpdates`: Streams remote updates with updated monotonic causal cursor.
- `HeartbeatAck`: Echo acknowledgment of client ping.
- `Error`: Standardized error envelope (`code`, `message`).

---

## 4. Conflict Resolution & Idempotency

1. **State-based / Delta-based CRDTs:** Text editing and collaborative collections converge deterministically regardless of message delivery order.
2. **Stable Operation IDs:** Every mutation carries a unique UUID and client clock (`(client_id, sequence)`). Duplicate delivery across network reconnects is dropped idempotently.
3. **Cursor Recovery:** Clients persist the latest acknowledged cursor (`SyncCursor`) in local SQLite. On reconnection after offline periods, clients resume exactly from where they left off.
4. **Durability Guarantee:** Operations are acknowledged to the sending client only after being committed to durable cloud PostgreSQL storage.
