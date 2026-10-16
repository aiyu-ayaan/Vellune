# Vellune Canonical Document Format

> **Status:** Draft / Specification  
> **Version:** 1.0.0

---

## 1. Overview

Vellune uses a versioned, canonical data model that bridges typed document blocks, infinite whiteboard canvas nodes, and vector ink strokes while preserving high-fidelity Markdown import and export.

---

## 2. Core Entities & Identifiers

All entities use globally unique, cryptographically random **UUIDv4** identifiers that remain stable across renames, moves, and structural reorganizations.

```text
Workspace (Uuid)
 └── Page (Uuid)
      ├── Metadata (title, slug, timestamps)
      ├── Document Hierarchy (Blocks)
      │    ├── Heading / Paragraph / Checklist
      │    ├── CodeBlock / ImageAttachment
      │    └── EmbeddedPage
      └── Canvas Plane (CanvasElements)
           ├── Vector Strokes (Stroke, PenPoint[])
           ├── StickyNotes
           └── Canvas Nodes
```

---

## 3. Block Structure

Documents are ordered sequences of atomic **Blocks**:

```json
{
  "id": "7b8f1a23-45cd-4e89-a123-456789abcdef",
  "page_id": "3f4a9b12-98fe-43dc-ba98-123456789abc",
  "parent_id": null,
  "kind": {
    "type": "Heading",
    "level": 1
  },
  "content": "Project Architecture Overview",
  "order_index": 1.0
}
```

### Supported Block Kinds
- `Paragraph`: Flowing markdown/rich-text content.
- `Heading`: Level 1 through 6 section headers.
- `Checklist`: Interactive todo items with boolean `checked` state.
- `CodeBlock`: Syntax-highlighted code with optional `language` identifier.
- `DrawingBlock`: Inline vector drawing container.
- `ImageAttachment`: Reference to uploaded media attachment by UUID.
- `EmbeddedPage`: Live transclusion of another page by UUID.
- `CanvasNode`: Spatial positioning container (`x`, `y`, `width`, `height`).

The web/desktop client (`packages/ui/src/model.ts`) currently implements these `BlockKind`s, with this Markdown mapping:

| kind | Markdown |
|------|----------|
| `paragraph` | plain text |
| `heading` | `#`–`######` |
| `todo` | `- [ ]` / `- [x]` |
| `bullet` | `- ` |
| `numbered` | `1. ` (every item exports as `1.`; CommonMark renumbers) |
| `toggle` | `+ ` (a CommonMark bullet elsewhere; Vellune reads it back as a toggle) |
| `table` | GFM pipe table; the first row is the header. Block text holds the cells as TSV (rows on lines, cells split by tabs), so edits to different cells merge per character |
| `quote` | `> ` |
| `callout` | Obsidian callout `> [!note] …`; any `[!type]` imports as a callout |
| `code` | fenced block with optional language |
| `divider` | `---` |
| `image` | `![alt](src)` on its own line |

Blocks nest through an optional `indent` (0–6, missing = 0): a block is the child of the nearest block above it with a smaller indent, and may sit at most one level deeper than the block above. A `toggle` folds its children; whether a toggle is folded is a per-device view choice and is not synced. Markdown export indents list items and paragraphs by two spaces per level (a paragraph under a list item is its CommonMark continuation); other kinds export flat and lose their nesting.

An `image` block keeps its alt text in `text` and the picture in `src`, one of:

- `vblob:<blob-id>:<mime>`: a file in the server's encrypted blob store, sealed with the page key (`iv[12] || AES-GCM`). Used whenever the page is in the cloud. Clients fetch `GET /pages/{id}/blobs/{blob-id}` and decrypt locally.
- `data:<mime>;base64,…`: inline, for pages not in the cloud yet (local mode, offline, not yet registered). Capped at 1.5 MB; it stays inline after the page syncs (still E2E-encrypted inside the page updates).

Markdown export writes `src` as-is, so `vblob:` images export as references, not files (known limitation until export bundles attachments).

Pages also carry tree metadata, synced in the page's Yjs `meta` map: `parentId` (containing folder or parent doc; unknown parent = root), `folder` (a sidebar folder: no content, excluded from links, graph, search, and databases), and `order` (manual sibling order). `props` holds typed properties (text, number, checkbox, `YYYY-MM-DD` date) in a separate Yjs map that merges per key, exported as frontmatter scalars (ADR 0004). Block comments live in the Yjs map `comments` (id → `{blockId, parentId?, authorId, authorName, text, createdAt, resolved?}`), so they are encrypted with the page and merge per comment; Markdown export leaves them out. `icon` is an optional emoji shown before the title (exported as frontmatter `icon:`); `mode: "edgeless"` makes a page open on its canvas. `favorite` stays per-device and `seeded` (untouched starter page) is never synced.

Block text holds inline Markdown (`**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, `[label](https://…)`, `[[wikilinks]]`, `#tags`), stored verbatim and rendered by `packages/ui/src/inline.ts`.

---

## 4. Vector Stroke Representation

Inking data is stored as vector strokes rather than raster images, ensuring infinite scalability and lossless zoom.

```json
{
  "id": "e8901234-bcde-4f01-2345-6789abcdef01",
  "color_rgba": 4278190335,
  "base_width": 2.5,
  "tool": "Pen",
  "points": [
    {
      "x": 120.5,
      "y": 340.2,
      "pressure": 0.65,
      "tilt_x": 0.12,
      "tilt_y": -0.05,
      "timestamp_ms": 1729000000123
    }
  ]
}
```

### Pen Tools
- `Pen`: Pressure-sensitive standard ink brush.
- `Pencil`: Textured stroke with tilt sensitivity.
- `Highlighter`: Semi-transparent multiplying overlay.
- `Eraser`: Stroke-level or segment-level removal mask.

---

## 5. Markdown Interoperability & Wikilinks

Vellune maintains full compatibility with Obsidian-style Markdown vaults:

- **Wikilinks:** `[[Note Title]]` and `[[Note Title|Custom Display Label]]` are indexed into bidirectional graph edges.
- **YAML Frontmatter:** Preserved at the top of imported and exported files:
  ```yaml
  ---
  title: Local-First System Design
  tags: [architecture, sync, rust]
  aliases: [System Architecture, Vellune Design]
  ---
  ```
- **Round-Trip Integrity:** Unsupported rich canvas elements are serialized into sidecar metadata or HTML comment blocks (`<!-- vellune-meta: ... -->`) without corrupting readable Markdown text.
