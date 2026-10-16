import { describe, expect, it } from 'vitest';
import { store } from './store';

describe('folder tree', () => {
  it('nests, orders, blocks cycles, and trashes/restores subtrees', () => {
    const work = store.createFolder('Work');
    const sub = store.createFolder('Sub', work.id);
    const a = store.createPage('A doc', { parentId: sub.id }, false);
    const b = store.createPage('B doc', {}, false);

    expect(store.children(work.id).map((p) => p.title)).toEqual(['Sub']);
    expect(store.descendants(work.id).sort()).toEqual([sub.id, a.id].sort());

    expect(store.move(work.id, sub.id)).toBe(false); // into own subtree
    expect(store.move(b.id, sub.id, a.id)).toBe(true); // before A
    expect(store.children(sub.id).map((p) => p.title)).toEqual(['B doc', 'A doc']);
    expect(store.move(b.id, null)).toBe(true);
    expect(store.get().pages[b.id].parentId).toBeUndefined();

    store.trash(work.id);
    expect([work.id, sub.id, a.id].every((id) => store.get().pages[id].deletedAt)).toBe(true);
    expect(store.children(null).some((p) => p.id === a.id)).toBe(false);
    store.restore(a.id); // brings back its trashed ancestors
    expect([work.id, sub.id, a.id].every((id) => !store.get().pages[id].deletedAt)).toBe(true);
  });
});
