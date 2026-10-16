import { describe, expect, it } from 'vitest';
import { findTrigger, mapOffset, parseInline } from './inline';

describe('parseInline', () => {
  it('tokenizes marks, links, tags and keeps visible text as source substrings', () => {
    const src = 'A **bold** and *it* `c` ~~s~~ [[Page|alias]] [[Other]] [x](https://a.io) #tag https://b.io/p.';
    const toks = parseInline(src);
    expect(toks.map((t) => t.type)).toEqual([
      'text', 'bold', 'text', 'italic', 'text', 'code', 'text', 'strike', 'text', 'wikilink', 'text', 'wikilink', 'text', 'link', 'text', 'tag', 'text', 'url', 'text',
    ]);
    for (const t of toks) expect(src.slice(t.start, t.start + t.text.length)).toBe(t.text);
    expect(toks.find((t) => t.type === 'wikilink')).toMatchObject({ target: 'Page', text: 'alias' });
    expect(toks.find((t) => t.type === 'url')).toMatchObject({ href: 'https://b.io/p' });
  });

  it('ignores mid-word tags, snake_case, and numeric tags', () => {
    expect(parseInline('a#b snake_case_name a_b_ c #123').map((t) => t.type)).toEqual(['text']);
  });
});

describe('findTrigger', () => {
  it('detects slash, link, and tag menus before the caret', () => {
    expect(findTrigger('/head', 5)).toEqual({ kind: 'slash', start: 0, query: 'head' });
    expect(findTrigger('see /to do', 10)).toEqual({ kind: 'slash', start: 4, query: 'to do' });
    expect(findTrigger('a/b', 3)).toBeNull();
    expect(findTrigger('go [[Wri', 8)).toEqual({ kind: 'link', start: 3, query: 'Wri' });
    expect(findTrigger('[[done]] x', 10)).toBeNull();
    expect(findTrigger('x #pro', 6)).toEqual({ kind: 'tag', start: 2, query: 'pro' });
    expect(findTrigger('# ', 2)).toBeNull();
  });
});

describe('mapOffset', () => {
  it('keeps the caret on the same character across remote inserts and deletes', () => {
    expect(mapOffset('hello world', 'B: hello world', 6)).toBe(9); // insert before caret shifts it
    expect(mapOffset('hello world', 'hello world!!', 6)).toBe(6); // insert after caret does not
    expect(mapOffset('hello brave world', 'hello world', 14)).toBe(8); // delete before caret
    expect(mapOffset('abcdef', 'abXYef', 3)).toBe(4); // caret inside replaced range
  });
});

describe('@ mentions', () => {
  it('triggers after whitespace only, so emails do not open it', async () => {
    const { findTrigger } = await import('./inline');
    expect(findTrigger('see @tom', 8)).toEqual({ kind: 'mention', start: 4, query: 'tom' });
    expect(findTrigger('mail me@host', 12)).toBeNull();
  });

  it('resolves relative days and weekdays to journal keys', async () => {
    const { dateMentions } = await import('./inline');
    const fri = new Date(2026, 9, 16); // a Friday
    expect(dateMentions('', fri).map((d) => d.key)).toEqual(['2026-10-16', '2026-10-17', '2026-10-15']);
    expect(dateMentions('tom', fri)).toEqual([{ label: 'Tomorrow', key: '2026-10-17' }]);
    expect(dateMentions('mon', fri)).toEqual([{ label: 'Next Monday', key: '2026-10-19' }]);
    expect(dateMentions('fr', fri)).toEqual([{ label: 'Next Friday', key: '2026-10-23' }]);
    expect(dateMentions('2026-12-01', fri)).toEqual([{ label: '2026-12-01', key: '2026-12-01' }]);
  });
});
