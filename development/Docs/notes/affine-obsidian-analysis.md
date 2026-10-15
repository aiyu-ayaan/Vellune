# AFFiNE × Obsidian — Feature Analysis for Vellune

> **Status:** Working note · **Date:** 2026-10-16  
> **Purpose:** Decide what Vellune takes from each product, where they conflict, and what the first combined app slice contains.

## Sources

- AFFiNE README — <https://github.com/toeverything/affine> (features, tech stack, MIT license)
- AFFiNE releases — <https://github.com/toeverything/AFFiNE/releases> (latest: `2026.10.12-canary.921`)
- AFFiNE site — <https://affine.pro/>
- AFFiNE 0.27 summary (third-party, unverified against official notes) — <https://blog.elest.io/affine-0-27-new-importers-calendar-view-and-a-sync-warning/>
- AFFiNE 0.25.x summary incl. MCP integration (third-party listing) — <https://apps.umbrel.com/app/affine>
- Obsidian core plugins — <https://obsidian.md/help/plugins>

Items marked *(unverified)* come only from third-party summaries.

---

## 1. AFFiNE

**Model:** one workspace of block documents. Each doc is a Yjs CRDT document, so docs and the edgeless whiteboard are two views of the same blocks.

**Stack:** BlockSuite editor, Yjs on web, `y-octo` (Rust, thread-safe Yjs-compatible CRDT), OctoBase (Rust local-first data engine), Electron desktop, React + Jotai + Vite, napi-rs. TypeScript + Rust. MIT (Community Edition).

| Area | Features |
|---|---|
| Docs | Block editor: rich text, lists, code, LaTeX, Mermaid/Typst *(unverified)*, images, attachments, bookmarks, embeds, linked docs |
| Edgeless | Infinite canvas with any block, shapes, connectors, sticky notes, pen, frames, presentation (slides), mind maps |
| Databases | Multi-view databases (table, kanban; calendar view in 0.27 *(unverified)*), placeable on canvas, filters, grouping |
| Organization | Tags, collections, favorites, journals, templates, trash |
| Collaboration | Real-time sync across clients, sharing, comments, self-host with Docker |
| AI | Drafting, summarizing, outline → slides, article → mind map, workspace Q&A, BYO key *(unverified)* |
| Import | Markdown, Notion, Word, OneNote, Obsidian, Bear *(unverified for latest list)* |
| Integrations | MCP integration *(unverified)* |

**Strengths:** docs and whiteboard share one model; databases; real-time collaboration; Rust CRDT core.  
**Weaknesses (for our goals):** data lives in CRDT binaries, not a plain-file vault. Markdown is only an import/export path. Linking and graph features are weaker than Obsidian's. No native stylus pipeline.

## 2. Obsidian

**Model:** a vault is a folder of plain Markdown files on disk. Links, tags, and properties are indexed from the files. It has a closed-source app with a large plugin API.

Core plugins (29): Audio recorder, Backlinks (linked + unlinked mentions), Bases (property-driven database views), Bookmarks, Canvas, Command palette, Daily notes, File explorer, File recovery (snapshots), Footnotes view, Graph view, Note composer (merge/split), Outgoing links, Outline, Page preview (hover), Properties view, Publish, Quick switcher, Random note, Search, Slash commands, Slides, Sync, Tags view, Templates, Unique note creator, Web viewer, Word count, Workspaces. Team-maintained extras: Importer, Maps.

**Strengths:** portable plain files; `[[wikilinks]]`, aliases, backlinks, unlinked mentions, and graph view; keyboard-first (quick switcher, command palette); extensibility.  
**Weaknesses (for our goals):** no typed block model, so tables and databases are bolted on through Bases and properties. Canvas is a separate `.canvas` file, not a view of the note. No built-in real-time collaboration. Mobile inking needs plugins.

## 3. Comparison and Vellune decision

| Capability | AFFiNE | Obsidian | Vellune choice |
|---|---|---|---|
| Storage of truth | CRDT docs | Markdown files | Canonical block model + CRDT. Markdown is a lossless-as-possible round trip with `vellune-meta` comments (see `../document-format.md`) |
| Doc ↔ whiteboard | Same doc, two modes | Separate `.canvas` file | **AFFiNE way:** each page has a Page view and an Edgeless view |
| Links | Linked docs | Wikilinks, aliases, backlinks, unlinked mentions | **Obsidian way:** `[[wikilinks]]` + aliases + backlinks inside the block editor |
| Graph | — | Global + local graph | **Brain View:** global + local graph, also showing canvas embeds |
| Databases | Multi-view DB blocks | Bases over properties | **Hybrid:** typed page properties (frontmatter-mapped) with table/kanban views |
| Daily notes | Journals | Daily notes | Journals = daily notes (one feature) |
| Search / commands | Quick search | Quick switcher + command palette | One Cmd/Ctrl+K palette for pages, commands, and create-on-miss |
| Collaboration | Real-time | Paid Sync, no live co-editing | Real-time later (Milestone 3), local-first always |
| Ink | Pen on canvas | Plugin | Native ink (Android, Phase 2); pointer-pressure strokes on web canvas now |
| AI access | Built-in AI, MCP *(unverified)* | Plugins | MCP server first (Milestone 6); built-in AI optional, off by default |
| Extensibility | Plugins "coming soon" | Mature plugin API | Deferred; design stable internal commands first |

**Conflicts resolved:**
1. *Files vs. CRDT.* Vellune keeps the block/CRDT model as the source of truth for rich content, as AFFiNE does. It guarantees Markdown export and import that loses no text, which keeps Obsidian's portability. Externally edited files are reconciled; a conflict copy is made when a safe merge is impossible (CLAUDE.md §8).
2. *Canvas as separate file vs. view.* Vellune makes it a view. Card positions live on blocks (`CanvasNode`) and canvas-only items (stickies, strokes) live on the page, so the graph and backlinks see one document.
3. *Bases vs. databases.* Properties are typed page metadata (exported as frontmatter). Database views are queries over properties, so they stay portable.

## 4. First combined app slice (this iteration)

Built as a shared React package (`packages/ui`, `@vellune/ui`) mounted by both `apps/web` and `apps/desktop`. That covers TODO M2's requirement to share components between web and desktop.

In scope:
- Sidebar: all pages, journals (today's daily note), tags, favorites.
- Block editor: Markdown shortcuts (`#`, `-`, `- [ ]`, `>`, ```` ``` ````), Enter/Backspace block flow, `[[` link autocomplete, clickable wikilinks that create missing pages.
- Page properties: tags and aliases, used for link resolution.
- Backlinks panel (linked mentions) and outgoing links.
- Edgeless mode: page blocks as draggable cards, sticky notes, pressure-aware pen strokes, pan/zoom.
- Brain View: global and local force-directed graph, click to open, tag filter.
- Database view: table of all pages with their properties; kanban grouped by tag.
- Cmd/Ctrl+K palette: search pages and commands, create on miss.
- Markdown import/export per page, matching `crates/markdown` rules.
- Local persistence in IndexedDB (browser and Tauri webview).

Explicitly deferred: real-time sync, Tauri SQLite IPC, comments, version history, slides, mind maps, AI, plugins, unlinked mentions, nested block trees.
