# 13 — Page mode, edgeless mode and the toggle: behaviour spec

Clean-room feature inventory for Phases 4–5 (`TODO.md`). Source: AFFiNE canary at commit `733d839` (2026-10), read in a sparse clone outside this repo (`blocksuite/affine/*`, `packages/frontend/core/src/blocksuite`, `…/modules/editor`). Only observed behaviour and names of features are recorded; no code, assets or strings are copied. Our own labels, icons and shortcuts may differ. See `11-licensing-and-cleanroom.md`.

Tags: **Observed** (read in source), **Inferred**, **Unknown**. Scope column: **v1** = in plan, **later** = after v1, **no** = out of scope (CLAUDE.md rule 7: no AI, payments, calendar in v1).

## 1. The toggle (Observed)

- One document, two views: **page** and **edgeless**. The mode is a per-document state; new docs open in page mode, and a doc remembers its primary mode, stored as the built-in doc property `docPrimaryMode` (see `14` §7).
- UI: a two-item segmented control (page icon, canvas icon) in the doc header, with tooltips "View in Edgeless Canvas" and "Page mode" plus the shortcut `Alt+S` (your screenshots).
- Switching clears the editor selection, is blocked for trashed docs (only the current mode stays visible), and `Alt+S` is registered as a command-palette entry whose label flips ("switch to edgeless" / "switch to page").
- Both views edit the same content: page blocks appear on the canvas as note containers, and canvas elements are not shown in page mode.
- **Vellune:** segmented control in the doc top bar, `Alt+S` for keyboards, toolbar button on touch. Per-doc remembered mode. Scope: v1 (Phase 5).

## 2. Page mode: block types (Observed)

Block flavours: paragraph (text, h1–h6, quote), list (bulleted, numbered, to-do), code, divider, callout, latex (block and inline), image, attachment (file, PDF), bookmark (link card), table, database (table, kanban, calendar views), data-view to-do, embed (YouTube, GitHub, Figma, Loom, generic iframe), embed-doc (linked doc card), surface-ref (frame or mind map embedded in a page), note, edgeless-text.

| Feature | Behaviour | Vellune scope |
|---|---|---|
| Paragraph, headings 1–6, quote | Convert via slash menu, toolbar or shortcut | v1 |
| Bulleted, numbered, to-do lists | Nesting with Tab / Shift+Tab, checkbox toggle | v1 |
| Code block | Language picker, copy button | v1 |
| Divider, callout | Simple blocks | v1 |
| Image, file attachment | Blob upload, caption, preview | v1 (blobs: Phase 4) |
| Link card (bookmark), linked-doc card, `[[` linked doc | Doc picker; this is the backlink source | v1 |
| Table (simple) | Rows/columns, cell text | later |
| Database views (table, kanban, calendar) | Properties, filters | later |
| Latex block and inline | Math rendering | later |
| Embeds (YouTube, GitHub, Figma, Loom, iframe) | Network cards | later; needs consent copy because of rule 3 (offline-first) |
| PDF preview | In-page viewer | later |

## 3. Page mode: editing behaviour (Observed)

- **Slash menu** groups, in order: Basic (text, headings, lists, quote, divider, callout, code, inline equation), Page (new doc, linked doc), Content & Media (table, image, link, attachment, PDF, embeds, equation), Edgeless Element (frame, mind map, existing frames and groups), Date (today, tomorrow, yesterday, now), Database, Actions (move up, move down, copy, duplicate, delete). Items carry search aliases (for example "file", "dual link", "database").
- **Inline formats and shortcuts:** bold `Mod+B`, italic `Mod+I`, underline `Mod+U`, strikethrough `Mod+Shift+S`, inline code `Mod+E`, link `Mod+K`.
- **Block conversion shortcuts:** text `Mod+Alt+0`, headings `Mod+Alt+1..6`, bulleted `Mod+Alt+8`, numbered `Mod+Alt+9`, code `Mod+Alt+C`, divider `Mod+Alt+D` (alternate `Mod+Shift+n` forms exist). Conversions also appear in a selection toolbar and block menu. Text alignment is per block.
- **Other widgets:** drag handle for reordering blocks, keyboard toolbar on mobile, linked-doc popup after `[[`/`@`, note slicer, remote selections (collaborator cursors), scroll anchoring to a block, block selection and drag-to-select area.
- **Vellune:** same groups, own labels; Markdown typing shortcuts (`#`, `-`, `1.`, `[]`, `>`, `---`) as in the Obsidian-style flow; on Android the keyboard toolbar above the IME is the main entry point (Phase 4). Slash menu "Edgeless Element" group depends on Phase 5. Date items: allowed in v1 (plain text, no calendar integration).

