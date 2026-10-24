# 08 — Data model and storage

Block tree + canvas element map in one doc, modelled on behaviour seen in BlockSuite (flat block store, `surface` element map) but with our own schema and a compact stroke encoding.

## Doc structure (inside one CRDT doc)

```
doc
├─ meta        Map   {title, createdAt, updatedAt, tags[], icon, parent?}
├─ blocks      Map<blockId, Block>        # flat; tree via children ids
└─ canvas      Map<elementId, Element>    # edgeless elements
```

### Block

```
Block {
  id        BlockId        # ULID-like, client generated
  flavour   enum           # paragraph, heading, list, todo, quote, code, divider,
                           # image, attachment, table, callout, embed-doc, math, database(later)
  props     Map            # flavour specific
  text?     RichText       # yrs text with inline attributes: bold, italic, link, code, color, wikilink{docId}
  children  Array<BlockId>
  parent    BlockId?
}
```

- Order is the `children` array of the parent; one root block per doc.
- Inline `wikilink` attribute stores the target `docId` (not the title) so renames never break links; title is resolved at display time.

### Canvas element

```
Element {
  id      ElementId
  kind    enum      # stroke, shape, text, sticky, connector, frame, group, image, doc-embed
  xywh    [x, y, w, h]  + rotation
  z       fractional index (string) for stable ordering under concurrent edits
  props   Map       # kind specific
}
```

- A canvas element may *host a block subtree* (text, sticky, doc-embed) so page content and canvas content share the editor.
- Connectors store `from/to` as `{elementId, anchor}` or free points.

### Stroke element

Strokes are the largest data. AFFiNE stores `points: number[][]` in the CRDT. Vellune stores one immutable binary blob per stroke:

```
Stroke {
  brush   enum        # pen, pencil, highlighter, marker
  color   u32 RGBA
  width   f32         # base width in canvas units
  data    bytes       # see encoding
}
```

Encoding (draft, versioned):

- Header: version, flags (has pressure, has tilt, has time), point count, origin (x0, y0 as f32).
- Points: delta-encoded varints in quantized units (e.g. 1/16 canvas unit for x/y, 8-bit pressure, 8-bit tilt, delta-time ms).
- Optionally zstd-compressed above a size threshold.
- Bounding box is derived and cached in `xywh`.

Target: a typical 200-point stroke well under 1 KB (to be verified by benchmark).

## Workspace index doc

```
index
├─ docs    Map<docId, {title, tags, createdAt, updatedAt, parent?, trashed?}>
├─ tags    Map<tag, {color}>
└─ settings Map
```

Used for the doc list, sidebar tree, and quick tag filters. Title and tags are mirrored from each doc's `meta` by the client that edits it.

## Derived data (never synced)

Rebuilt locally from docs; stored in the same SQLite file:

- `links(src_doc, src_block, dst_doc, kind)` kinds: wikilink, embed, canvas-embed, mention.
- `tags(doc, tag)`.
- `fts(doc, block, text)` — FTS5 index.
- `graph_layout(doc_id, x, y, pinned)` — cached node positions per user.

## Local SQLite schema (draft)

```sql
CREATE TABLE workspace(id TEXT PRIMARY KEY, name TEXT, server_url TEXT);
CREATE TABLE doc_snapshot(workspace TEXT, doc TEXT, state BLOB, state_vector BLOB, updated_at INTEGER,
                          PRIMARY KEY(workspace, doc));
CREATE TABLE doc_update(workspace TEXT, doc TEXT, seq INTEGER, update_blob BLOB, pushed INTEGER DEFAULT 0,
                        PRIMARY KEY(workspace, doc, seq));
CREATE TABLE blob(workspace TEXT, hash TEXT, size INTEGER, mime TEXT, path TEXT, uploaded INTEGER DEFAULT 0,
                  PRIMARY KEY(workspace, hash));
-- derived: links, tags, fts, graph_layout
```

- `pushed` marks updates not yet acknowledged by the server.
- Compaction: when pending updates exceed N or M bytes, merge into `doc_snapshot` in one transaction.
- Schema migrations are numbered and tested with fixtures from each released version.

## Server schema (draft)

Same shape plus `user`, `session`, `workspace_member`, `invitation`, `doc_grant`, `blob_meta`, `snapshot_history`. Doc content stays opaque bytes. SQLite default; Postgres supported through the same repository trait.

## Blobs

- Key = content hash (BLAKE3 or SHA-256, decided in ADR before first release).
- Upload resumable for large files; dedupe by key; garbage-collect unreferenced blobs after a grace period.

## Import / export

- Markdown folder (vault compatible): one `.md` per doc, wikilinks, YAML frontmatter from `meta`, attachments directory.
- JSON Canvas (`.canvas`) for canvas elements that it can represent.
- Full workspace backup: zip of snapshots + blobs, restorable on any server.
- Strokes export as SVG paths; PNG/PDF export of a canvas region.

## Schema evolution

- Every block/element has a `flavour`/`kind` string; unknown kinds are preserved and rendered as placeholders so older clients do not destroy newer data.
- Document schema version stored in `meta`; migrations run as CRDT transactions by a single elected client per doc (or by the server) to avoid duplicate migration updates.
