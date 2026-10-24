# 05 — Pen and input specification

This is the core differentiator. AFFiNE has no stylus/touch arbitration (see `01-affine-reverse-engineering.md` §4). Vellune treats input as a first-class subsystem.

Android API names below are from general platform knowledge and must be verified against current docs when implementing (flagged *verify*).

## Goals

1. Pen draws; fingers navigate. Resting palm or thumb never leaves a mark.
2. Stroke latency low enough to feel like ink on paper (target and measurement method below).
3. Pressure, tilt, hover, barrel button and eraser end supported when hardware provides them.
4. Behaviour is deterministic and testable from recorded input traces.

## Input pipeline

```
MotionEvent ─► InputClassifier ─► PointerArbiter ─► ToolRouter ─► Tool
 (raw, batched   (finger/stylus/    (who owns the     (pen, eraser,   (stroke builder,
  + historical)   eraser/mouse)      gesture; cancel)  lasso, pan…)   select, shape…)
```

Everything before `Tool` is pure Kotlin/Rust logic on plain data (`PointerSample { id, tool, x, y, pressure, tilt, orientation, touchMajor, tMs }`) so it can be unit-tested with synthetic traces.

## InputClassifier

- Use `MotionEvent.getToolType()`: stylus, eraser, finger, mouse *(verify)*.
- Read **all historical samples** in a batched `ACTION_MOVE`, not only the latest, so fast strokes keep their detail.
- Capture pressure, `AXIS_TILT`, `AXIS_ORIENTATION`, `getTouchMajor()` (contact size) and button state.
- Hover events from the stylus (`ACTION_HOVER_*`) mark *pen proximity*.
- Pressure support is decided per device by capability, not by guessing from value changes like AFFiNE does. Fallback to constant width when no pressure axis.

## PointerArbiter (palm rejection and thumb cancellation)

State machine with an explicit owner of the current gesture: `Idle | PenDrawing | FingerPan | FingerPinch | FingerTap`.

Rules, in priority order:

1. **OS cancel wins.** On `ACTION_CANCEL` or a pointer-up carrying the canceled flag *(verify `FLAG_CANCELED`, API level)*, discard that pointer's *uncommitted* stroke. This is how Android signals "that touch was a palm".
2. **Pen priority.** If a stylus is in proximity (hover) or down, all finger/touch pointers are ignored for drawing and for tool taps on the canvas. A touch that starts while the pen is down is dropped entirely (thumb cancellation).
3. **Pen proximity lockout.** For `lockoutMs` after the pen leaves (default 400 ms, tunable) new finger-down events on the canvas do not start gestures. Prevents the wrist landing right after lifting the pen.
4. **Contact-size gate.** A finger with `touchMajor` above `palmMajorThreshold` is a palm, ignored. Threshold calibrated per device density; default derived from display metrics, user-adjustable.
5. **Finger = navigate.** Single finger: pan (when mode "pen only draws") or draw (mode "finger draws too"). Two fingers: pan/zoom/rotate always, on any mode.
6. **Delayed commit for ambiguous first touches.** A finger stroke in "finger draws" mode is buffered for `commitDelayMs` (default 40 ms) or until it moves past a slop distance. If a second pointer, a large contact, or a cancel arrives first, the buffered stroke is dropped. After commit, a later cancel still removes the stroke if it has not been finalized (pen up).
7. **Pointer capture.** The stroke owner pointer id is locked; other pointers cannot steal the tool mid-stroke.

User settings: `Pen only` (default when a stylus is detected), `Pen + finger`, `Finger only` (no stylus devices), toggle for lockout, palm threshold slider.

## Stroke building

- Input: sample list. Output: smoothed polyline with per-point width.
- Smoothing: one-euro or Catmull-Rom style filter chosen in the spike by visual/latency test. Predicted points (from motion prediction *(verify AndroidX Input motion prediction)*) are drawn but **never stored**; they are replaced by real points on the next event.
- Width: function of pressure and speed, per brush.
- On pen up: simplify (Ramer–Douglas–Peucker with tolerance relative to zoom), quantize, delta-encode, and write **one** binary blob to the document (`08-data-model-and-storage.md`). Live strokes for collaborators go through the ephemeral channel (awareness-style), not the CRDT, until pen up.

## Rendering for low latency

Two layers:

1. **Wet ink layer:** the in-progress stroke, drawn through a front-buffered path *(verify AndroidX Graphics front-buffered renderer)* directly to the display with minimal latency.
2. **Dry layer:** committed strokes, cached as vector paths with tile/bitmap caching per viewport; only dirty regions repaint.

On pen up the wet stroke is handed to the dry layer in the same frame to avoid a flicker.

Targets (to be measured, not assumed): touch-to-ink under ~25 ms on a 120 Hz tablet with prediction on; 60 fps pan/zoom with 10k strokes. Measurement: high-speed camera test and `Choreographer`/frame timing logs; record in `docs/benchmarks/` once run.

## Tools (v1)

