import { describe, expect, it } from 'vitest';
import { buildGraph, buildIndex } from './links';
import { extractInlineTags, extractWikilinks, markdownToPage, pageToMarkdown } from './markdown';
import { newBlock, newPage } from './model';

describe('wikilinks', () => {
  it('extracts targets, aliases, embeds, and drops section suffixes', () => {
    expect(extractWikilinks('See [[A]], [[B|the b]], ![[Img]] [[C#Sec|c]] [[#local]] [[ ]] [[open')).toEqual([
      { target: 'A' },
      { target: 'B', alias: 'the b' },
      { target: 'Img' },
      { target: 'C', alias: 'c' },
    ]);
    expect(extractWikilinks('[[a [[b]]')[0].target).toBe('b');
  });

  it('extracts inline tags but not numbers or headings', () => {
    expect(extractInlineTags('#rust and #area/sync, issue #42 and a#b')).toEqual(['rust', 'area/sync']);
  });
});

describe('markdown round trip', () => {
  it('imports frontmatter, blocks, and keeps unknown keys', () => {
    const md = [
      '---',
      'title: "Design: v1"',
      'tags: [arch, "#sync"]',
      'aliases:',
      '  - System',
      'custom:',
      '  nested: 1',
      '---',
      '# Heading',
      '',
      'line one',
      'line two',
      '- [ ] todo',
      '- [x] done',
      '- bullet',
      '> quoted',
      '---',
      '```rust',
      'fn main() {}',
      '',
      '# not heading',
      '```',
    ].join('\n');
    const page = markdownToPage(md, 'file');
    expect(page.title).toBe('Design: v1');
    expect(page.tags).toEqual(['arch', 'sync']);
    expect(page.aliases).toEqual(['System']);
    expect(page.extraFrontmatter).toBe('custom:\n  nested: 1\n');
    expect(page.blocks.map((b) => [b.kind, b.text])).toEqual([
      ['heading', 'Heading'],
      ['paragraph', 'line one\nline two'],
      ['todo', 'todo'],
      ['todo', 'done'],
      ['bullet', 'bullet'],
      ['quote', 'quoted'],
      ['divider', ''],
      ['code', 'fn main() {}\n\n# not heading'],
    ]);

    const again = markdownToPage(pageToMarkdown(page), 'x');
    expect(again.title).toBe(page.title);
    expect(again.tags).toEqual(page.tags);
    expect(again.extraFrontmatter).toBe(page.extraFrontmatter);
    expect(again.blocks.map((b) => [b.kind, b.text, b.checked, b.language])).toEqual(
      page.blocks.map((b) => [b.kind, b.text, b.checked, b.language]),
    );
  });

  it('round-trips canvas items through the sidecar comment', () => {
    const page = newPage('Board', {
      canvas: {
        items: [{ id: 's', type: 'sticky', x: 1, y: 2, w: 3, h: 4, text: 'tricky --> text' }],
        strokes: [{ id: 'k', points: [0, 0, 0.5, 10, 10, 1], color: '#000', width: 2 }],
      },
    });
    const md = pageToMarkdown(page);
    expect(md.match(/-->/g)).toHaveLength(1);
    expect(markdownToPage(md, 'x').canvas).toEqual(page.canvas);
  });

  it('keeps an unterminated frontmatter as body', () => {
    const page = markdownToPage('---\ntitle: x\nbody', 'file');
    expect(page.title).toBe('file');
    expect(page.blocks.some((b) => b.text.includes('body'))).toBe(true);
  });
});

describe('link index and graph', () => {
  const a = newPage('Alpha', { aliases: ['First'], blocks: [newBlock('paragraph', 'to [[Beta]] and [[first]] #x')] });
  const b = newPage('Beta', { blocks: [newBlock('paragraph', 'back to [[Alpha|a]] and [[Missing]]'), newBlock('code', '[[Gamma]]')] });
  const c = newPage('Gamma', { tags: ['x'], canvas: { items: [{ id: 'e', type: 'embed', x: 0, y: 0, w: 1, h: 1, pageId: b.id }], strokes: [] } });
  const gone = newPage('Gone', { deletedAt: 1, blocks: [newBlock('paragraph', '[[Alpha]]')] });
  const pages = [a, b, c, gone];
  const index = buildIndex(pages);

  it('resolves by title then alias, case-insensitive', () => {
    expect(index.resolve('beta')?.id).toBe(b.id);
    expect(index.resolve(' FIRST ')?.id).toBe(a.id);
    expect(index.resolve('Gone')).toBeUndefined();
  });

  it('builds backlinks, skipping self links, code, and deleted pages', () => {
    expect(index.backlinks.get(a.id)?.map((l) => l.sourceId)).toEqual([b.id]);
    expect(index.backlinks.get(b.id)?.map((l) => l.sourceId).sort()).toEqual([a.id, c.id].sort());
    expect(index.backlinks.get(c.id)).toBeUndefined();
    expect([...index.outgoing.get(b.id)!.unresolved]).toEqual(['Missing']);
    expect([...index.tags.get('x')!].sort()).toEqual([a.id, c.id].sort());
  });

  it('builds global and local graphs', () => {
    const global = buildGraph(pages, index, { showUnresolved: true, showTags: true });
    expect(global.nodes.map((n) => n.label).sort()).toEqual(['#x', 'Alpha', 'Beta', 'Gamma', 'Missing']);
    const local = buildGraph(pages, index, { focusId: c.id, depth: 1 });
    expect(local.nodes.map((n) => n.label).sort()).toEqual(['Beta', 'Gamma']);
    const deeper = buildGraph(pages, index, { focusId: c.id, depth: 2 });
    expect(deeper.nodes.map((n) => n.label).sort()).toEqual(['Alpha', 'Beta', 'Gamma']);
  });
});

