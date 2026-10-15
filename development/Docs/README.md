# Vellune Implementation Notes & Scratchpad

> **Location:** `development/Docs/`  
> **Purpose:** Internal implementation notes, architecture scratchpads, and agent research logs.

---

## Relationship with Root `docs/`

Vellune organizes documentation across two designated directories:

| Directory | Scope & Purpose |
|---|---|
| **[`docs/`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs)** | **Canonical Project Specifications:** High-level system architecture ([`architecture.md`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs/architecture.md)), canonical document data formats ([`document-format.md`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs/document-format.md)), WebSocket sync contracts ([`sync-protocol.md`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs/sync-protocol.md)), security policies ([`security.md`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs/security.md)), and formal ADRs ([`docs/adr/`](file:///media/aiyu/550E85595197DBEC/development/vellune/docs/adr)). |
| **`development/Docs/`** | **Working Implementation Notes:** Deep-dive implementation designs, transient research, benchmarking data, protocol experiments, and developer notes. |
| **[`development/TODO.md`](file:///media/aiyu/550E85595197DBEC/development/vellune/development/TODO.md)** | **Roadmap & Progress:** Active milestones, task checklists, and MVP status tracking (Desktop & Web priority, Android post-MVP). |

---

## Guidelines for Adding Implementation Notes

1. Feel free to create targeted Markdown documents here (e.g., `sqlite-schema-notes.md`, `tauri-ipc-design.md`, `crdt-benchmarks.md`).
2. Keep operational notes, WIP architectural explorations, and code snippets here while features are under development.
3. Once an architecture pattern stabilizes, document the permanent specification in root `docs/` or as an ADR in `docs/adr/`.
