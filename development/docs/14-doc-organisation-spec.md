# 14 — How docs are organised: behaviour spec

Clean-room inventory of how AFFiNE organises notes, for Phase 4 (doc list, search, links) and Phase 7 (graph). Companion to `13` (page/edgeless/toggle). Source: AFFiNE canary `733d839` (2026-10), `packages/frontend/core/src/{modules,components,desktop}`, read in a sparse clone outside this repo. Behaviour and feature names only; no code, assets or strings copied (`11`).

Tags: **Observed**, **Inferred**, **Unknown**. Scope: **v1**, **later**, **no** (CLAUDE.md rule 7: no AI, payments, calendar in v1).

## 1. "Sub notes" are links, not a hierarchy (Observed)

- There is **no parent/child field on a doc**. A doc's "children" in the sidebar are the docs it **links to** (outgoing references), listed by a search of references from that doc.
- Expanding a doc row shows those linked docs; a user setting turns this on or off ("show linked docs in the sidebar"). When off the row is not collapsible.
- **Drop a doc onto another doc** (tree "make child") and AFFiNE **appends a paragraph containing a link to the dropped doc** at the end of the target doc's first note block. The tree then shows it as a child because the link now exists. Dropping a non-doc item shows a "link docs only" toast; a missing edit permission shows a no-permission toast.
- A doc can therefore be a child of many docs and can link back (cycles are possible). Linked rows are marked as references.
- "Add linked doc" also exists in the doc row menu.
- **Vellune:** keep this model. It matches Obsidian and our derived `links` table (`08`, `09`). Sidebar children = outgoing links, drag-onto-doc appends a wikilink block, and a cycle guard is only needed when rendering the tree (depth cap, visited set). Scope: v1 (Phase 4; needs a simple per-doc link scan before the Phase 7 index).

## 2. Sidebar structure (Observed)

Top buttons: new doc, **All docs**, **Journal**, notifications (cloud accounts only), AI chat (no). Then, in order, four sections: **Favorites**, **Organize**, **Tags**, **Collections**. Footer: **Trash**, invite members, **Template doc entrance**, updater/app download. A workspace switcher and account block sit at the top and bottom.

Vellune v1: new, all docs, journal, favorites, organize (folders), tags, collections, trash, templates. No AI, no notifications until sync/sharing (Phase 6).

## 3. Organize: folders (Observed)

- A node is a **folder**, or a **link** to a doc, tag or collection. Links are leaves; only folders have children; the **root holds only folders**.
- Folders nest. Moving a folder into its own descendant is refused (ancestor check). Order inside a parent uses fractional indexing, so reorder is a single-node update.
- Links are references: the same doc can appear in several folders without being copied. Deleting a link does not delete the doc; deleting a folder removes the folder and its links.
- Folder ops: create, rename, delete, move (drag), add link (drag a doc/tag/collection in).
- Vellune: v1, same model. The folder tree is *synced* workspace data (small, in the index doc), unlike derived indexes.

## 4. Favorites (Observed)

Can favourite **docs, tags, collections and folders**. Shown in a section at the top; quick-favourite action in list rows. v1.

## 5. Tags (Observed)

Tags are workspace-wide options with a **name, a colour and a creation date**; docs carry a list of tag ids (a "tags" property). Create, rename, recolour, delete, add/remove on a doc. A tag page lists its docs. A tag can be placed in Organize or Favorites as a link. v1.

## 6. Collections (Observed)

A collection = **rules (filters) plus a manual allow-list of doc ids**. So it is both a saved search and a hand-picked set; adding docs puts them on the allow-list, removing takes them off. A "pinned collection" concept exists for quick access. Collections open as a doc list with the usual view options. v1 for manual lists; filter rules in Phase 4b.

## 7. Properties and filters (Observed)

