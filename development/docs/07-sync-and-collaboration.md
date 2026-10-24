# 07 — Sync and collaboration

Pattern adopted from AFFiNE's observed design (update log + snapshot per doc, rooms per workspace, awareness for presence), re-implemented independently. See `01-affine-reverse-engineering.md` §3.

## Model

- Each doc is a `yrs` document. Clients exchange **updates** (binary, mergeable, order-independent) and compare **state vectors** to compute diffs.
- Local store is authoritative for the user. Offline edits are queued as updates and pushed on reconnect.
- Server stores per doc: an append-only **update log** and a periodic **snapshot** (merged state + state vector). Compaction merges the log into the snapshot and truncates it.

## Transport

- One WebSocket per client per workspace, binary frames (length-prefixed, versioned header). HTTPS REST for auth, workspace admin, blob upload/download.
- Message types (draft):

| Message | Direction | Purpose |
|---|---|---|
| `hello {protocol, token}` | C→S | version + auth |
| `join {workspace}` / `leave` | C→S | subscribe to a workspace room |
| `sync1 {doc, stateVector}` | both | "here is what I have" |
| `sync2 {doc, update}` | both | diff reply |
| `update {doc, update}` | both | live edit |
| `doc-list {since}` | C→S, reply | incremental workspace index |
| `awareness {doc, payload}` | both | cursors, selections, live pen strokes (ephemeral) |
| `ack / error` | S→C | delivery and rejection (permission, size) |

- Heartbeat ping/pong; exponential backoff reconnect with jitter.
- Protocol version in header; server rejects unknown major versions with a clear error.

## Sync flow for a doc

1. Client opens doc locally from SQLite (instant).
2. Client sends `sync1` with its state vector; server replies `sync2` with missing updates and its own `sync1`.
3. Client sends what the server lacks.
4. Live: every local transaction produces an `update`; server persists then broadcasts to other members of the room.
5. Persist-before-ack: server acks an update only after it is durable.

## Presence and live ink

- Cursor/selection/viewport go through awareness (ephemeral, not persisted).
- **Live strokes:** while a collaborator is drawing, their in-progress stroke is broadcast as small ephemeral point batches over awareness and rendered as a wet stroke. Only when the pen lifts is the final compact stroke blob written to the CRDT. This avoids AFFiNE-style repeated 20-point writes into the shared doc.

## Permissions

- Roles per workspace: owner, editor, commenter, viewer; optional per-doc grants.
- Server checks role on `join` and on every `update`. A viewer's update is rejected with an error, never applied.
- Auth tokens are short-lived; the WebSocket re-validates on refresh.

## Limits and abuse protection

- Max update size, max updates per second per connection, max doc size; configurable.
- Server validates that an update decodes before storing it.

## Conflict behaviour

- Text, lists, maps: CRDT merge.
- Stroke blobs are immutable once written; erase = delete element or write a replacement element (stroke split), never in-place edit.
- Deleting a doc is a tombstone in the workspace index first; hard delete after retention period.

## Version history

- Periodic snapshots kept as history (retention configurable). Restore = create a new update that sets state to the snapshot.

## Testing

- Deterministic multi-client simulation: N clients, random edit scripts, random disconnects, assert convergence.
- Fuzz the update decoder with malformed input.
- Replay tests of recorded sessions against compaction.

## Open questions

- Per-doc rooms vs per-workspace rooms for very large workspaces.
- Whether the workspace index doc should be sharded after N docs.
