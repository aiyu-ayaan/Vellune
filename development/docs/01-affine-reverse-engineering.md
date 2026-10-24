# 01 — AFFiNE reverse engineering

Goal: learn what makes AFFiNE good, what it gets wrong, and what Vellune should copy as *behaviour* (not code).

## Method and evidence rules

- AFFiNE is published as a source repository (`toeverything/AFFiNE`, default branch `canary`). The REA skill is for shipped binaries and packages; for a complete source repo the right tool is ordinary repository analysis. REA was **not** used.
- Source was read from a sparse, shallow clone kept outside this repo (scratchpad). Nothing from it is committed here.
- Snapshot date: 2026-10-25. The repo reported about 73.8k stars and a last push on 2026-10-24.
- Every finding is tagged **Observed** (read in source/issue tracker), **Inferred** (reasoned from observed facts) or **Unknown** (not established). Paths are relative to the AFFiNE repo root.
- The published Play Store APK was **not** downloaded or decompiled. See "Unknowns" below.

## 1. Repository layout (Observed)

- Yarn monorepo plus a Rust Cargo workspace (`Cargo.toml`, `rust-toolchain.toml`).
- `packages/frontend/`: `admin`, `apps`, `component`, `core`, `electron-api`, `i18n`, `mobile-native`, `native`, `routes`, `templates`, `track`.
- `packages/frontend/apps/`: `android`, `electron`, `electron-renderer`, `ios`, `mobile`, `mobile-shared`, `web`.
- `packages/backend/`: `server` (NestJS) and `native` (Rust N-API addon).
- `blocksuite/`: `framework` (`global`, `std`, `store`, `sync`), `affine` (editor blocks, graphics, model), plus docs and a playground.

Takeaway: one TypeScript editor core (BlockSuite) is reused on web, Electron, iOS and Android. This is why features land everywhere at once, and also why the mobile pen experience is limited by the web stack.

## 2. Product model (Observed from block and element lists)

- Two views of the same document: **page mode** (block document) and **edgeless mode** (infinite canvas). Blocks live on the canvas as first-class items.
- Block flavours under `blocksuite/affine/blocks`: attachment, bookmark, callout, code, database, data-view, divider, edgeless-text, embed, embed-doc, frame, image, latex, list, note, paragraph, root, surface, surface-ref, table.
- Canvas/graphics modules under `blocksuite/affine/gfx`: brush, connector, group, link, mindmap, note, pointer, shape, template, text, turbo-renderer.
- Canvas element models under `blocksuite/affine/model/src/elements`: brush, connector, group, highlighter, mindmap, shape, text.
- `surface-ref` and `embed-doc` let one doc reference another doc or a canvas region. **Inferred:** this gives AFFiNE a document link graph, though no graph view is exposed.

## 3. Data and sync architecture (Observed)

Client:

- `blocksuite/framework/store` holds a block store on top of Yjs. `blocksuite/framework/sync` has `awareness`, `blob` and `doc` sync modules.
- A Rust crate set (`packages/frontend/native` with `nbstore`, `sqlite_v1`, `disk_sync`) provides local storage for desktop; `packages/frontend/mobile-native` (UniFFI) does the same for iOS/Android.

Server (`packages/backend/server`):

- NestJS 11, Prisma 6, PostgreSQL with `pgvector`, Redis (`ioredis`), `socket.io` 4, GraphQL (Apollo), `yjs` 13.6.
- Doc persistence tables: `Snapshot (workspaceId, guid, blob, size, state, createdAt, updatedAt, createdBy, updatedBy)`, `Update (workspaceId, guid, blob, createdAt, createdBy)` with primary key `(workspaceId, id, createdAt)`, `SnapshotHistory`. Blobs have their own `Blob` table (`workspaceId, key, size, mime, status, ...`) separate from docs.
- Realtime gateway events seen in `src/core/sync/gateway.ts`: `space:join-batch`, `space:leave-batch`, `space:leave`, `space:load-doc`, `space:delete-doc`, `space:doc-lifecycle`, `space:push-doc-update`, `space:load-doc-timestamps`, `space:load-awarenesses`, `space:update-awareness`, and server pushes `space:broadcast-doc-updates`, `space:broadcast-awareness-update`, `space:collect-awareness`, `space:broadcast-doc-invalidation`.
- Plugins: calendar, captcha, copilot (AI), gcloud, indexer, license, oauth, payment, worker.
- Rust workspace dependency `y-octo = "0.1.1"` (a Rust Yjs implementation) and a native backend addon with `doc.rs`, `doc_loader.rs`, `search_index`, `llm`, `permission`, `html_sanitize.rs`, `safe_fetch.rs`.

