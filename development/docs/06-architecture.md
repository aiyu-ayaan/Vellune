# 06 — System architecture

Working name: **Vellune**. Local-first, self-hosted, collaborative notes with a page editor, an infinite pen-first canvas and a link graph.

## Principles

1. **Local-first.** The device store is the working copy; the server relays and persists. Everything works offline.
2. **Rust core, native shells.** Document model, CRDT, storage, sync client, link index and graph layout are Rust crates shared by server, Android, iOS and web (WASM).
3. **Native input and rendering per platform.** Pen quality is never traded for code sharing.
4. **Derived data is not synced.** Link index, graph, search index are rebuilt locally from documents.
5. **Boring server.** One binary, SQLite or Postgres, no Redis requirement for single node.

## Components

```
              ┌──────────────────────────────────────────┐
              │            vellune-server (axum)         │
              │ auth · workspaces · sync ws · blobs · api│
              │ storage: SQLite|Postgres + fs|S3         │
              └───────────────▲──────────────────────────┘
                              │ WebSocket (binary sync) + HTTPS (REST)
   ┌──────────────────────────┴───────────────────────────┐
   │                      Rust core                       │
   │ model · crdt(yrs) · store(SQLite) · sync client      │
   │ links+graph index · markdown io · search             │
   └───▲───────────────▲──────────────────▲───────────────┘
       │UniFFI         │UniFFI            │wasm-bindgen
  Android (Kotlin)   iOS (Swift)       Web (TS UI)
  Compose + ink      later             later
```

## Workspace layout (planned)

```
vellune/
  Cargo.toml                 # workspace
  crates/
    vellune-model/           # block + canvas element types, schema, ids
    vellune-crdt/            # yrs wrapper: docs, updates, snapshots
    vellune-store/           # local SQLite store, migrations
    vellune-sync/            # wire protocol, client + server halves
    vellune-graph/           # link extraction, backlink index, force layout
    vellune-markdown/        # Markdown/Obsidian vault + JSON Canvas io
    vellune-server/          # axum server binary
    vellune-ffi/             # UniFFI bindings (Kotlin, Swift)
    vellune-wasm/            # wasm-bindgen bindings (later)
  apps/
    android/                 # Kotlin, Compose, native canvas
    web/                     # later
  development/
    docs/  TODO.md
  CLAUDE.md
```

## Technology choices

| Concern | Choice | Why |
|---|---|---|
| CRDT | `yrs` (Yjs-compatible) | Mature Rust implementation, rich-text and map/array types, wire-compatible with the Yjs ecosystem for a future web editor. ADR 0002 compares Loro and Automerge. |
| Local store | SQLite (`rusqlite`) | Same engine on Android, iOS, desktop, server. |
| Server | `axum` + `tokio` | Simple, async, WebSocket support. |
| Server DB | SQLite default, Postgres optional via `sqlx` | Easy self-host first, scale later. |
| Blobs | filesystem default, S3-compatible optional | |
| FFI | UniFFI | Proven by AFFiNE on Android. |
| Auth | email+password and OIDC; sessions or tokens | No payment, no telemetry by default. |
| Search | SQLite FTS5 locally | No separate service. |

## Document hierarchy

- **Workspace**: members, settings, doc index, blobs.
- **Doc**: one CRDT document per page/canvas (same pattern AFFiNE uses per `(workspace, guid)`). A doc has a *page* view (block tree) and an *edgeless* view (canvas elements).
- **Workspace index doc**: a small CRDT doc listing docs (title, tags, timestamps, parent). Cheap to sync first so the doc list appears fast.
- **Blobs**: content-addressed (hash key), uploaded separately.

See `08-data-model-and-storage.md` and `07-sync-and-collaboration.md`.

## Platform shells

Android (v1 target):

- Compose for app chrome, doc list, page editor.
- Custom `View`/`SurfaceView` canvas for pen and edgeless (see `05-pen-and-input-spec.md`).
- Rust via UniFFI on a background dispatcher; UI observes state through flows/callbacks.

Web (later): TypeScript UI over the WASM core; Yjs-compatible so a mature editor binding can be used. iOS (later): Swift + PencilKit-class input, same core.

## Security

- TLS required by default for non-localhost; documented reverse proxy setup.
- Passwords hashed with Argon2id. Per-workspace roles: owner, editor, commenter, viewer.
- Server never interprets doc content except for optional text extraction; E2E encryption is a possible later mode, not v1 (it conflicts with server-side search).
- Input validation on every update size and rate; reject oversized updates.

## Out of scope for v1

AI features, payments/subscriptions, calendar integration, plugin system, real-time audio/video.
