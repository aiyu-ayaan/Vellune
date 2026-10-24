# ADR 0005 — androidx.ink renders wet and dry ink in the Android spike

Status: accepted (2026-10-25).

## Context

Phase 1 must prove low-latency ink and palm rejection on Android before the Rust core exists (`TODO.md`). `docs/05` asks for a front-buffered wet layer, a cached dry layer and motion prediction. Writing a front-buffered renderer, brush tessellation and smoothing ourselves would take most of the phase and is not what the spike needs to prove.

`androidx.ink` 1.0.0 (stable) provides `InProgressStrokesView` (front-buffered wet ink, takes `StrokeInput` batches as well as `MotionEvent`s), stock brushes and `CanvasStrokeRenderer` for dry strokes. `androidx.input:input-motionprediction` 1.0.0 provides `MotionEventPredictor`.

## Decision

- The Android spike renders wet ink with `InProgressStrokesView` and dry ink with `CanvasStrokeRenderer`.
- Input arbitration stays ours: `PointerArbiter` in `:core` decides ownership, then feeds the ink view `StrokeInput`s (not `MotionEvent`s), so delayed-commit finger strokes and replayed traces use the same path.
- The stored stroke is ours too: world-space points, time, pressure, tilt, orientation plus brush kind, colour and size. Ink geometry is rebuilt from that on load. Spike storage is JSON; Phase 2 swaps it for the ADR 0004 binary blob without changing the input pipeline.

## Consequences

- Front buffering, brush shapes and prediction come from a maintained library; the spike can focus on arbitration and measurement.
- Stroke data does not depend on ink's serialisation, so the Rust codec and other platforms stay free to choose their renderer.
- Rendering is tied to androidx.ink on Android. If it misses latency targets on test devices, `InkCanvasLayout` is the only class to replace.
- `CanvasStrokeRenderer.draw` expects the canvas to already carry the stroke-to-screen transform; its matrix argument only tunes quality (found during the spike).
