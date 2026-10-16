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
