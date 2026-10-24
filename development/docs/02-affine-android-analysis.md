# 02 — AFFiNE Android app analysis

Source: `packages/frontend/apps/android` in `toeverything/AFFiNE` (canary, 2026-10-25). Source read; the store APK was not decompiled.

## What it is (Observed)

AFFiNE Android is a **hybrid** app: a Capacitor shell hosting the same React/BlockSuite web app used on other platforms.

- `capacitor.config.ts`: `appId: 'app.affine.pro'`, `webDir: 'dist'`, release type `AAB`. `CapacitorHttp` and `CapacitorCookies` disabled.
- `package.json`: `@capacitor/android ^8.4.2`, React 19, `@affine/core`, `@affine/nbstore`, `@blocksuite/affine`, `async-call-rpc`, `idb`.
- Build: `affine bundle` (web build) → `cap sync` → Gradle. The README adds a Rust toolchain requirement (`rust.cargoCommand`) because the app bundles a Rust library.
- Gradle plugins: Android application, Kotlin (Parcelize, serialization, KSP), `rust.android`, Compose, Google services, Firebase Crashlytics, Hilt. Default ABI `arm64-v8a` (or `x86_64` via env). Product flavours `stable`, `beta`, `internal`. Release builds minified.

## Native pieces (Observed)

Hand-written Kotlin in `app/src/main/java/app/affine/pro/` is about 1,164 lines:

| File | Lines | Purpose |
|---|---|---|
| `AffineInputConnection.kt` | 332 | wraps `InputConnection` to fix IME behaviour in the WebView |
| `ImeReplayController.kt` | 217 | replays IME events into the web editor |
| `MainActivity.kt` | 194 | Capacitor `BridgeActivity`, theme + AI button callbacks, trusted-origin check |
| `AffineEditorWebView.kt` | 80 | `CapacitorWebView` subclass; installs a JS IME bridge only on the trusted origin |
| `AFFiNEApp.kt`, `AffineImeBridge.kt`, `AffineImeWebDispatcher.kt`, `AndroidImeState.kt`, `AffineWebViewClient.kt`, `AuthInitializer.kt`, `Constants.kt`, `CapacitorConfig.kt` | small | glue |

Capacitor plugins in `src/plugins`: `auth`, `hashcash`, `preview`, `ai-button`, `nbstore`, `affine-theme`, `mobile-back`.

Rust: `packages/frontend/mobile-native` is a UniFFI crate (`affine_mobile_native`, `cdylib` + `staticlib`) with `storage`, `cache`, `preview`, `payload_codec`, built with Tokio. Generated Kotlin lives in `uniffi/affine_mobile_native/`. It depends on `affine_nbstore` (shared storage). Android-only `affine_preview` adds mermaid/typst rendering.

Tests: only two instrumented tests (`MobileBackDispatcherTest`, `AuthSessionBrokerTest`).

## What is NOT native (Observed, by absence)

- No custom `MotionEvent` handling, no `View.onTouchEvent`, no stylus APIs, no `Ink`/`graphics-core` usage in the Kotlin sources listed above. All canvas input arrives as DOM pointer events inside the WebView.
- The editor, canvas, toolbar, gestures and rendering are all web.

## Inferences

1. The IME bridge (~550 lines across three files) exists because WebView text composition on Android is unreliable for rich editors. Hybrid cost shows up as bespoke native-to-web glue.
2. Pen quality on Android is capped by the WebView: pointer events are coalesced, there is no access to predicted points, no front-buffered rendering, and palm cancellation (`ACTION_CANCEL` / `FLAG_CANCELED`) is only visible to the page indirectly as `pointercancel`.
3. Rust is already used for storage on mobile, so a Rust core shared with a native UI is a proven path (AFFiNE does the storage half of it).

## Native vs hybrid verdict

See `04-android-native-vs-hybrid.md` and ADR 0001. Short version: native Kotlin/Compose UI plus a shared Rust core. AFFiNE shows both halves are realistic: UniFFI + Rust storage works on Android, and the WebView half is where the pen and IME pain lives.

## Unknowns

- Whether the store APK matches canary.
- WebView version floor and performance on low-end devices. Not measured.
- What `packages/frontend/apps/mobile` and `mobile-shared` do for edgeless on touch. Not read.
