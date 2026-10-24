# 09 — Graph view specification

Feature parity target: Obsidian's graph view (`03-obsidian-features.md`), extended with canvas-aware edges.

## Data source

Built from the derived `links` table (`08-data-model-and-storage.md`), maintained incrementally: on every doc change, re-extract that doc's outgoing links and replace its rows. Never synced; each device rebuilds from its docs.

Edge kinds:

| Kind | Source |
|---|---|
| `wikilink` | inline link attribute in text |
| `embed` | embed-doc block |
| `canvas-embed` | doc-embed element on a canvas |
| `mention` | unlinked title mention (opt-in, computed lazily) |
| `tag` | doc → tag node (opt-in) |

## Views

1. **Global graph**: all docs in the workspace.
2. **Local graph**: active doc plus neighbours to depth N (1–5). Backlinks, outgoing links, or both.
3. **Backlinks panel** (non-graph): linked and unlinked mentions with context snippet.

## Controls

- Filters: text query (reuse search syntax), tags on/off, orphans on/off, canvas docs on/off, attachments on/off.
- Groups: query → colour, ordered; first match wins.
- Display: arrows, node size by degree or by word count, link thickness, label fade by zoom.
- Forces: centre, repel, link strength, link distance. Presets: Compact, Spread, Hierarchy.
- Time-lapse: replay doc creation order.
- Pin/drag nodes; positions saved per user in `graph_layout`.
- Tap node: preview card. Double tap: open. Long press: menu (open, open in canvas, hide, group by).

## Layout engine (Rust core)

- Force-directed simulation with Barnes–Hut approximation for repulsion (O(n log n)), velocity Verlet integration, alpha cooling.
- Deterministic given a seed so tests can assert layout stability.
- Runs on a background thread in chunks; emits position buffers (`f32` x,y pairs) to the UI per frame. UI never recomputes physics.
- Targets (to benchmark): 1k nodes smooth at 60 fps on mid-range Android; 10k nodes interactive with level-of-detail (hide labels, aggregate far clusters).

## Rendering (Android)

- Custom `View` drawing with `Canvas` (or GPU-backed layer) from the position buffer.
- Culling to viewport, label draw only above a zoom threshold, edge batching in one path per colour.
- Pinch zoom and pan use the same arbiter as the canvas (`05-pen-and-input-spec.md`). The graph is read-only navigation in v1; no pen drawing on it.
- Accessibility: node list alternative view for TalkBack.

## Differentiator: canvas-aware graph

An embedded doc inside a canvas frame creates a `canvas-embed` edge, so a whiteboard that gathers ten notes shows up as a hub in the graph. Neither Obsidian's graph nor AFFiNE exposes this.

## Tests

- Link extraction unit tests: wikilinks with alias, heading, rename resilience (target by id).
- Incremental update tests: edit a doc, assert only its rows change.
- Layout determinism and no-NaN property tests.
- Large-vault benchmark generated synthetically.

## Out of scope for v1

3D graph, query-driven graphs (properties/bases), graph editing (creating links by drawing edges).