Built-in doc properties: tags, created at, updated at, created by, updated by, **primary mode (page or edgeless; this answers the Unknown in `13`: the per-doc mode is a doc property)**, journal date, template flag, edgeless theme, page width. Custom property types: text, number, checkbox, date, tags. Hidden built-ins exist (mode, journal, template, theme, width) and can be shown or hidden per property. Properties show in a doc info panel and as list columns.

Filter operators by type: tags (includes all / any / none, is empty or not), text (is / is not / empty), number (= ≠ > < ≥ ≤), checkbox (is), date (before, after, between, relative this week/month/quarter/year, empty), created/updated by (include), mode, journal, template, page width, edgeless theme (is / is not). Filters also exist for favourite, trash, shared, title and integration type.

Vellune: v1 properties = tags, created, updated, mode, journal date, template flag; custom text/number/checkbox/date after v1. Filter UI in 4b.

## 8. Doc list (Explorer) views (Observed)

- Views: **list, grid, masonry**.
- **Group by** and **order by** any property (tags, created, updated, text, number, date, checkbox, journal, mode and so on; order has a descending flag).
- Per-view display preferences: which properties show, doc icon, doc preview, more-menu, drag handle, and quick actions (favourite, trash, split view, open in tab, select, restore, delete permanently).
- Multi-select with bulk actions.
- Vellune v1: list and grid, sort by updated/created/title, group by tag, bulk select (long-press select), quick favourite/trash. Masonry later.

## 9. Journal and templates (Observed)

- **Journal:** a doc with a date property in `YYYY-MM-DD` form; "today" opens or creates today's doc titled by that date. Journals are browsable by date; a "journal" flag is filterable. Optional journal template. The journal date can be set or removed on any doc.
- **Templates:** a doc flagged as a template. Settings: enable a default page template used for new docs, pick the template doc, pick a journal template. A footer entrance lists templates.
- Vellune v1: journal (local date, no calendar integration), templates for new docs and journals. Rule 7: no calendar sync.

## 10. Search and quick access (Observed)

The quick-search palette mixes sources: docs (title and content), recent docs, tags, collections, journals, commands, links and create-new actions, plus external links. Doc search also powers "references from" for the sidebar tree. Vellune: FTS5 (`08`), recent, docs, tags, commands, create; a full-screen search on Android.

## 11. Trash, import, export, sharing (Observed)

- **Trash:** soft delete with restore and permanent delete (confirm dialog); trashed docs lock the mode switch. No automatic retention was found in a bounded search of `modules` and `desktop` (*Unknown* if the server purges).
- **Import:** Markdown, HTML, Notion export (zip), **Obsidian vault**, Bear backup, zip bundles, snapshots. Vellune v1: Markdown and Obsidian vault (strong fit with wikilinks); others later.
- **Export:** Markdown, HTML, PNG, PDF, snapshot. Vellune v1: Markdown (with wikilinks), PNG/PDF of a canvas region (Phase 5), snapshot later.
- **Sharing / permissions:** doc update permission is checked before edits (toast on denial); workspace settings, properties and delete are permissioned; public share links carry a doc mode. Vellune: Phase 6 (roles per `07`).
- **Workbench:** open in a new tab, split view, per-tab doc, history navigation. Vellune: Android uses a back stack (Phase 4); tablet split view later.

## 12. Plan impact

- Phase 4 gains: all-docs list with views and sorting, favourites, folders, tags, manual collections, journal, templates, properties panel, quick search, trash, Markdown/Obsidian import, sidebar linked children and drag-to-link. Phase 4b (after v0.1): collection filters, group-by, custom properties, masonry.
- Phase 7 reuses the link index for sidebar children and backlinks.
- Data model (`08`) needs: a `folders` node store (folder | link), tag table, doc property map including `primaryMode`, journal date and template flag, collection (rules + allow-list), favourites list, template settings. Update `08` when Phase 2 starts.

## 13. Unknowns

- Mobile layout of the sidebar tree and explorer (touch code not read).
- Server-side trash retention.
- Filter UI details beyond the operator list above.
