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
- [ ] **Database Migrations (`migrations/`):**
  - [ ] Write initial SQLx PostgreSQL schema for `users`, `workspaces`, `pages`, `blocks`, and `document_updates`.
- [ ] **Backend API (`services/api`):**
  - [ ] Implement user authentication and session management.
  - [ ] Implement workspace CRUD with role-based access control (Owner, Editor, Viewer).
  - [ ] Implement page/block CRUD and batch update endpoints.
  - [ ] Implement presigned S3 attachment upload and completion routes.
- [ ] **WebSocket Sync Gateway (`services/sync`):**
  - [ ] Implement WebSocket connection lifecycle, authentication, and heartbeats.
  - [ ] Implement durable sync cursor resume logic (`SyncCursor`).
  - [ ] Implement room-based delta fan-out to active workspace peers.

---

### Milestone 4: Infinite Canvas & Whiteboard MVP
- [ ] Implement canvas rendering surface with smooth pan and zoom in React.
- [ ] Support canvas element types: sticky notes, text blocks, shapes, and embedded page cards.
- [ ] Spatial bounding box queries (`BoundingBox::intersects`) for virtualized viewport rendering.

---

## 🚀 Post-MVP Milestones (Future)

### Phase 2: Native Android Stylus & Inking Engine
- [ ] Kotlin + Jetpack Compose app shell (`apps/android`).
- [ ] Low-latency native Android `MotionEvent` stylus input pipeline.
- [ ] Pressure-sensitive brush rendering (Pen, Pencil, Highlighter, Eraser).
- [ ] Curve smoothing, Bezier interpolation, and palm rejection.
- [ ] Local SQLite integration on Android with background WorkManager sync.

### Phase 3: Advanced Collaboration & Knowledge Features
- [ ] Visual interactive Knowledge Graph view (Force-directed layout).
- [ ] Asynchronous background OCR and handwriting recognition workers.
- [ ] PDF embedding, side-by-side annotation, and export pipelines.
- [ ] Multi-region sync replication and automated snapshot compaction.
