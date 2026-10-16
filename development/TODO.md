# Vellune Development Roadmap & Tasks

> **Product Tagline:** Where thoughts take form  
> **MVP Focus Notice:** For the initial Minimum Viable Product (MVP), active development is focused exclusively on the **Desktop application (Tauri 2)** and the **Web application (React + TS)** alongside the Rust core backend services.  
> **Mobile Notice:** Native Android stylus inking (`apps/android`) is scheduled for future post-MVP milestones (Phase 2).

---

## 🎯 MVP Scope & Focus
- **Desktop App (`apps/desktop`):** Tauri 2 + React + TypeScript + Local SQLite database.
- **Web App (`apps/web`):** React + TypeScript with IndexedDB local caching.
- **Shared Rust Core (`crates/*`):** Canonical document model, CRDT synchronization, vector strokes, Markdown & wikilink parser, storage abstractions.
- **Backend Services (`services/*`):** Axum REST API, WebSocket real-time sync gateway, background worker.
- **Infrastructure (`deploy/` & `docker-compose-dev.yml`):** Local development environment with PostgreSQL 16 & Redis 7.

---

## 📋 Task Breakdown

### Milestone 0: Project Skeleton & Workspace Setup [COMPLETED]
- [x] Establish monorepo directory layout (`apps/`, `crates/`, `services/`, `development/`, `tests/`, `deploy/`).
- [x] Configure Rust root workspace (`Cargo.toml`) with all core crates and services.
- [x] Verify `cargo check` compiles with 0 errors across all 11 crates and 3 services.
- [x] Set up pnpm workspace (`pnpm-workspace.yaml`) with `@vellune/web` and `@vellune/desktop`.
- [x] Add root developer commands (`pnpm dev`, `pnpm dev:backend`, `pnpm dev:web`, `pnpm dev:desktop`, `pnpm db:up`).
- [x] Configure `docker-compose-dev.yml` with PostgreSQL 16 and Redis 7 healthchecks and volume persistence.
- [x] Create foundational architecture documentation (now in `development/Docs/`: `architecture.md`, `document-format.md`, `sync-protocol.md`, `security.md`, and ADR 0001).

---

### Milestone 1: Core Document Engine & Local Storage (MVP) [COMPLETED]
- [x] Implement SQLite schema and local persistence layer in `crates/storage` (`SqliteStore`, schema v1 via `PRAGMA user_version`).
- [x] Connect `crates/editor` transaction commands (insert, delete, reorder blocks) with undo/redo history (`Transaction::apply`, `History`).
- [x] Implement Obsidian-style Markdown import and export in `crates/markdown`:
  - [x] Frontmatter parsing and preservation (raw YAML re-emitted verbatim).
  - [x] Bidirectional `[[wikilink]]` extraction and alias resolution (`LinkResolver` in `crates/graph`).
- [x] Implement in-memory (`KnowledgeGraph`) and SQLite-backed (`local_page_links`) backlink graph indexing.
- [x] Unit test document tree mutations, boundary edge cases, and serialization.

**Known limitations (follow-ups):**
- [ ] Frontmatter reader handles only top-level `title`/`tags`/`aliases` with flat lists; swap in a YAML parser for nested values.
- [ ] Inline `#tags` in note bodies are not extracted yet.
- [ ] Wikilinks inside inline code spans are still extracted (fenced code is skipped).
- [ ] Adjacent checklist items export separated by blank lines (renders as a loose list).
- [ ] `save_document` rewrites all blocks of a page; move to per-operation writes with the CRDT/pending-op queue.
- [ ] SQLite journal/WAL settings left at defaults pending benchmarks.

---

### Milestone 2: Desktop & Web Client MVP
- [ ] **Desktop (`apps/desktop`):**
  - [ ] Configure Tauri IPC bridge to interact with local SQLite vault.
  - [ ] Build core desktop app shell: sidebar navigation, note explorer, tabs, search shortcut (Cmd/Ctrl + K).
  - [ ] Integrate block-based text editor with markdown formatting shortcuts.
  - [ ] Display backlinks and forward links panel for active notes.
