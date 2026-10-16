// Markdown import/export. Same rules as crates/markdown: wikilink syntax, frontmatter
// preservation, fenced code, and HTML-comment sidecars for content Markdown cannot express.

import { Block, Page, newBlock, newPage } from './model';

export interface WikiLink {
  target: string;
  alias?: string;
}

/** `[[target]]`, `[[target|alias]]`, `![[embed]]`; `#heading` / `^block` suffixes dropped. */
export function extractWikilinks(text: string): WikiLink[] {
  const links: WikiLink[] = [];
  let rest = text;
  for (;;) {
    const start = rest.indexOf('[[');
    if (start < 0) break;
    rest = rest.slice(start + 2);
    const end = rest.indexOf(']]');
    if (end < 0) break;
    const inner = rest.slice(0, end);
    const nested = inner.lastIndexOf('[[');
    if (nested >= 0) {
      rest = rest.slice(nested);
      continue;
    }
    rest = rest.slice(end + 2);
    if (inner.includes('\n')) continue;
    const bar = inner.indexOf('|');
    const rawTarget = bar >= 0 ? inner.slice(0, bar) : inner;
    const alias = bar >= 0 ? inner.slice(bar + 1).trim() : '';
    const target = rawTarget.split(/[#^]/)[0].trim();
    if (target) links.push(alias ? { target, alias } : { target });
  }
  return links;
}

// Obsidian rule: a tag needs at least one non-digit character.
const TAG_RE = /(?:^|\s)#([\p{L}\p{N}_\-/]+)/gu;

export function extractInlineTags(text: string): string[] {
  const tags: string[] = [];
  for (const m of text.matchAll(TAG_RE)) {
    if (!/^\d+$/.test(m[1])) tags.push(m[1]);
  }
  return tags;
}

/** Links and tags of a page, skipping code blocks. Canvas embeds count as links. */
export function pageReferences(page: Page): { links: WikiLink[]; tags: string[]; embeds: string[] } {
  const links: WikiLink[] = [];
  const tags = new Set(page.tags);
  for (const b of page.blocks) {
    if (b.kind === 'code') continue;
    links.push(...extractWikilinks(b.text));
    extractInlineTags(b.text).forEach((t) => tags.add(t));
  }
  for (const item of page.canvas.items) {
    if (item.type === 'sticky' && item.text) links.push(...extractWikilinks(item.text));
  }
  const embeds = page.canvas.items.flatMap((i) => (i.type === 'embed' && i.pageId ? [i.pageId] : []));
  return { links, tags: [...tags], embeds };
}

// ---------- export ----------

const CANVAS_PREFIX = '<!-- vellune-canvas: ';
const COMMENT_SUFFIX = ' -->';

function yamlScalar(s: string): string {
  const plain =
    s.length > 0 &&
    !/^[\s\-?:,[\]{}#&*!|>'"%@`]/.test(s) &&
    !/\s$/.test(s) &&
    !s.includes(': ') &&
    !s.includes(' #') &&
    !/[,[\]{}\n]/.test(s);
  return plain ? s : `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function renderBlock(b: Block): string {
  switch (b.kind) {
    case 'heading':
      return `${'#'.repeat(Math.min(6, Math.max(1, b.level ?? 1)))} ${b.text}`;
    case 'todo':
      return `- [${b.checked ? 'x' : ' '}] ${b.text}`;
    case 'bullet':
      return `- ${b.text}`;
    case 'numbered':
      // CommonMark renumbers a list from its first marker, so every item can be "1.".
      return `1. ${b.text}`;
    case 'callout':
      return [`[!note] ${b.text.split('\n')[0]}`, ...b.text.split('\n').slice(1)].map((l) => `> ${l}`).join('\n');
    case 'quote':
      return b.text
        .split('\n')
        .map((l) => `> ${l}`)
        .join('\n');
    case 'divider':
      return '---';
    case 'image':
      return `![${b.text.replace(/[\[\]\n]/g, ' ')}](${b.src ?? ''})`;
    case 'code': {
      const longest = Math.max(0, ...b.text.split('\n').map((l) => /^\s*(`*)/.exec(l)![1].length));
      const fence = '`'.repeat(Math.max(2, longest) + 1);
      return `${fence}${b.language ?? ''}\n${b.text}\n${fence}`;
    }
    default:
      return b.text;
  }
}

