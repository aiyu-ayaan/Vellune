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

describe('nested blocks in Yjs', () => {
  it('keeps indent through the shared document', async () => {
    const Y = await import('yjs');
    const { pageToDoc, docToPage } = await import('./cloud/ydoc');
    const page = newPage('Y', { blocks: [newBlock('toggle', 't'), newBlock('paragraph', 'c', { indent: 1 })] });
    const doc = new Y.Doc();
    pageToDoc(doc, page);
    const copy = new Y.Doc();
    Y.applyUpdate(copy, Y.encodeStateAsUpdate(doc));
    expect(docToPage(copy)?.blocks.map((b) => [b.kind, b.indent])).toEqual([['toggle', undefined], ['paragraph', 1]]);
    pageToDoc(doc, { ...page, blocks: [page.blocks[0], { ...page.blocks[1], indent: undefined }] });
    expect(docToPage(doc)?.blocks[1].indent).toBeUndefined();
  });
});

describe('unlinked mentions', () => {
  it('finds whole-word mentions outside links and code, and links them', async () => {
    const { unlinkedMentions, linkMention } = await import('./links');
    const target = newPage('Vellune', { aliases: ['VL'] });
    const src = newPage('Notes', {
      blocks: [
        newBlock('paragraph', 'I love vellune, see [[Vellune]] and `Vellune` and Vellunes'),
        newBlock('code', 'Vellune'),
        newBlock('paragraph', 'Vellune again'),
      ],
    });
    const hits = unlinkedMentions([target, src], target);
    expect(hits.map((h) => [h.blockId, h.start])).toEqual([[src.blocks[0].id, 7], [src.blocks[2].id, 0]]);
    expect(linkMention(src.blocks[0].text, 7, 7, 'Vellune')).toBe('I love [[Vellune|vellune]], see [[Vellune]] and `Vellune` and Vellunes');
    expect(linkMention('Vellune again', 0, 7, 'Vellune')).toBe('[[Vellune]] again');
  });
});

describe('page properties', () => {
  it('round-trip through frontmatter with their types', () => {
    const page = newPage('P', { props: { status: 'In progress', due: '2026-10-20', points: 3, done: false, code: '42' } });
    const md = pageToMarkdown(page);
    expect(md).toContain('status: In progress\n');
    expect(md).toContain('code: "42"\n');
    expect(markdownToPage(md, 'x').props).toEqual(page.props);
  });

  it('keeps lists, nested maps and continued scalars verbatim', () => {
    const p = markdownToPage('---\ntitle: T\nauthors:\n  - a\nnote: first\n  second\nrating: 5\n---\nbody', 'x');
    expect(p.props).toEqual({ rating: 5 });
    expect(p.extraFrontmatter).toBe('authors:\n  - a\nnote: first\n  second\n');
  });

  it('merge per key in the shared document', async () => {
    const Y = await import('yjs');
    const { pageToDoc, docToPage } = await import('./cloud/ydoc');
    const base = newPage('P', { props: { a: 1 } });
    const d1 = new Y.Doc();
    pageToDoc(d1, base);
    const d2 = new Y.Doc();
    Y.applyUpdate(d2, Y.encodeStateAsUpdate(d1));
    pageToDoc(d1, { ...base, props: { a: 1, b: 'x' } });
    pageToDoc(d2, { ...base, props: { a: 2 } });
    Y.applyUpdate(d1, Y.encodeStateAsUpdate(d2));
    expect(docToPage(d1)?.props).toEqual({ a: 2, b: 'x' });
  });
});

describe('comments', () => {
  it('sync through the page document and merge between people', async () => {
    const Y = await import('yjs');
    const { pageToDoc, docToPage } = await import('./cloud/ydoc');
    const page = newPage('C', { blocks: [newBlock('paragraph', 'x')] });
    const c = (id: string, at: number) => ({ id, blockId: page.blocks[0].id, authorId: 'u', authorName: 'U', text: id, createdAt: at });
    const a = new Y.Doc();
    pageToDoc(a, { ...page, comments: [c('first', 1)] });
    const b = new Y.Doc();
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a));
    pageToDoc(a, { ...page, comments: [c('first', 1), c('reply-a', 2)] });
    pageToDoc(b, { ...page, comments: [c('first', 1), c('reply-b', 3)] });
    Y.applyUpdate(a, Y.encodeStateAsUpdate(b));
    expect(docToPage(a)?.comments?.map((x) => x.id)).toEqual(['first', 'reply-a', 'reply-b']);
    expect(pageToMarkdown(docToPage(a)!)).not.toContain('reply');
  });
});
