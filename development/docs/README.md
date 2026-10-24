# Vellune documentation

Vellune: a self-hosted, local-first, collaborative notes app with Notion-style pages, an AFFiNE-style infinite canvas with first-class pen support, and Obsidian-style links and graph. Native Android first, Rust core and server.

## Reading order

| Doc | Content |
|---|---|
| [01 AFFiNE reverse engineering](01-affine-reverse-engineering.md) | architecture, sync model, pen/touch findings, issue evidence |
| [02 AFFiNE Android analysis](02-affine-android-analysis.md) | Capacitor shell, native pieces, what is missing |
| [03 Obsidian features](03-obsidian-features.md) | graph view, linking, JSON Canvas |
| [04 Android native vs hybrid](04-android-native-vs-hybrid.md) | decision and rationale |
| [05 Pen and input spec](05-pen-and-input-spec.md) | palm rejection, thumb cancel, latency, tools, tests |
| [06 Architecture](06-architecture.md) | components, crates, tech choices |
| [07 Sync and collaboration](07-sync-and-collaboration.md) | protocol, presence, permissions |
| [08 Data model and storage](08-data-model-and-storage.md) | doc schema, stroke encoding, SQLite |
| [09 Graph view spec](09-graph-view-spec.md) | links index, layout, rendering |
| [10 Self-hosting](10-self-hosting.md) | deployment, config, ops |
| [11 Licensing and clean-room](11-licensing-and-cleanroom.md) | what we may and may not reuse |
| [12 Android design system](12-android-design-system.md) | Material 3 Expressive tokens, screens, accessibility |
| [13 Page and edgeless feature spec](13-page-and-edgeless-feature-spec.md) | page-mode blocks, slash menu, edgeless tools, the page ⇄ edgeless toggle |
| [14 Doc organisation spec](14-doc-organisation-spec.md) | sub-docs as links, folders, tags, collections, properties, journal, templates, import/export |
| [ADRs](adr/) | recorded decisions |

Live plan: [`../TODO.md`](../TODO.md). AI/agent guide: [`../../CLAUDE.md`](../../CLAUDE.md).

## Conventions

- Findings are tagged Observed / Inferred / Unknown; prefer citing paths and issue numbers.
- A decision changes only via a new or updated ADR.
- Design docs describe intent; code and tests are the source of truth once they exist. Update the doc in the same change.