describe('markdown numbered lists and callouts', () => {
  it('round-trip', () => {
    const page = newPage('Lists', {
      blocks: [newBlock('numbered', 'one'), newBlock('numbered', 'two'), newBlock('callout', 'Heads up\nsecond line'), newBlock('quote', 'plain quote')],
    });
    const md = pageToMarkdown(page);
    expect(md).toContain('> [!note] Heads up\n> second line');
    expect(markdownToPage(md, 'x').blocks.map((b) => [b.kind, b.text])).toEqual(page.blocks.map((b) => [b.kind, b.text]));
  });
});

describe('markdown images', () => {
  it('round-trips an image block', () => {
    const p = newPage('img');
    p.blocks = [newBlock('image', 'cat', { src: 'data:image/webp;base64,AAAA' })];
    const back = markdownToPage(pageToMarkdown(p), 'img');
    expect(back.blocks[0]).toMatchObject({ kind: 'image', text: 'cat', src: 'data:image/webp;base64,AAAA' });
  });
});

describe('page icons', () => {
  it('keeps the first emoji whole and ignores the rest', async () => {
    const { firstGlyph } = await import('./components/PageIcon');
    expect(firstGlyph('👩‍💻 dev notes')).toBe('👩‍💻');
    expect(firstGlyph('🇮🇳')).toBe('🇮🇳');
    expect(firstGlyph('👍🏽!')).toBe('👍🏽');
    expect(firstGlyph('Ab')).toBe('A');
    expect(firstGlyph('')).toBeUndefined();
  });

  it('round-trips through Markdown frontmatter and Yjs', async () => {
    const { pageToMarkdown, markdownToPage } = await import('./markdown');
    const Y = await import('yjs');
    const { pageToDoc, docToPage } = await import('./cloud/ydoc');
    const page = { ...newPage('Trip'), icon: '✈️' };
    expect(markdownToPage(pageToMarkdown(page), 'x').icon).toBe('✈️');
    const doc = new Y.Doc();
    pageToDoc(doc, page);
    expect(docToPage(doc)?.icon).toBe('✈️');
    pageToDoc(doc, { ...page, icon: undefined });
    expect(docToPage(doc)?.icon).toBeUndefined();
  });
});

describe('nested blocks', () => {
  it('round-trips nested lists, toggles and their child paragraphs', () => {
    const page = newPage('Nest', {
      blocks: [
        newBlock('bullet', 'parent'),
        newBlock('bullet', 'child', { indent: 1 }),
        newBlock('numbered', 'grandchild', { indent: 2 }),
        newBlock('toggle', 'Details'),
        newBlock('paragraph', 'hidden text\nsecond line', { indent: 1 }),
        newBlock('todo', 'task', { indent: 1, checked: true }),
        newBlock('paragraph', 'after'),
      ],
    });
    const back = markdownToPage(pageToMarkdown(page), 'x').blocks;
    expect(back.map((b) => [b.kind, b.text, b.indent ?? 0])).toEqual(page.blocks.map((b) => [b.kind, b.text, b.indent ?? 0]));
    expect(back[5].checked).toBe(true);
  });

  it('folds toggles, shifts subtrees and numbers per level', async () => {
    const { visibleBlocks, shiftIndent, listNumber, clampIndent } = await import('./outline');
    const bs = [
      newBlock('toggle', 't'),
      newBlock('numbered', 'a', { indent: 1 }),
      newBlock('numbered', 'a1', { indent: 2 }),
      newBlock('numbered', 'b', { indent: 1 }),
      newBlock('paragraph', 'out'),
    ];
    expect(visibleBlocks(bs, new Set([bs[0].id])).map((b) => b.text)).toEqual(['t', 'out']);
    expect(listNumber(bs, 3)).toBe(2);
    expect(listNumber(bs, 2)).toBe(1);
    expect(clampIndent(bs, 4, 5)).toBe(2);
    expect(clampIndent(bs, 0, 3)).toBe(0);
    // Outdenting "a" carries its child along.
    const out = shiftIndent(bs, 1, -1);
    expect(out.map((b) => b.indent ?? 0)).toEqual([0, 0, 1, 1, 0]);
    // A block cannot jump two levels below its predecessor.
    expect(shiftIndent(bs, 4, 1)[4].indent).toBe(1);
  });
});
