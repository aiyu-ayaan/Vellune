# Vellune Documentation

> **Location:** `development/Docs/`  
> **Purpose:** Single home for all Vellune documentation — canonical specifications, architecture decision records, and working implementation notes.

---

## Layout

| Path | Scope & Purpose |
|---|---|
| [`architecture.md`](architecture.md) | **Spec:** System architecture, request lifecycles, component interactions. |
| [`document-format.md`](document-format.md) | **Spec:** Canonical document model, blocks, vector strokes, Markdown round-trip rules. |
| [`sync-protocol.md`](sync-protocol.md) | **Spec:** WebSocket sync wire format, cursors, recovery. |
| [`security.md`](security.md) | **Spec:** Tenant isolation, RBAC, presigned uploads, data protection. |
| [`adr/`](adr) | **Decisions:** Architecture decision records (`NNNN-short-title.md`). |
| `notes/` | **Working notes:** Implementation designs, research, benchmarks, experiments. Create on first use. |
| [`../TODO.md`](../TODO.md) | **Roadmap:** Milestones, task checklists, MVP status. |

---

## Rules

1. Specs (`*.md` at this level) describe the intended contract. Code that changes a contract updates its spec in the same commit.
2. Significant design choices get a new ADR in `adr/`; never rewrite an accepted ADR — supersede it.
3. Transient work (WIP designs, research, benchmark logs) goes in `notes/` (e.g. `notes/tauri-ipc-design.md`).
4. Once a note stabilizes, fold it into the relevant spec or an ADR.
