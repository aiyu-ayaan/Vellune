// Table blocks keep their cells in the block text as TSV (rows on lines, cells split by tabs), so
// concurrent edits to different cells merge per character like any other text. Row 0 is the header.

export type Grid = string[][];

const clean = (s: string) => s.replace(/[\t\n\r]+/g, ' ');

export function parseTable(text: string): Grid {
  const rows = text.split('\n').map((r) => r.split('\t'));
  const cols = Math.max(1, ...rows.map((r) => r.length));
  return rows.map((r) => Array.from({ length: cols }, (_, i) => r[i] ?? ''));
}

export const serializeTable = (g: Grid): string => g.map((r) => r.map(clean).join('\t')).join('\n');

export const emptyTable = (rows = 3, cols = 3): string => serializeTable(Array.from({ length: rows }, () => Array(cols).fill('')));

export const setCell = (g: Grid, r: number, c: number, v: string): Grid => g.map((row, i) => (i === r ? row.map((x, j) => (j === c ? clean(v) : x)) : row));
export const addRow = (g: Grid, at = g.length): Grid => [...g.slice(0, at), Array(g[0]?.length ?? 1).fill(''), ...g.slice(at)];
export const addCol = (g: Grid, at = g[0]?.length ?? 0): Grid => g.map((r) => [...r.slice(0, at), '', ...r.slice(at)]);
export const dropRow = (g: Grid, at: number): Grid => (g.length > 1 ? g.filter((_, i) => i !== at) : g);
export const dropCol = (g: Grid, at: number): Grid => (g[0].length > 1 ? g.map((r) => r.filter((_, i) => i !== at)) : g);

const mdCell = (s: string) => s.replace(/\\/g, '\\\\').replace(/\|/g, '\\|') || ' ';

/** GitHub-flavored Markdown table. */
export function tableToMarkdown(text: string): string {
  const g = parseTable(text);
  const line = (r: string[]) => `| ${r.map(mdCell).join(' | ')} |`;
  return [line(g[0]), `| ${g[0].map(() => '---').join(' | ')} |`, ...g.slice(1).map(line)].join('\n');
}

const splitRow = (line: string): string[] => {
  const t = line.trim().replace(/^\|/, '').replace(/(?<!\\)\|$/, '');
  const cells: string[] = [];
  let cur = '';
  for (let i = 0; i < t.length; i++) {
    if (t[i] === '\\' && (t[i + 1] === '|' || t[i + 1] === '\\')) cur += t[++i];
    else if (t[i] === '|') {
      cells.push(cur.trim());
      cur = '';
    } else cur += t[i];
  }
  cells.push(cur.trim());
  return cells;
};

export const isTableSeparator = (line: string) => /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(line);

/** Rows of a GFM table (header, separator, body lines) as block text. */
export function markdownToTable(lines: string[]): string {
  return serializeTable([splitRow(lines[0]), ...lines.slice(2).map(splitRow)].map((r) => r.map(clean)));
}
