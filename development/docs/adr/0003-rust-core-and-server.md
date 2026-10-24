# ADR 0003 — Rust core, Rust server

Status: accepted (2026-10-25). Requested: build the app in Rust.

## Decision

- Shared logic in Rust crates (`vellune-model`, `-crdt`, `-store`, `-sync`, `-graph`, `-markdown`).
- Server in Rust (`axum`, `tokio`), same crates for sync logic, so the protocol has one implementation.
- Bindings: UniFFI (Kotlin now, Swift later), `wasm-bindgen` for web later.
- Local and default server storage: SQLite. Optional Postgres through a repository trait.

## Consequences

- One toolchain for core and server; cross-compile to Android with `cargo-ndk`.
- FFI boundary must stay coarse (commands in, state/events out) to avoid chatty calls from the UI thread.
- Compile times and Android build complexity are accepted costs; AFFiNE already ships a Rust library on Android this way.
