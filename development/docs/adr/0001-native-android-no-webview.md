# ADR 0001 — Native Android, no WebView

Status: accepted (2026-10-25). Requested by the project owner: a proper native app, not a WebView.

## Context

AFFiNE's Android app is a Capacitor WebView hosting a DOM editor. Pen/palm/latency issues are open and the shell needs ~550 lines of IME glue (`02-affine-android-analysis.md`). Pen quality is Vellune's main goal.

## Decision

Android UI is Kotlin + Jetpack Compose + custom canvas views, with a shared Rust core via UniFFI. The Android app contains **no WebView** for editor, canvas, graph or settings. External links open in the system browser.

## Consequences

- We build the block editor, canvas renderer, and graph renderer natively.
- Input handling uses `MotionEvent` directly (`05-pen-and-input-spec.md`).
- Page mode may ship with fewer block types in v1 rather than embedding web content.
- iOS follows the same pattern (SwiftUI + PencilKit-class input) later.
