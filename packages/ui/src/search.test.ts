import { describe, expect, it } from 'vitest';
import { searchPages } from './search';
import { newBlock, newPage } from './model';

describe('quick search', () => {
  const a = newPage('Rust sync design', { updatedAt: 1 });
  const b = newPage('Groceries', { blocks: [newBlock('paragraph', 'Buy oat milk and the rust remover')], updatedAt: 2 });
  const c = newPage('Trip', { tags: ['travel'], updatedAt: 3 });
  const gone = newPage('Rust old', { deletedAt: 5 });

  it('ranks title matches above body matches and skips trash', () => {
    const hits = searchPages([b, a, gone], 'rust');
    expect(hits.map((h) => h.page.title)).toEqual(['Rust sync design', 'Groceries']);
    expect(hits[1].snippet?.match.toLowerCase()).toBe('rust');
    expect(hits[0].snippet).toBeUndefined();
  });

  it('needs every word and matches tags', () => {
    expect(searchPages([a, b, c], 'oat remover').map((h) => h.page.title)).toEqual(['Groceries']);
    expect(searchPages([a, b, c], 'travel').map((h) => h.page.title)).toEqual(['Trip']);
  });

  it('lists recent pages for an empty query', () => {
    expect(searchPages([a, b, c], '  ').map((h) => h.page.title)).toEqual(['Trip', 'Groceries', 'Rust sync design']);
  });
});
