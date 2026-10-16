import { describe, expect, it } from 'vitest';
import { addCol, addRow, dropCol, emptyTable, parseTable, serializeTable, setCell } from './table';
import { markdownToPage, pageToMarkdown } from './markdown';
import { newBlock, newPage } from './model';

describe('table blocks', () => {
  it('edits a grid stored as TSV', () => {
    let g = parseTable(emptyTable(2, 2));
    g = setCell(g, 0, 1, 'Name\twith tab');
    g = addRow(addCol(g));
    expect(g.length).toBe(3);
    expect(g[0]).toEqual(['', 'Name with tab', '']);
    expect(parseTable(serializeTable(dropCol(g, 0)))[0]).toEqual(['Name with tab', '']);
    expect(parseTable('a\tb\nc')).toEqual([['a', 'b'], ['c', '']]);
  });

  it('round-trips through GFM Markdown, pipes included', () => {
    const text = serializeTable([['Item', 'Note'], ['a|b', '[[Page]]'], ['', 'x']]);
    const md = pageToMarkdown(newPage('T', { blocks: [newBlock('paragraph', 'before'), newBlock('table', text), newBlock('paragraph', 'after')] }));
    expect(md).toContain('| Item | Note |\n| --- | --- |\n| a\\|b | [[Page]] |');
    const back = markdownToPage(md, 'x').blocks;
    expect(back.map((b) => b.kind)).toEqual(['paragraph', 'table', 'paragraph']);
    expect(back[1].text).toBe(text);
  });

  it('imports an aligned GFM table', () => {
    const b = markdownToPage('|A|B|\n|:--|--:|\n|1|2|\n', 'x').blocks;
    expect(b[0].kind).toBe('table');
    expect(parseTable(b[0].text)).toEqual([['A', 'B'], ['1', '2']]);
  });
});

describe('file blocks', () => {
  it('round-trip through Markdown as a paperclip link', () => {
    const page = newPage('F', { blocks: [newBlock('file', 'Report [final].pdf', { src: 'vblob:abc:application/pdf' })] });
    const md = pageToMarkdown(page);
    expect(md).toContain('[📎 Report  final .pdf](vblob:abc:application/pdf)');
    const back = markdownToPage(md, 'x').blocks[0];
    expect([back.kind, back.src]).toEqual(['file', 'vblob:abc:application/pdf']);
  });
});

describe('math blocks', () => {
  it('round-trip as $$ display math, multi-line or one line', () => {
    const page = newPage('M', { blocks: [newBlock('math', 'a^2 + b^2\n= c^2')] });
    const md = pageToMarkdown(page);
    expect(md).toContain('$$\na^2 + b^2\n= c^2\n$$');
    expect(markdownToPage(md, 'x').blocks.map((b) => [b.kind, b.text])).toEqual([['math', 'a^2 + b^2\n= c^2']]);
    expect(markdownToPage('$$ e^{i\\pi} $$', 'x').blocks.map((b) => [b.kind, b.text])).toEqual([['math', 'e^{i\\pi}']]);
  });
});
