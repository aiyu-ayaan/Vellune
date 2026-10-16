// Inline Markdown for rendered (non-editing) blocks. Every token's visible `text` is a verbatim
// substring of the source starting at `start`, so a click in rendered text maps back to a caret.

export type InlineToken =
  | { type: 'text' | 'bold' | 'italic' | 'strike' | 'code'; text: string; start: number }
  | { type: 'wikilink'; text: string; start: number; target: string }
  | { type: 'link' | 'url'; text: string; start: number; href: string }
  | { type: 'tag'; text: string; start: number; tag: string };

const RULES: [InlineToken['type'], RegExp][] = [
  ['code', /`([^`\n]+)`/y],
  ['wikilink', /\[\[([^\]|\n]+?)(?:\|([^\]\n]+))?\]\]/y],
  ['link', /\[([^\]\n]+)\]\((https?:\/\/[^)\s]+)\)/y],
  ['bold', /\*\*([^*\n]+)\*\*/y],
  ['strike', /~~([^~\n]+)~~/y],
  ['italic', /\*([^*\s][^*\n]*)\*|_([^_\s][^_\n]*)_/y],
  ['url', /https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"]/y],
  ['tag', /#([\p{L}\p{N}_\-/]*[\p{L}_\-/][\p{L}\p{N}_\-/]*)/uy],
];

export function parseInline(src: string): InlineToken[] {
  const out: InlineToken[] = [];
  let textStart = 0;
  const flush = (end: number) => {
    if (end > textStart) out.push({ type: 'text', text: src.slice(textStart, end), start: textStart });
  };
  let i = 0;
  while (i < src.length) {
    let matched = false;
    for (const [type, re] of RULES) {
      // Tags only at a word start, like Obsidian; `_` italics only at word boundaries.
      if (type === 'tag' && i > 0 && !/\s/.test(src[i - 1])) continue;
      re.lastIndex = i;
      const m = re.exec(src);
      if (!m) continue;
      if (type === 'italic' && m[2] !== undefined && (/\w/.test(src[i + m[0].length] ?? '') || /\w/.test(src[i - 1] ?? ''))) continue;
      flush(i);
      const inner = m[1] ?? m[2] ?? m[0];
      const innerStart = i + m[0].indexOf(inner);
      if (type === 'wikilink') {
        const alias = m[2];
        const target = m[1].trim();
        out.push({ type, target, text: alias ?? m[1], start: alias ? i + m[0].lastIndexOf(alias) : i + 2 });
      } else if (type === 'link') {
        out.push({ type, text: m[1], start: i + 1, href: m[2] });
      } else if (type === 'url') {
        out.push({ type, text: m[0], start: i, href: m[0] });
      } else if (type === 'tag') {
        out.push({ type, text: m[0], start: i, tag: m[1] });
      } else {
        out.push({ type, text: inner, start: innerStart } as InlineToken);
      }
      i += m[0].length;
      textStart = i;
      matched = true;
      break;
    }
    if (!matched) i++;
  }
  flush(src.length);
  return out;
}

export type TriggerKind = 'slash' | 'link' | 'tag';
export interface Trigger {
  kind: TriggerKind;
  /** Index of the trigger's first character (`/`, `[[`, or `#`). */
  start: number;
  query: string;
}

/** Which suggestion menu the text before the caret opens, if any. */
export function findTrigger(text: string, caret: number): Trigger | null {
  const before = text.slice(0, caret);
  const link = before.lastIndexOf('[[');
  if (link >= 0) {
    const q = before.slice(link + 2);
    if (!q.includes(']') && !q.includes('\n') && q.length <= 60) return { kind: 'link', start: link, query: q };
  }
  const slash = /(?:^|\s)\/([\p{L}\p{N} ]{0,24})$/u.exec(before);
  if (slash && !slash[1].startsWith(' ') && !slash[1].includes('  ')) {
    return { kind: 'slash', start: caret - slash[1].length - 1, query: slash[1] };
  }
  const tag = /(?:^|\s)#([\p{L}\p{N}_\-/]{0,40})$/u.exec(before);
  if (tag) return { kind: 'tag', start: caret - tag[1].length - 1, query: tag[1] };
  return null;
}
