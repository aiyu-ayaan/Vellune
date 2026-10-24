# Vellune TODO

Live plan. Design lives in [`docs/`](docs/README.md). Tick items when done and keep this file current in the same commit as the work.

Order is deliberate: the **pen/ink spike comes first** because it is the reason the project exists and the biggest technical unknown.

## Phase 0 — Research and design (done)

- [x] Reverse engineer AFFiNE architecture, sync, pen handling (`docs/01`)
- [x] Analyse AFFiNE Android app (`docs/02`)
- [x] Obsidian feature analysis (`docs/03`)
- [x] Native vs hybrid decision: native, no WebView (`docs/04`, ADR 0001)
- [x] Pen/input spec (`docs/05`), architecture (`docs/06`), sync (`docs/07`), data model (`docs/08`), graph (`docs/09`), self-host (`docs/10`), licensing (`docs/11`)
- [x] ADRs 0001–0004
- [ ] Optional: pull the AFFiNE store APK with `adb` and diff against `canary` source (REA Android tools)
- [ ] ADR 0005: Vellune license
- [ ] ADR: content hash (BLAKE3 vs SHA-256)

## Phase 1 — Pen spike (Android, no Rust yet)

Goal: prove palm rejection and low-latency ink on real devices before building anything else.

- [x] Android project skeleton (Kotlin, Compose, Gradle version catalog, minSdk 26)
- [ ] Check current release notes/versions for AndroidX Graphics, Input (motion prediction), Ink; record findings in `docs/05`
- [ ] Input trace recorder: dump `MotionEvent` streams (incl. historical samples, cancel flags) to files
- [ ] `InputClassifier` + `PointerArbiter` as pure logic with unit tests driven by traces
- [ ] Traces: palm before pen, thumb during stroke, pen lift then wrist, fast scribble, hover in/out, no-pressure device
- [ ] Wet-ink layer (front-buffered) + dry layer (cached vector paths)
- [ ] Motion prediction on/off comparison
- [ ] Pan/zoom/rotate with two fingers, pen-only draw mode
- [ ] Eraser (stylus eraser end + barrel button), stroke eraser
- [ ] Measure latency (high-speed camera) and frame time; write `docs/benchmarks/ink.md`
- [ ] Test on ≥3 devices (active-pen tablet, USI/MPP-class, passive stylus phone)
- [ ] Exit gate: no palm marks in the trace suite; latency and 10k-stroke pan/zoom targets from `docs/05` met or revised

## Phase 2 — Rust core foundations

- [ ] Cargo workspace, toolchain pin, `rustfmt`, `clippy`, `cargo deny`
- [ ] `vellune-model`: ids, block flavours, canvas element kinds, schema version
- [ ] `vellune-crdt`: yrs wrapper, doc open/apply/encode, state vector diff, snapshot
- [ ] Stroke binary codec + property tests (round trip, size benchmark) per ADR 0004
- [ ] `vellune-store`: SQLite schema, migrations, update queue, compaction
- [ ] Fractional index helper for ordering
- [ ] CI: build, test, clippy, deny on Linux
- [ ] `vellune-ffi`: UniFFI proof (open doc, apply update, observe change) running on Android emulator via `cargo-ndk`

## Phase 3 — Server MVP

- [ ] `vellune-server` skeleton (axum), config, `/healthz`, `/api/version`
- [ ] Auth: email+password (Argon2id), sessions/tokens, first-user setup token
- [ ] Workspaces, members, roles, invitation links
- [ ] Sync WebSocket: `hello/join/sync1/sync2/update/awareness` per `docs/07`
- [ ] Persist update log + snapshot + compaction
- [ ] Blob upload/download (fs backend), content-addressed
- [ ] Limits and rate limiting; update validation
- [ ] Multi-client convergence simulation test; decoder fuzzing
- [ ] Docker image + compose file; backup/restore command
- [ ] Postgres repository implementation (after SQLite is solid)

## Phase 4 — Android app v0.1 (local-first notes)

- [ ] App shell: workspace list, doc list, navigation
- [ ] Page editor: paragraph, headings, lists, todo, quote, code, divider (Compose, IME-correct)
- [ ] Inline formats and `[[wikilink]]` insertion with doc picker
- [ ] Local persistence via Rust core; offline only
- [ ] Images/attachments as blobs
- [ ] Trash, tags, search (FTS5)
- [ ] Markdown export/import

## Phase 5 — Canvas (edgeless) on Android

- [ ] Port ink spike into the app on top of the Rust doc model
- [ ] Canvas elements: stroke, shape, text, sticky, image, frame, group, connector
- [ ] Lasso select/transform; shape snap; highlighter/pencil brushes
- [ ] Per-user undo/redo grouped per stroke
- [ ] Page ⇄ canvas toggle on the same doc; doc-embed element
- [ ] Export canvas region to SVG/PNG/PDF

## Phase 6 — Sync in the app

- [ ] Server setup screen, sign-in, workspace attach
- [ ] Background sync with reconnect/backoff; sync status UI
- [ ] Presence: cursors, selections, viewport
- [ ] Live remote strokes over awareness (wet ink for collaborators)
- [ ] Offline edit → reconnect convergence tests on device
- [ ] Sharing UI: invite, roles

## Phase 7 — Graph and links

- [ ] `vellune-graph`: link extraction, incremental `links` table
- [ ] Backlinks panel (linked + unlinked mentions)
- [ ] Force layout (Barnes–Hut), determinism tests
- [ ] Android graph renderer: global + local graph, depth, filters, groups, forces
- [ ] Canvas-embed edges
- [ ] Benchmarks: 1k and 10k nodes
- [ ] JSON Canvas import/export

## Phase 8 — Hardening and release

- [ ] Accessibility pass (TalkBack, large text, graph list view)
- [ ] Crash reporting opt-in only; no default telemetry
- [ ] Version history UI
- [ ] Upgrade/compat tests across protocol versions
- [ ] Security review (auth, permissions, update validation)
- [ ] Docs: user guide, self-hosting guide
- [ ] Beta release (AAB), server release (binary + image)

## Later

- [ ] Web client (WASM core + TS UI, Yjs-compatible editor)
- [ ] iOS (SwiftUI + PencilKit-class input)
- [ ] Desktop client
- [ ] Handwriting to text
- [ ] Databases/tables views, query-driven graph, 3D graph
- [ ] Optional E2E-encrypted workspaces
- [ ] Plugin API

## Open questions

- Yrs vs Loro final call after the sync prototype (ADR 0002)
- Per-doc vs per-workspace sync rooms at scale
- Minimum Android API after evaluating ink/input libraries
- Desktop strategy given the native-first preference
