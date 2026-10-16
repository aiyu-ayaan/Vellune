# ADR 0004: Typed page properties for databases

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

AFFiNE and Notion databases are views over typed fields (status, due date, points, done). Vellune's database view only had title, tags and dates, so it could not track work. Obsidian keeps the same information as frontmatter properties, and portability to Markdown is a product principle. ADR 0002 keeps all content end-to-end encrypted, so the server cannot index or validate fields.

## Decision

1. A page has optional `props`: a flat map from name to `string | number | boolean`. The value decides the kind: boolean = checkbox, number, `YYYY-MM-DD` string = date, any other string = text. There is no per-database schema; a text property whose values repeat acts as a select (its values are offered while typing).
2. In the shared Yjs document, properties live in their own top-level map `props` (not inside `meta`), one Yjs key per property, so two people editing different properties merge instead of one overwriting the other.
3. Markdown carries them as top-level frontmatter scalars. Import turns a plain scalar `key: value` into a property (`true`/`false` = boolean, a number = number, quoted = string); lists, maps, block scalars and continued values stay verbatim in `extraFrontmatter`. Export quotes strings that would read back as a number or boolean.
4. The database view shows every property as a sortable, editable column and can group the board by a text or checkbox property; dragging a card between columns sets that property.

## Consequences

- Clients that predate this ignore the `props` map and leave it untouched (the Rust core only writes maps it knows), so properties survive an old Android build.
- No schema means two pages can use one name with different kinds; the database takes the kind of the first page it sees for empty cells and grouping.
- Names `title`, `icon`, `tags` and `aliases` are reserved by the frontmatter mapping and cannot be properties.
- Existing pages whose frontmatter scalars were kept verbatim in `extraFrontmatter` are not migrated; they become properties the next time the file is imported.