**Inferred pattern:** append-only update log + periodic merged snapshot per `(workspace, doc)`, with per-workspace rooms for presence. Doc content is opaque CRDT bytes to the database. This is the pattern Vellune adopts (see `07-sync-and-collaboration.md`).

## 4. Pen and touch handling (Observed) — the key weakness

Brush model (`model/src/elements/brush/brush.ts`):

- Points are `[x, y, pressure?]`; pressure exists only when supported. Stroke shape is rendered by turning points into outline points and then an SVG path (`getSolidStrokePoints`, `getSvgPathFromStroke`), cached as a `commands` string. **Inferred:** a perfect-freehand-style outline algorithm.

Brush tool (`gfx/brush/src/brush-tool.ts`):

- Uses DOM `PointerEvent` state only (`e.point`, `e.pressure`, `e.raw.pointerId`).
- Pressure support is detected by *whether pressure values change during a stroke*, because `pointerType === 'pen'` is "not reliable". Pen without pressure falls back to constant width.
- While dragging, the element's `points` and `xywh` are "stashed" locally and popped to the shared doc every 20 points (`BRUSH_POP_GAP = 20`); undo boundary set with `captureSync()` at start/end. **Inferred:** live strokes reach other users in chunks, not per point.
- Shift key snaps to horizontal/vertical straight line.

Pointer pipeline (`framework/std/src/event/control/pointer.ts`):

- Click, drag, pinch and pan controllers. Pinch and pan run only when `pointerType === 'touch'` and need two pointers. Secondary pointers are ignored for clicks.
- The only pen-specific code is `_applyScribblePatch()`: on iPad, if a `pen` drag is active, `touchmove` is `preventDefault`ed to avoid an iOS Safari Scribble bug.

Bounded negative search: grepping `palm`, `pointerType`, `touch-action`, `pressure`, `stylus` across `blocksuite/affine/gfx`, `blocksuite/affine/shared/src` and `blocksuite/framework/std/src` found **no palm-rejection, no stylus-vs-finger arbitration, no pen-only mode, no tilt, no motion prediction, no front-buffer rendering**. Not searched: `packages/frontend/core` UI code.

Public issue tracker (state as of 2026-10-25, all OPEN):

| Issue | Title |
|---|---|
| #13301 | [Feature Request]: [Mobile] Support Drawing on Canvas |
| #13782 | Proper iPad pencil support |
| #14027 | [Feature Request]: (General) Palm Rejection feature? |
| #14187 | iPad: disable touch input while Apple Pencil is in use to prevent accidental taps |
| #14223 | [Bug]: Lag while writing on iPadOS |
| #13812 | [Bug]: Gboard handwriting keyboard unusable |
| #14104 | affine desktop can not capture my wacom tablet input on wayland |

This matches the reported problem: pen input on mobile is an afterthought of a DOM editor.

## 5. Strengths worth matching (behaviour only)

1. Page ⇄ edgeless switch on the same document, with blocks living on the canvas.
2. Local-first with CRDT sync, offline edit, merge without conflicts.
3. Self-hostable server with workspaces, members, invitations, doc-level grants.
4. Real-time presence (awareness) and multi-user editing.
5. Rich canvas element set: shapes, connectors, mind maps, frames, groups, templates.
6. Blob storage separate from doc state; snapshots plus update log keep loads fast.
7. Web, desktop and mobile clients on one data model.

## 6. Weaknesses to avoid

1. Input stack is DOM-only, so no native stylus APIs, no palm rejection, no low-latency path.
2. Mobile wrapped in a WebView; the Android shell needs ~550 lines of IME patching (see `02-affine-android-analysis.md`).
3. Strokes are stored as arrays of numbers inside the CRDT: large, slow to merge, and pushed in 20-point chunks.
4. Very large surface (AI, payments, calendar, licensing) in the server; self-hosting is tied to an enterprise license for the backend (see `11-licensing-and-cleanroom.md`).

## Unknowns

- Behaviour of the shipped Play Store build (may differ from `canary`). Verifying it needs an APK pulled from a device with `adb` and inspected with REA's Android tools. Optional follow-up; not needed for the design decisions here.
- `packages/frontend/core` (React UI) was not read; mobile edgeless UX details live there.
- Actual stroke latency numbers. Not measured.