| Tool | Notes |
|---|---|
| Pen | pressure width, colours, sizes |
| Pencil | texture + tilt shading |
| Highlighter | multiply blend, fixed opacity, flat tip |
| Eraser | stroke eraser and pixel (split stroke) eraser; stylus eraser end and barrel button switch automatically |
| Lasso | select, move, scale, rotate, recolour |
| Shape snap | hold at end of stroke to convert to line/rect/ellipse |
| Ruler / straight-edge | v1.1 |
| Text, sticky, shape, connector, frame, image | canvas elements shared with page-mode blocks |
| Handwriting to text | v2 (evaluate on-device recognition) |

Undo/redo is per user and groups one stroke (or one eraser drag) per step. Multi-finger tap undo/redo optional setting.

## Testing

- **Trace replay:** record `MotionEvent` streams (including cancel events and multi-touch) to files; replay through the pipeline in JVM unit tests. Required traces: palm landing before pen, thumb during stroke, pen lift then wrist, fast scribble with historical samples, hover in/out, device without pressure.
- **Instrumented tests** with synthesized events for the arbiter state machine.
- **Device matrix** (manual checklist): at least one active-pen tablet (e.g. an S Pen device), one USI/MPP-class pen device, one passive-capacitive stylus phone, one device with an unreliable palm cancel.
- Regression guard: arbiter rules have table-driven tests; every bug found on a device becomes a new trace.

## Known gaps

- Behaviour with Android on ChromeOS/desktop pen devices not covered yet.
- E-ink devices (very different refresh model) out of scope for v1.

## Phase 1 spike: findings and structure (2026-10-25)

### Library versions checked (Google Maven, 2026-10-25)

| Library | Version used | Notes |
|---|---|---|
| `androidx.ink:ink-*` | 1.0.0 (stable; 1.1.0-alpha10 exists) | Wet + dry rendering, stock brushes. See ADR 0005. |
| `androidx.input:input-motionprediction` | 1.0.0 | `MotionEventPredictor.newInstance(view)`, `record(event)`, `predict()`. |
| `androidx.graphics:graphics-core` | not used directly (1.0.4 stable, 1.1.0-beta01) | `androidx.ink` brings its own front-buffered path. |

### API names verified (*verify* items above)

- *Observed* (javap on the AARs): `InProgressStrokesView.startStroke(StrokeInput, Brush, strokeToViewTransform)`, `addToStroke(StrokeInputBatch, id, prediction)`, `finishStroke(StrokeInput, id)`, `cancelStroke(id)`, `removeFinishedStrokes(ids)`; `CanvasStrokeRenderer.draw(canvas, stroke, Matrix)`.
- *Observed* (running the spike): `CanvasStrokeRenderer.draw` does not apply the matrix; the canvas must already hold it.
- *Inferred* (platform docs, not yet tested on hardware): `MotionEvent.FLAG_CANCELED` on pointer-up exists from API 33; earlier versions only send `ACTION_CANCEL`. Tool types `TOOL_TYPE_STYLUS` (2) and `TOOL_TYPE_ERASER` (4), `BUTTON_STYLUS_PRIMARY` (32), `AXIS_TILT`, `getOrientation()`.
- *Unknown*: whether `InProgressStrokesView` front-buffers on every API 29+ device; measure per device.

### Android structure

```
apps/android
├─ core  (pure Kotlin, JVM-tested)
│   input/  InputEvent, InputClassifier, PointerArbiter, TraceCodec
│   ink/    StrokeGeometry (eraser hit test)
│   test/resources/traces/*.jsonl  (replay fixtures)
└─ app
    ink/        MotionEventMapper (MotionEvent → InputEvent), Brushes, InkStroke
    ui/canvas/  InkCanvasLayout ─┬─ DryInkView (committed strokes, dot grid, culling)
                                 └─ InProgressStrokesView (wet ink, front buffer)
                CanvasViewModel (strokes, undo/redo, eraser sessions, save)
    data/       CanvasRepository (JSON per canvas), SettingsRepository, TraceRecorder
    ui/…        shell, home, settings, traces (docs/12)

MotionEvent ─► MotionEventMapper ─► TraceRecorder (optional, JSONL)
                     │
                     ▼
              PointerArbiter ─► Gesture ─► InkCanvasLayout
              (:core, pure)                 ├─ StrokeStart/Move/End → InProgressStrokesView
                                            │     + MotionEventPredictor tail (not stored)
                                            ├─ finished stroke → DryInkView + CanvasViewModel
                                            ├─ eraser → CanvasViewModel.eraseAt (StrokeGeometry)
                                            └─ Transform → view matrix → viewport (saved)
```

### What the spike does not cover yet

- Rotation in the two-finger transform (pan and zoom only).
- Pixel eraser, lasso, shape snap, pencil texture.
- Dry-layer bitmap/tile cache: every frame redraws visible strokes; measure with 10k strokes before adding one.
- Traces in `core/src/test/resources/traces` are synthetic. Replace or extend them with device recordings (Settings → Record input traces).