export function pageToMarkdown(page: Page): string {
  let yaml = `title: ${yamlScalar(page.title)}\n`;
  if (page.icon) yaml += `icon: ${yamlScalar(page.icon)}\n`;
  for (const [key, items] of [
    ['tags', page.tags],
    ['aliases', page.aliases],
  ] as const) {
    if (items.length) yaml += `${key}:\n${items.map((i) => `  - ${yamlScalar(i)}\n`).join('')}`;
  }
  if (page.extraFrontmatter) yaml += page.extraFrontmatter.endsWith('\n') ? page.extraFrontmatter : `${page.extraFrontmatter}\n`;

  const parts = page.blocks.filter((b) => b.kind === 'code' || b.kind === 'divider' || (b.kind === 'image' && b.src) || b.text !== '').map(renderBlock);
  const { items, strokes } = page.canvas;
  if (items.length || strokes.length) {
    // "-->" only appears inside JSON strings, where \u003e is an equivalent escape.
    const json = JSON.stringify({ items, strokes }).replace(/-->/g, '--\\u003e');
    parts.push(`${CANVAS_PREFIX}${json}${COMMENT_SUFFIX}`);
  }
  return `---\n${yaml}---\n\n${parts.join('\n\n')}\n`;
}

// ---------- import ----------

interface ParsedFrontmatter {
  title?: string;
  icon?: string;
  tags: string[];
  aliases: string[];
  extra: string;
}

