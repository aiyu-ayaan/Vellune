import { describe, expect, it } from 'vitest';
import { crc32, importMarkdownFiles, workspaceFiles, zip } from './vault';
import { newBlock, newPage } from './model';

describe('workspace vault', () => {
  it('computes the standard CRC-32', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe(0xcbf43926);
  });

  it('writes a well-formed stored ZIP', () => {
    const data = new TextEncoder().encode('hello');
    const z = zip([{ path: 'a/b.md', data }]);
    const v = new DataView(z.buffer);
    expect(v.getUint32(0, true)).toBe(0x04034b50);
    const end = z.length - 22;
    expect(v.getUint32(end, true)).toBe(0x06054b50);
    expect(v.getUint16(end + 10, true)).toBe(1);
    expect(new TextDecoder().decode(z.slice(30 + 6, 30 + 6 + 5))).toBe('hello');
  });

  it('lays files out like the sidebar and round-trips them', () => {
    const folder = newPage('Projects', { folder: true, blocks: [] });
    const doc = newPage('Plan', { parentId: folder.id, blocks: [newBlock('paragraph', 'see [[Notes]]')] });
    const child = newPage('Step/1', { parentId: doc.id });
    const dupe = newPage('Plan', { parentId: folder.id });
    const gone = newPage('Old', { deletedAt: 1 });
    const files = workspaceFiles([folder, doc, child, dupe, gone]);
    expect(files.map((f) => f.path)).toEqual(['Projects/Plan.md', 'Projects/Plan/Step_1.md', 'Projects/Plan 2.md']);

    const back = importMarkdownFiles(files);
    const titles = back.map((p) => `${p.folder ? 'dir:' : ''}${p.title}`);
    expect(titles).toEqual(['dir:Projects', 'Plan', 'dir:Plan', 'Step/1', 'Plan']);
    const plan = back[1];
    expect(plan.parentId).toBe(back[0].id);
    expect(plan.blocks[0].text).toBe('see [[Notes]]');
    expect(back[3].parentId).toBe(back[2].id);
  });

  it('skips non-Markdown files', () => {
    expect(importMarkdownFiles([{ path: 'vault/.obsidian/app.json', text: '{}' }])).toEqual([]);
  });
});