- [ ] **Web Client (`apps/web`):**
  - [ ] Build responsive browser app shell sharing React components with desktop.
  - [ ] Implement IndexedDB cache fallback for browser offline editing.

---

### Milestone 3: Real-Time Sync & Cloud Backend (MVP)
> Design: end-to-end encrypted Yjs sync, see [ADR 0002](Docs/adr/0002-e2e-encrypted-sync.md) and [`api.md`](Docs/api.md). Blocks are not server-side rows: the server stores only ciphertext.
- [x] **Database Migrations (`migrations/0001_init.sql`):** `users` (with E2E key bundles), `sessions`, `oauth_accounts`, `oauth_providers`, `server_settings`, `pages`, `page_members`, `document_updates`, `audit_events`.
- [x] **Backend API (`services/api`):**
  - [x] Email/password auth with a client-derived `auth_hash` (argon2id at rest), cookie sessions, CSRF header, and login rate limits.
  - [x] GitHub and Google OAuth (code + PKCE, browser-bound state); providers configured from the admin panel.
  - [x] Per-page RBAC (Owner, Editor, Viewer), sharing by email with per-member wrapped keys, and public read-only links.
  - [x] Page registry (create, list, delete); content flows through sync.
  - [ ] Presigned S3 attachment upload and completion routes.
  - [ ] Workspace-level membership and teamspaces (pages are shared individually today).
- [x] **WebSocket Sync Gateway (in `vellune-api`, `/api/v1/sync`):**
  - [x] Connection lifecycle, cookie auth, Origin check, heartbeats, idle timeout, rate limit, kick on revoke/disable.
  - [x] Durable cursor resume (`seq`) and idempotent pushes (`op_id`); acks only after commit.
  - [x] Per-page room fan-out and presence.
- [x] **Clients (`packages/ui/src/cloud`):** WebCrypto E2E, Yjs page model, IndexedDB-persisted docs and pending queue, reconnect with jittered backoff, live presence avatars, sync status.
- [x] **Login / unlock / passphrase screens**, Notion-style **Share** popover, **admin panel** (`/admin`: overview, users, OAuth, settings, audit log).
- [x] Tests: crypto and Yjs merge unit tests (`cloud.test.ts`); a manual end-to-end protocol run and a headless-Chrome UI run (two users, share, live edits) passed on 2026-10-16.

**Known limitations (follow-ups):**
- [ ] Automated API integration tests in CI (needs a Postgres service); the end-to-end runs above were manual.
- [ ] Update-log compaction via client-pushed encrypted snapshots.
- [ ] Page-key rotation on member removal.
- [ ] Account recovery key / passphrase change (re-wrap the private key).
- [ ] Public-key verification UX beyond the fingerprint shown when sharing.
- [ ] Multi-instance fan-out (Redis pub/sub) and distributed rate limits.
- [ ] Desktop (Tauri) sync transport (token-based; the webview origin cannot use the session cookie).
- [ ] Encrypt OAuth client secrets at rest.
- [ ] Edgeless canvas is not read-only for viewers yet; the server rejects their pushes, but their local view can diverge.
- [ ] Newly shared pages appear for the recipient after the next page-list refresh (reconnect or reload), not instantly.

---

### Milestone 3b: Self-Hosted Deploy Package
- [x] **Single image + Compose:** `deploy/Dockerfile`, `deploy/docker-compose.yml`, `deploy/.env.example`, optional Caddy; guide in [`self-hosting.md`](Docs/self-hosting.md). Verified: SPA fallback, `/api`, WebSocket hello on one origin.
- [ ] Commit `Cargo.lock` (currently gitignored) so image builds are reproducible.
- [ ] Publish a pre-built multi-arch image (GHCR) from CI.
- [ ] Deploy-time docs for backup/restore drills and a `pg_dump` cron example.
- [ ] Optional: read `DATABASE_URL`/secrets from `*_FILE` (Docker secrets).