function unquote(s: string): string {
  const t = s.trim();
  const m = /^"(.*)"$/.exec(t) ?? /^'(.*)'$/.exec(t);
  return m ? m[1].replace(/\\(["\\])/g, '$1') : t;
}

// shortcut: reads top-level title/tags/aliases with flat lists only; other keys pass through
// verbatim in `extra`. Upgrade to a YAML parser when nested properties need editing.
function parseFrontmatter(yaml: string): ParsedFrontmatter {
  const fm: ParsedFrontmatter = { tags: [], aliases: [], extra: '' };
  let listKey: 'tags' | 'aliases' | null = null;
  let skipping = false;
  const push = (key: 'tags' | 'aliases', v: string) => {
    const item = unquote(v);
    if (item) fm[key].push(key === 'tags' ? item.replace(/^#/, '') : item);
  };
  for (const line of yaml.split('\n')) {
    const isChild = /^\s+\S/.test(line) || /^-\s/.test(line);
    if (isChild) {
      const item = /^\s*-\s+(.*)$/.exec(line);
      if (listKey && item) push(listKey, item[1]);
      else if (!skipping && line.trim()) fm.extra += `${line}\n`;
      continue;
    }
    listKey = null;
    skipping = false;
    const m = /^([^:\s][^:]*):(.*)$/.exec(line);
    if (!m) {
      if (line.trim()) fm.extra += `${line}\n`;
      continue;
    }
    const key = m[1].trim();
    const value = m[2].trim();
    const norm = key === 'tag' ? 'tags' : key === 'alias' ? 'aliases' : key;
    if (norm === 'title' || norm === 'icon') {
      fm[norm] = unquote(value) || undefined;
      skipping = true;
    } else if (norm === 'tags' || norm === 'aliases') {
      skipping = true;
      if (!value) listKey = norm;
      else if (value.startsWith('[') && value.endsWith(']')) value.slice(1, -1).split(',').forEach((v) => push(norm, v));
      else push(norm, value);
    } else {
      fm.extra += `${line}\n`;
    }
  }
  return fm;
}

function parseBlocks(body: string): { blocks: Block[]; canvas?: Page['canvas'] } {
  const blocks: Block[] = [];
  let canvas: Page['canvas'] | undefined;
  let paragraph: string[] = [];
  let quote: string[] = [];
  const flush = () => {
    if (paragraph.length) blocks.push(newBlock('paragraph', paragraph.join('\n')));
    const callout = quote.length ? /^\[!\w+\][+-]?\s?(.*)$/.exec(quote[0]) : null;
    if (callout) blocks.push(newBlock('callout', [callout[1], ...quote.slice(1)].join('\n')));
    else if (quote.length) blocks.push(newBlock('quote', quote.join('\n')));
    paragraph = [];
    quote = [];
  };
  const lines = body.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/\s+$/, '');
    const t = line.trim();
    const fence = /^(`{3,}|~{3,})\s*(\S*)/.exec(t);
    if (fence) {
      flush();
      const [marker, lang] = [fence[1], fence[2]];
      const code: string[] = [];
      for (i++; i < lines.length; i++) {
        const c = lines[i].trim();
        if (c.length >= marker.length && [...c].every((ch) => ch === marker[0])) break;
        code.push(lines[i]);
      }
      blocks.push(newBlock('code', code.join('\n'), lang ? { language: lang } : {}));
      continue;
    }
    const img = /^!\[([^\]]*)\]\((\S+)\)$/.exec(t);
    if (img) {
      flush();
      blocks.push(newBlock('image', img[1], { src: img[2] }));
      continue;
    }
    if (t.startsWith(CANVAS_PREFIX) && t.endsWith(COMMENT_SUFFIX)) {
      try {
        canvas = JSON.parse(t.slice(CANVAS_PREFIX.length, -COMMENT_SUFFIX.length));
        flush();
        continue;
      } catch {
        // Not ours after all: keep it as text so nothing is lost.
      }
    }
    if (!t) {
      flush();
      continue;
    }
    const q = /^>\s?(.*)$/.exec(t);
    if (q) {
      if (paragraph.length) flush();
      quote.push(q[1]);
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(t);
    const todo = /^[-*]\s+\[([ xX])\]\s+(.*)$/.exec(t);
    const bullet = /^[-*]\s+(.*)$/.exec(t);
    const numbered = /^\d+[.)]\s+(.*)$/.exec(t);
    if (heading || todo || bullet || numbered || /^(-{3,}|\*{3,})$/.test(t)) {
      flush();
      if (heading) blocks.push(newBlock('heading', heading[2].trim(), { level: heading[1].length }));
      else if (todo) blocks.push(newBlock('todo', todo[2], { checked: todo[1] !== ' ' }));
      else if (/^(-{3,}|\*{3,})$/.test(t)) blocks.push(newBlock('divider'));
      else if (bullet) blocks.push(newBlock('bullet', bullet[1]));
      else if (numbered) blocks.push(newBlock('numbered', numbered[1]));
      continue;
    }
    if (quote.length) flush();
    paragraph.push(line);
  }
  flush();
  return { blocks, canvas };
}

/** Parse a Markdown file into a new page. `fallbackTitle` is usually the file name. */
export function markdownToPage(markdown: string, fallbackTitle: string): Page {
  let body = markdown.replace(/\r\n/g, '\n');
  let fm: ParsedFrontmatter = { tags: [], aliases: [], extra: '' };
  if (body.startsWith('---\n')) {
    const end = body.slice(4).search(/^(---|\.\.\.)\s*$/m);
    if (end >= 0) {
      fm = parseFrontmatter(body.slice(4, 4 + end));
      body = body.slice(4 + end).replace(/^(---|\.\.\.)[^\n]*\n?/, '');
    }
  }
  const { blocks, canvas } = parseBlocks(body);
  return newPage(fm.title || fallbackTitle, {
    icon: fm.icon,
    blocks: blocks.length ? blocks : [newBlock()],
    tags: fm.tags,
    aliases: fm.aliases,
    extraFrontmatter: fm.extra || undefined,
    canvas: canvas ?? { items: [], strokes: [] },
  });
}
