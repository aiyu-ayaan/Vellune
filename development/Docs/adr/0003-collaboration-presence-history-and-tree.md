# ADR 0003: Live cursors, version history, and the page tree inside the E2E model

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Users asked for Google Docs-style collaboration (see each other's cursors, browse and restore versions), an AFFiNE-style folder tree with drag and drop, and stable per-page URLs. ADR 0002 makes the server blind to content, so none of this may require the server to read pages.

## Decision

1. **Cursors** travel as `awareness` WebSocket frames encrypted with the page key. Positions are Yjs relative positions, so a caret stays on its character while others type. The server relays them only between page members, caps them at 1 KiB, and never stores them.
2. **Version history** reuses the existing durable update log. `GET /pages/{id}/history` returns ciphertext plus author and timestamp; the client decrypts, groups edits into sessions, rebuilds any state with Yjs, and restores by applying the old state as a new edit. Nothing is ever rewritten, so a restore can itself be undone.
3. **Folders are pages** with `folder: true`, nested through `parentId` and ordered by `order`. They sync and encrypt like any page, so no new server table or plaintext folder names are needed.
4. **URLs** are `/doc/{uuid}` (plus `/edgeless`), `/graph`, `/databases?tag=`, `/trash`; block links add `#b-{blockId}`.

## Consequences

- Author and edit time are visible to the server (they always were, in `document_updates`); content is not.
- History depends on the full update log. Future log compaction must keep periodic encrypted snapshots or history granularity is lost.
- Sharing a folder does not share its children; each page is shared individually. A shared page whose folder is not shared appears at the recipient's root.
- Remote cursors reveal which block someone is in to other members only, never to the server.