### Milestone 3c: Notion look & feel
- [x] Notion palette, typography, and spacing in light and dark mode, plus a Light / Dark / System toggle (`theme.ts`).
- [x] Notion block colors (9 text and 9 background colors) via the block handle menu, with "Turn into" and Delete.
- [x] Subtle motion (popover pop-in, hover, checkbox) that respects `prefers-reduced-motion`.

### Milestone 3d: AFFiNE editor parity
- [x] AFFiNE-style shell: sidebar (search, journals, favorites, docs, tags, trash), top bar (page/edgeless toggle, more menu, blue Share).
- [x] Live-preview blocks: rendered inline Markdown; clicking a block edits it in place with the caret where you clicked.
- [x] `/` command menu (text, headings 1–4, lists, to-do, quote, callout, code, divider, link to page, new linked page, tag, date/time).
- [x] `[[` page suggestions with "Create page", `#` tag suggestions with "Create tag".
- [x] Numbered list and callout blocks (Markdown round-trip), Enter splits / Backspace merges, Ctrl/Cmd+B/I/E and Ctrl/Cmd+Shift+S formatting, Markdown paste becomes blocks, drag-to-reorder plus Move up/down and Duplicate.
- [x] Collapsible page Info (tags, journal date, created, updated, backlinks, words) and a heading outline rail.
- [x] Code blocks: language picker with search, syntax highlighting (highlight.js, light/dark), copy button; spacing between quote/callout/code.
- [x] Live collaborator cursors and selections with names (E2E-encrypted awareness, Yjs relative positions); caret kept in place during remote edits.
- [x] Version history (Google Docs style): sessions or every edit, change highlights, restore as a new version (ADR 0003).
- [x] Organize tree: folders, nested docs, drag and drop, keyboard tree navigation, Move to…, rename, duplicate.
- [x] Custom right-click menus (sidebar rows, blocks); native menu only in text fields.
- [x] URLs per page: `/doc/{uuid}`, `/doc/{uuid}/edgeless`, `/graph`, `/databases?tag=`, `/trash`, block links `#b-{id}`; Back/Forward work.
- [x] Fixed: second device duplicated starter pages; quick reload could revert the last edit (page cache vs Yjs race).
- [ ] Sidebar collapse button, page icons, inline formatting toolbar on selection, `@` mentions, images/attachments.
- [ ] Share a folder with its subtree; history snapshots before log compaction.

---

### Milestone 4: Infinite Canvas & Whiteboard MVP
- [ ] Implement canvas rendering surface with smooth pan and zoom in React.
- [ ] Support canvas element types: sticky notes, text blocks, shapes, and embedded page cards.
- [ ] Spatial bounding box queries (`BoundingBox::intersects`) for virtualized viewport rendering.

---

### Milestone 5: Brain View — Obsidian-Style Graph (MVP)
- [ ] `crates/graph`: neighborhood query (`neighbors(page, depth)`), orphan and broken-link listing, tag-group membership.
- [ ] Graph data export for UI: nodes (pages, tags, unresolved links) and edges, filterable server-side and locally.
- [ ] **Global graph:** whole-workspace view with zoom, pan, drag, and click-to-open.
- [ ] **Local graph:** N-hop neighborhood panel for the active note, updated live as links change.
- [ ] Force-directed layout in a Web Worker so typing never blocks; Canvas/WebGL renderer.
- [ ] Filters: tags, folders, orphans, attachments, unresolved links, search highlight.
- [ ] Color groups by tag/folder/query; node size by link count; display settings (forces, labels, arrows).
- [ ] Progressive loading and level-of-detail; benchmark with 10k+ nodes before claiming targets.
- [ ] Keyboard and screen-reader access (list fallback of neighbors).

---

