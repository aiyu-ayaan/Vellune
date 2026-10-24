# CLAUDE.md — Vellune

Guide for AI agents (and humans) working in this repository. Keep it short and true; update it when the project changes.

## What this is

**Vellune**: a self-hosted, local-first, collaborative notes app.

- Notion-style block pages **and** an AFFiNE-style infinite canvas ("edgeless") on the same doc.
- First-class **pen support**: palm rejection, thumb cancellation, low-latency ink. This is the main reason the project exists (AFFiNE's mobile pen support is weak; see `development/docs/01`).
- Obsidian-style `[[wikilinks]]`, backlinks and a **graph view**.
- **Rust** core and server. **Native Android** UI (Kotlin + Compose). **No WebView** in the Android app (ADR 0001).

## Status

Design phase complete; no code yet. Current plan: `development/TODO.md` (Phase 1 = Android pen spike). Do not assume any crate or app below exists until you see it in the tree.

## Where things are

| Path | Content |
|---|---|
| `development/docs/README.md` | docs index, start here |
| `development/docs/01..03` | research on AFFiNE, its Android app, Obsidian |
| `development/docs/04..11` | decisions and specs: native Android, pen input, architecture, sync, data model, graph, self-hosting, licensing |
| `development/docs/adr/` | architecture decision records |
| `development/TODO.md` | live task plan |
| `.claude/`, `.agents/` | tool/skill config, not product code |

Planned code layout (from `development/docs/06-architecture.md`): `crates/vellune-{model,crdt,store,sync,graph,markdown,server,ffi,wasm}`, `apps/android`, `apps/web` (later).

## Hard rules

1. **No WebView on Android.** Editor, canvas, graph, settings are native.
2. **Clean room.** Never copy AFFiNE/BlockSuite code, assets or names. AFFiNE's backend is under an Enterprise licence (see `docs/11`). Describe observed *behaviour*; write original code. Keep any clone of AFFiNE outside this repo.
3. **Local-first.** The device store is the working copy; every feature must work offline. Derived data (links, graph, search) is rebuilt locally and never synced.
4. **Pen input is a subsystem, not a feature.** Input classification and palm/thumb arbitration are pure logic with trace-driven tests (`docs/05`). Any input bug gets a new recorded trace as a regression test.
5. **Strokes are binary blobs** (ADR 0004), written to the CRDT on pen up; live strokes use the ephemeral channel.
6. **Decisions change via ADR.** Add or update `development/docs/adr/` in the same change.
7. **No telemetry by default.** No payments, AI or calendar in v1.

## Terms

- **Doc**: one CRDT document; has a *page* view (block tree) and an *edgeless* view (canvas elements).
- **Workspace**: set of docs, members, blobs, plus a small index doc.
- **Wet ink / dry ink**: in-progress stroke (front-buffered, ephemeral) vs committed stroke (cached vector).
- **Arbiter**: the state machine deciding which pointer owns the gesture (pen vs finger vs palm).
- **Awareness**: ephemeral presence data (cursors, live strokes), not persisted.
- **Evidence tags** in research docs: *Observed* (read in source/tracker), *Inferred*, *Unknown*.

## Commands

Not available yet. When code lands, put the real commands here. Expected set:

```
cargo fmt --check && cargo clippy --all-targets -- -D warnings
cargo test --workspace
cargo deny check
cargo run -p vellune-server -- --data ./data
./gradlew -p apps/android assembleDebug testDebugUnitTest
```

Do not document a command here until it works.

## Working conventions

- Rust: edition 2024, `rustfmt` defaults, no `unwrap()` outside tests, errors via `thiserror`, FFI surface coarse-grained (commands in, state/events out).
- Kotlin: Compose, unidirectional state, no business logic in views; CRDT/storage/sync only through the Rust core.
- Tests first for: arbiter rules, stroke codec, sync convergence, link extraction, layout determinism.
- Commits: Conventional Commits (`docs:`, `feat:`, `fix:`, `test:`, `chore:`), small and staged by concern. Update `development/TODO.md` in the same commit that completes an item.
- Prefer the smallest change that solves the task; do not add features, options or abstractions the plan does not call for.

## Verifying claims about other products

AFFiNE is open source: read its source (shallow/sparse clone into a scratch dir, never in this repo) and use the issue tracker via `gh`. For shipped binaries or APKs use the REA tools (`reverse-engineer-anything` skill). Obsidian is closed source: use public docs and the MIT JSON Canvas spec only. Android API names in the docs marked *verify* must be checked against current Android docs before use.

## Known unknowns

See "Unknowns" in `docs/01`, `docs/02` and "Open questions" in `development/TODO.md`.
