import { describe, expect, it } from 'vitest';
import * as Y from 'yjs';
import { diffBlocks, groupVersions, pageAt, LoggedUpdate } from './history';
import { pageToDoc } from './ydoc';
import { newBlock, newPage } from '../model';

describe('version history', () => {
  it('groups edits into sessions by gap, newest first, with authors', () => {
    const a = { id: 'a', name: 'Ann' }, b = { id: 'b', name: 'Bo' };
    const m = 60_000;
    const v = groupVersions([
      { seq: 1, at: 0, user: a },
      { seq: 2, at: 1 * m, user: b },
      { seq: 3, at: 2 * m, user: a },
      { seq: 4, at: 30 * m, user: b },
    ]);
    expect(v.map((x) => [x.seq, x.edits, x.authors.map((u) => u.name)])).toEqual([
      [4, 1, ['Bo']],
      [3, 3, ['Ann', 'Bo']],
    ]);
  });

  it('rebuilds any past state from the log and diffs it', () => {
    const doc = new Y.Doc();
    const log: LoggedUpdate[] = [];
    let seq = 0;
    doc.on('update', (u: Uint8Array) => log.push({ seq: ++seq, at: seq, user: null, update: u }));
    const p1 = newPage('Plan', { blocks: [newBlock('paragraph', 'one')] });
    pageToDoc(doc, p1);
    const p2 = { ...p1, blocks: [{ ...p1.blocks[0], text: 'one!' }, newBlock('paragraph', 'two')] };
    pageToDoc(doc, p2);
    const v1 = pageAt(log, 1)!;
    const v2 = pageAt(log, 2)!;
    expect(v1.blocks.map((b) => b.text)).toEqual(['one']);
    expect(v2.blocks.map((b) => b.text)).toEqual(['one!', 'two']);
    const d = diffBlocks(v1, v2);
    expect(Object.values(d.changes).sort()).toEqual(['added', 'changed']);
    expect(diffBlocks(v2, v1).removed.map((b) => b.text)).toEqual(['two']);
  });
});
