# 03 — Obsidian features to adopt

Obsidian is closed source, so this is feature analysis from official documentation and public specs, not reverse engineering. Sources: [Graph view help](https://obsidian.md/help/Plugins/Graph+view), [JSON Canvas](https://obsidian.md/blog/json-canvas/) and [jsoncanvas.org](https://jsoncanvas.org). Items marked *(background)* come from general knowledge of Obsidian, not these pages; verify before relying on them.

## Graph view (documented)

- **Global graph:** every note as a node, every link as an edge.
- **Local graph:** only notes connected to the active note. Depth slider; each level adds notes linked to the previous level. Same settings as global.
- **Groups:** a search query (full search syntax) plus a colour; matching nodes take the colour.
- **Display:** arrows for link direction, text fade threshold, node size, link thickness.
- **Forces:** centre force, repel force, link force, link distance. Higher centre force makes a more circular layout.
- **Time-lapse:** notes appear in creation order.
- Filters *(background)*: search filter, tags toggle, attachments toggle, existing-files-only, orphans toggle.

## Linking model *(background)*

- `[[Note]]`, `[[Note|alias]]`, `[[Note#Heading]]`, `[[Note#^block]]` wikilinks; Markdown links; embeds `![[Note]]`.
- Backlinks panel (linked + unlinked mentions) and outgoing links panel.
- Tags, properties (YAML frontmatter), search operators.

## Canvas / JSON Canvas (documented)

- Open, MIT-licensed format, spec v1.0. Conservative by design. Useful as an **import/export** format for Vellune canvases: nodes (text, file, link, group) and edges.
- Vellune's canvas is richer (pen strokes, shapes). Export maps what JSON Canvas supports and drops or embeds the rest; the pen layer exports as SVG/PNG attachment.

## Plugin-level ideas (documented)

- 3D force-directed graph plugins and "Base Graph" show demand for graph views driven by queries (properties, link types) and hierarchy layouts. Treat as post-v1.

## What Vellune takes

1. Wikilink syntax and a link index that powers backlinks, unlinked mentions and the graph.
2. Global and local graph with depth, groups, forces, filters, orphans, tags.
3. Markdown import/export of a workspace as a folder of `.md` files (vault compatible), so users are not locked in.
4. JSON Canvas import/export.
5. Graph includes canvas-to-doc references (embedded docs, linked frames), which Obsidian's graph does not cover well. This is Vellune's differentiator over both products.

## What Vellune does not take (v1)

- Plugin ecosystem, themes marketplace, Publish/Sync services.
- Plain-files-as-source-of-truth. Vellune stores CRDT state; Markdown is an export/import projection.

See `09-graph-view-spec.md` for the design.
