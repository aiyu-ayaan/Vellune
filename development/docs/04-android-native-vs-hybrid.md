# 04 — Android: native vs hybrid

**Decision: native Android (Kotlin + Jetpack Compose + custom canvas view) on top of a shared Rust core.** Matches the project preference and the evidence. Recorded as ADR 0001.

## Options

| | A. Hybrid (Capacitor/WebView, AFFiNE style) | B. Native UI + Rust core (chosen) | C. Cross-platform UI (Flutter / Compose Multiplatform / Slint) |
|---|---|---|---|
| Pen latency path | Blocked by WebView: pointer events only | Full: `MotionEvent`, historical + predicted points, front-buffer rendering | Partial; depends on the engine's access to platform input and surfaces |
| Palm rejection / thumb cancel | Page sees `pointercancel` at best | Direct `ACTION_CANCEL`, `FLAG_CANCELED`, tool type, contact size | Engine-dependent |
| Text editing / IME | Needs bespoke bridge (AFFiNE: ~550 lines of Kotlin) | Platform text stack | Engine-dependent, often weakest area |
| Reuse of AFFiNE-like web editor | High, but we are not reusing its code (licensing) | None; we build the editor | None |
| Code sharing with iOS/web/desktop | Highest | Core logic shared via UniFFI/WASM; UI per platform | UI shared, but pen quality risk |
| Effort for v1 | Lowest on paper, highest in pen/IME debugging | Highest up front | Medium |
| Fit with "always native" | No | Yes | Partial |

## Why not hybrid

1. The differentiating feature is pen quality. In a WebView you cannot get predicted points, front-buffered rendering or reliable cancel semantics. AFFiNE's open pen/palm issues (#13301, #13782, #14027, #14187, #14223) are the visible result.
2. Hybrid does not remove the native work; it moves it into glue (AFFiNE's IME bridge, auth/back/theme plugins).
3. We cannot reuse the AFFiNE editor anyway (see `11-licensing-and-cleanroom.md`), so hybrid's main benefit is gone.

## Why a Rust core

- AFFiNE already ships Rust storage to Android through UniFFI, so the pattern is proven on this target.
- One implementation of CRDT, storage, sync client, link index and graph layout serves Android, iOS (UniFFI Swift), server (same crates) and web (WASM).
- Native UI stays thin: render, input, platform integration.

## Main risk and mitigation

Risk: a block-based rich-text editor built natively on Compose is a large piece of work (selection, IME composition, inline formats, drag/reorder, CRDT text binding).

Mitigations:

1. Build the **canvas + pen** first as a spike (TODO Phase 1), since it is the reason the project exists and the highest technical unknown.
2. Keep the editor model (blocks, inline attributes, selection) in the Rust core so only rendering and IME glue are Kotlin.
3. **No WebView fallback.** The Android app contains no WebView at any layer. If text editing is slow to build, scope page mode down (fewer block types in v1) rather than embedding web content.

## Android baseline (verify before implementing)

- minSdk 26 matches AFFiNE's documented floor; revisit once we use newer ink/input APIs (stylus and canceled-flag APIs vary by API level).
- Kotlin, Compose, Gradle version catalog, Hilt optional (prefer plain constructor injection for a small app).
- Rust cross-compile with `cargo-ndk`; UniFFI for Kotlin bindings; ABIs `arm64-v8a` (+ `x86_64` for emulator).
- Android input/ink libraries to evaluate in the spike: AndroidX Graphics (front-buffered rendering), AndroidX Input motion prediction, AndroidX Ink. Their API stability and versions must be checked against current release notes at spike time; this document does not assume a version.