### Milestone 6: Vellune MCP Server
Expose a workspace to AI agents via the [Model Context Protocol](https://modelcontextprotocol.io).
- [ ] Evaluate the official Rust SDK (`rmcp`) vs. alternatives; record choice in an ADR.
- [ ] `services/mcp` (`vellune-mcp`) binary:
  - [ ] **stdio transport** for the local desktop vault (works fully offline, reads local SQLite).
  - [ ] **Streamable HTTP transport** for cloud workspaces, authenticated with OAuth and scoped tokens.
- [ ] Tools: `search_pages`, `read_page` (as Markdown), `create_page`, `append_blocks`, `update_block`, `list_backlinks`, `graph_neighbors`, `list_tags`, `get_daily_note`.
- [ ] Resources: `vellune://page/{id}` (Markdown), `vellune://graph/{id}` (local graph JSON).
- [ ] Prompts: summarize page, link suggestions, daily review.
- [ ] Writes go through `crates/editor` transactions so they are undoable and sync like user edits.
- [ ] Security: read-only mode, per-workspace scopes, server-side ACL on every call, payload limits, audit log of agent writes, no note content in logs.
- [ ] Integration tests with an MCP client; document setup for Claude Desktop/Claude Code.

---

## 🚀 Post-MVP Milestones (Future)

### Phase 2: Native Android Stylus & Inking Engine
- [x] Kotlin + Jetpack Compose app shell (`apps/android`): Discord-style sliding panels on phones, docked sidebar and info panel on wide tablets.
- [ ] Low-latency native Android `MotionEvent` stylus input pipeline.
- [ ] Pressure-sensitive brush rendering (Pen, Pencil, Highlighter, Eraser).
- [ ] Curve smoothing, Bezier interpolation, and palm rejection.
- [ ] Local SQLite integration on Android with background WorkManager sync.

### Phase 3: Advanced Collaboration & Knowledge Features
- [ ] Asynchronous background OCR and handwriting recognition workers.
- [ ] PDF embedding, side-by-side annotation, and export pipelines.
- [ ] Multi-region sync replication and automated snapshot compaction.

### Phase 4: AFFiNE Feature Parity
Target: feature parity with [AFFiNE](https://github.com/toeverything/affine). Items already covered by MVP milestones are not repeated.
- [ ] **Page ↔ Edgeless mode:** every doc switchable between document view and whiteboard view of the same blocks.
- [ ] **Rich blocks:** bulleted/numbered/toggle lists, quotes, callouts, dividers, tables, LaTeX math, bookmarks/link previews, embeds (YouTube, Figma, GitHub), attachments, images with captions.
- [ ] **Slash menu and inline toolbar:** ~~`/` block insertion~~ (done, 3d); inline formatting toolbar, `@` mentions for pages and dates.
- [ ] **Linked and synced docs:** inline doc links, embedded linked-doc cards, synced (live-transcluded) blocks.
- [ ] **Databases:** table and kanban views, typed properties (text, number, select, multi-select, date, checkbox, link), filters, sorts, grouping.
- [ ] **Doc properties:** typed page metadata panel, mapped to frontmatter on export.
- [ ] **Organization:** tags, collections (saved filters), favorites, folders, trash with restore.
- [ ] **Journals:** daily notes with calendar navigation (shared with Obsidian daily notes).
- [ ] **Templates:** page and edgeless templates, template picker.
- [ ] **Edgeless tools:** frames, presentation mode, mind maps, connectors with routing, shapes, text, pen/highlighter, sticky notes, grouping, alignment, layers.
- [ ] **Outline panel** and table-of-contents navigation.
- [ ] **Version history:** browse and restore past snapshots per doc.
- [x] **Sharing:** public read-only links, invite members, per-doc permissions (E2E encrypted).
- [ ] **Comments:** inline and block comment threads with resolve.
- [ ] **Import:** Markdown folders, Notion export, HTML, AFFiNE snapshot (where licensing and formats allow).
- [ ] **Export:** Markdown, HTML, PDF, PNG (edgeless), workspace snapshot.
- [ ] **Quick search** (Cmd/Ctrl + K) across docs, commands, and recent items.
- [ ] **Optional AI assistant:** summarize, rewrite, generate mind map; off by default, never required for core editing.