## 4. Edgeless mode: tools (Observed)

Tool names registered: select (default), pan (hand), brush, highlighter, eraser, shape, text, note, frame, connector, link, mind map, template, present (frames). Keyboard map in desktop edgeless: `V` select, `H` hand (holding `Space` pans temporarily), `P` pen, `Shift+P` highlighter, `E` eraser, `N` note, `T` text, `C` connector (pressing again cycles connector mode), `F` frame around selection, `Shift+S` shape, `Mod+G` / `Shift+Mod+G` group / ungroup, `Mod+A` select all, `Mod+-` / `Mod+=` zoom, `Alt+0` reset zoom, `Alt+1` / `Alt+2` fit variants, arrows nudge selection (Shift for larger steps), `Delete`/`Backspace` remove, `Esc` deselect, `Tab` cycles, `Enter` edits.

| Capability | Behaviour | Vellune scope |
|---|---|---|
| Pen, highlighter, eraser | Freehand elements; eraser removes whole elements | Done in spike (Phase 1); pencil texture later |
| Shapes | Rect, ellipse, diamond, triangle, rounded; fill, stroke, text inside | v1 (Phase 5) |
| Text and note (sticky) | Free text; coloured note cards with rich text | v1 |
| Connector | Lines or arrows between elements, straight/curve/elbow modes, auto-attach to element edges | v1 |
| Frame | Named region; presentation order | v1 (present mode later) |
| Group | Select several as one | v1 |
| Mind map | Tree layout, auto-arranged children | later |
| Images, link cards, doc embeds on canvas | Same blocks as page mode placed on the canvas | v1 (doc embed: Phase 5; feeds graph edges, `09`) |
| Templates | Prebuilt layouts | no |
| Selection | Rubber-band box, shift-add, resize/rotate handles, align and distribute, z-order | v1 (lasso is ours, pen-first) |
| Zoom toolbar, minimap, auto-connect widget | On-screen zoom controls and fit | v1 (zoom toolbar: yes; minimap: later) |
| Gestures | Wheel zoom, pinch zoom, two-finger pan, space-drag pan | v1; touch arbitration per `05` |

## 5. What AFFiNE does not give us (Observed, see `01` §4)

No palm rejection, no pen-only mode, no tilt, no prediction, no front-buffered ink. These are Vellune's reason to exist and stay Phase 1/5 priorities.

## 6. Unknowns

- How a shared link chooses the mode (the per-doc mode itself is a doc property, see `14`).
- Mobile edgeless toolbar layout (touch UI code not read; `packages/frontend/mobile` not cloned).
- Exact connector routing and mind-map layout algorithms (behaviour only; we will design our own, with determinism tests per `CLAUDE.md`).
- Database view feature depth (filters, formulas).

## 7. Plan impact

- **Phase 4:** page editor items in `TODO.md` stay; add keyboard toolbar, markdown typing shortcuts and slash menu groups from §3.
- **Phase 5:** add the toggle (§1), shape, connector, frame and group tools (§4), and zoom toolbar. The canvas from the Phase 1 spike becomes the edgeless view of the same doc; the standalone canvas list disappears when the doc list lands.
