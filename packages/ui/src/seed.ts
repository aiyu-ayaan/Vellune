import { Page, newBlock as b, newPage, newId } from './model';

/** First-run workspace showing how the features connect. */
export function seedPages(): Page[] {
  return starterPages().map((p) => ({ ...p, seeded: true }));
}

function starterPages(): Page[] {
  const welcome = newPage('Welcome to Vellune', {
    favorite: true,
    tags: ['guide'],
    blocks: [
      b('paragraph', '*Where thoughts take form.* Docs, whiteboards, and a knowledge graph in one local-first workspace.'),
      b('heading', 'Start here', { level: 2 }),
      b('todo', 'Read [[Writing in Vellune]]'),
      b('todo', 'Open [[Edgeless Canvas]] and switch to the Edgeless view'),
      b('todo', 'Press Ctrl/Cmd + K to search or create a page'),
      b('todo', 'Open the Brain View from the sidebar to see how notes connect'),
      b('heading', 'How it fits together', { level: 2 }),
      b('bullet', 'Link any page with [[double brackets]]; missing pages are created when you click the link.'),
      b('bullet', 'Every page lists its backlinks in the right panel.'),
      b('bullet', 'Tags like #guide and page properties drive the [[Databases|Database]] views.'),
      b('quote', 'Everything is saved on this device first. No account, no network needed.'),
    ],
  });
  const writing = newPage('Writing in Vellune', {
    tags: ['guide'],
    aliases: ['Editor', 'Markdown'],
    blocks: [
      b('paragraph', 'Type Markdown shortcuts at the start of a block:'),
      b('bullet', '`# ` to `###### ` for headings'),
      b('bullet', '`- ` for bullets, `- [ ] ` for tasks, `> ` for quotes'),
      b('bullet', '```` ``` ```` for a code block, `---` for a divider'),
      b('paragraph', 'Type `[[` to link a page. Aliases work too: [[Editor]] points back here.'),
      b('code', 'Enter       new block\nBackspace   merge with previous block\nShift+Enter line break', { language: 'text' }),
      b('paragraph', 'Pages export to portable Markdown with frontmatter. See [[Welcome to Vellune]].'),
    ],
  });
  const canvas = newPage('Edgeless Canvas', {
    tags: ['guide', 'canvas'],
    blocks: [
      b('paragraph', 'Each page has two views: Page and Edgeless. Edgeless places the document on an infinite canvas.'),
      b('bullet', 'Add sticky notes, draw with a pen (pressure-aware), and embed other pages as cards.'),
      b('bullet', 'Embedded pages show up as links in the [[Brain View]].'),
    ],
  });
  const brain = newPage('Brain View', {
    tags: ['guide', 'graph'],
    aliases: ['Graph'],
    blocks: [
      b('paragraph', 'The graph shows every page and the links between them. Switch to Local to see only the neighbors of the current page.'),
      b('bullet', 'Drag nodes, scroll to zoom, click a node to open it.'),
      b('bullet', 'Filter by tag, show tags as nodes, or show unresolved links like [[Ideas Inbox]].'),
    ],
  });
  const db = newPage('Databases', {
    tags: ['guide'],
    blocks: [
      b('paragraph', 'The Database view lists pages as a table, or as a kanban board grouped by tag. Drag cards between columns to retag them.'),
      b('paragraph', 'Back to [[Welcome to Vellune]].'),
    ],
  });
  canvas.canvas.items.push(
    { id: newId(), type: 'note', x: 0, y: 0, w: 420, h: 0 },
    { id: newId(), type: 'sticky', x: 480, y: 0, w: 200, h: 150, text: 'Stickies can link too: [[Brain View]]', color: 'amber' },
    { id: newId(), type: 'embed', x: 480, y: 190, w: 260, h: 120, pageId: writing.id },
  );
  return [welcome, writing, canvas, brain, db];
}
