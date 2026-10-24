# ADR 0004 — Strokes stored as one binary blob each

Status: accepted (2026-10-25).

## Context

AFFiNE stores brush points as `number[][]` inside the CRDT and pushes them in 20-point chunks while drawing (`01-affine-reverse-engineering.md` §4). That inflates doc size and merge cost.

## Decision

- A stroke is one immutable CRDT map entry with fixed metadata and a `data` byte blob (delta-encoded quantized points, optional zstd).
- In-progress strokes are ephemeral (awareness channel) and are written to the CRDT on pen up.
- Editing a stroke (erase, move) is delete/replace at the element level; move only changes `xywh`/transform, not `data`.

## Consequences

- Smaller docs and faster sync; strokes cannot be merged point-by-point between users (not needed).
- Pixel eraser splits a stroke into new stroke elements.
- Encoding is versioned; unknown versions render as a placeholder.
