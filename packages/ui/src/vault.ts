// Whole-workspace Markdown: export every page as a .md file in a ZIP that mirrors the sidebar
// tree, and import a set of .md files (or a folder) back into pages and folders.

import { markdownToPage, pageToMarkdown } from './markdown';
import { Page, newPage } from './model';

// ---------- ZIP (stored, no compression: Markdown is small and this keeps it dependency-free) ----------

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (const b of data) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** A ZIP archive of `files` (path -> bytes) using the "stored" method and UTF-8 names. */
export function zip(files: { path: string; data: Uint8Array }[]): Uint8Array {
  const enc = new TextEncoder();
  const local: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const f of files) {
    const name = enc.encode(f.path);
    const crc = crc32(f.data);
    const head = new DataView(new ArrayBuffer(30));
    head.setUint32(0, 0x04034b50, true);
    head.setUint16(4, 20, true);
    head.setUint16(6, 0x0800, true); // UTF-8 names
    head.setUint32(14, crc, true);
    head.setUint32(18, f.data.length, true);
    head.setUint32(22, f.data.length, true);
    head.setUint16(26, name.length, true);
    local.push(new Uint8Array(head.buffer), name, f.data);

    const dir = new DataView(new ArrayBuffer(46));
    dir.setUint32(0, 0x02014b50, true);
    dir.setUint16(4, 20, true);
    dir.setUint16(6, 20, true);
    dir.setUint16(8, 0x0800, true);
    dir.setUint32(16, crc, true);
    dir.setUint32(20, f.data.length, true);
    dir.setUint32(24, f.data.length, true);
    dir.setUint16(28, name.length, true);
    dir.setUint32(42, offset, true);
    central.push(new Uint8Array(dir.buffer), name);
    offset += 30 + name.length + f.data.length;
  }
  const size = central.reduce((n, a) => n + a.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, size, true);
  end.setUint32(16, offset, true);
  const parts = [...local, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(parts.reduce((n, a) => n + a.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

// ---------- export ----------

const safeName = (s: string) => s.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^\.+/, '').trim().slice(0, 120) || 'Untitled';

/**
 * Markdown files for every live page, laid out like the sidebar: folders become directories, and
 * a doc with child docs gets a directory of the same name for them.
 */
export function workspaceFiles(pages: Page[]): { path: string; text: string }[] {
  const live = pages.filter((p) => !p.deletedAt && !p.seeded);
  const byId = new Map(live.map((p) => [p.id, p]));
  const dirOf = new Map<string, string>();
  const dir = (id: string | undefined, seen = new Set<string>()): string => {
    const p = id ? byId.get(id) : undefined;
    if (!p || seen.has(p.id)) return '';
    if (!dirOf.has(p.id)) {
      seen.add(p.id);
      dirOf.set(p.id, `${dir(p.parentId, seen)}${safeName(p.title)}/`);
    }
    return dirOf.get(p.id)!;
  };
  const used = new Set<string>();
  const out: { path: string; text: string }[] = [];
  for (const p of live) {
    if (p.folder) continue;
    const base = `${dir(p.parentId)}${safeName(p.title)}`;
    let path = `${base}.md`;
    for (let n = 2; used.has(path.toLowerCase()); n++) path = `${base} ${n}.md`;
    used.add(path.toLowerCase());
    out.push({ path, text: pageToMarkdown(p) });
  }
  return out;
}

export function exportWorkspace(pages: Page[]): Blob {
  const enc = new TextEncoder();
  const bytes = zip(workspaceFiles(pages).map((f) => ({ path: f.path, data: enc.encode(f.text) })));
  return new Blob([bytes.buffer as ArrayBuffer], { type: 'application/zip' });
}

// ---------- import ----------

/** Notion exports name every file and folder "Title <32 hex id>"; the id is noise to people. */
export const stripNotionId = (name: string) => name.replace(/\s+[0-9a-f]{32}(?=(\.[a-z]+)?$)/i, '');

/** Notion links pages as relative Markdown links to their files; make those wikilinks. */
export function notionLinksToWikilinks(text: string): string {
  return text.replace(/(?<!!)\[([^\]]*)\]\(([^)\s]+\.md)\)/g, (all, label: string, href: string) => {
    if (/^[a-z]+:/i.test(href)) return all;
    let file: string;
    try {
      file = decodeURIComponent(href);
    } catch {
      file = href;
    }
    const title = stripNotionId(file.split('/').pop()!.replace(/\.md$/i, ''));
    return label && label !== title ? `[[${title}|${label}]]` : `[[${title}]]`;
  });
}

/**
 * Pages for Markdown files keyed by relative path ("Notes/Ideas/plan.md"). Directories become
 * sidebar folders; a file's frontmatter title wins over its file name.
 */
export function importMarkdownFiles(files: { path: string; text: string }[]): Page[] {
  const folders = new Map<string, Page>();
  const out: Page[] = [];
  const notion = files.some((f) => /\s[0-9a-f]{32}\.md$/i.test(f.path));
  const folderFor = (dir: string): string | undefined => {
    if (!dir) return undefined;
    let f = folders.get(dir);
    if (!f) {
      const cut = dir.lastIndexOf('/');
      const parentId = folderFor(cut < 0 ? '' : dir.slice(0, cut));
      f = newPage(notion ? stripNotionId(dir.slice(cut + 1)) : dir.slice(cut + 1), { folder: true, blocks: [], parentId });
      folders.set(dir, f);
      out.push(f);
    }
    return f.id;
  };
  for (const file of files) {
    const parts = file.path.split('/').filter(Boolean);
    const name = parts.pop() ?? 'Untitled.md';
    if (!/\.(md|markdown|txt)$/i.test(name)) continue;
    const base = name.replace(/\.(md|markdown|txt)$/i, '');
    const page = notion
      ? markdownToPage(notionLinksToWikilinks(file.text), stripNotionId(base))
      : markdownToPage(file.text, base);
    const parentId = folderFor(parts.join('/'));
    out.push(parentId ? { ...page, parentId } : page);
  }
  return out;
}

/** Read browser Files (from a file picker, folder picker, or drop) into pages. */
export async function importFiles(files: File[]): Promise<Page[]> {
  const read = await Promise.all(
    files.map(async (f) => ({ path: (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name, text: await f.text() })),
  );
  return importMarkdownFiles(read);
}

export function download(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
