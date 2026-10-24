# ADR 0002 — CRDT library: yrs

Status: proposed (2026-10-25). Confirm after the sync prototype.

## Context

Need a Rust CRDT with rich text, maps, arrays, offline merge and compact updates.

## Options

| | yrs (Yjs port) | Loro | Automerge |
|---|---|---|---|
| Maturity | High; Yjs is widely deployed | Newer, active | Mature, heavier |
| Rich text | Yes | Yes, with strong rich-text semantics | Yes |
| Wire ecosystem | Yjs-compatible editors and providers | Own | Own |
| Tree/movable list | Arrays only (reorder by delete+insert or fractional index) | Native movable tree/list | Lists, no native move |
| Rust-first | Rust port of a JS design | Rust-first | Rust core |

These characterizations are from general knowledge; re-verify versions and features before final acceptance.

## Decision (provisional)

Use `yrs`. Reasons: proven in the same product category (AFFiNE builds on Yjs), Yjs-compatible web editors give a path to a web client, and block reorder can use fractional indexing.

## Consequences

- Fractional indexes for block and element order (`z`, siblings).
- Keep CRDT access behind `vellune-crdt` so a switch to Loro is contained.
- Revisit if movable-tree needs (outline reordering with concurrent edits) prove painful in the prototype.
