// Quick-search ranking over page titles, aliases, tags and block text. Runs locally on every
// keystroke, so it stays a linear scan; swap in an index when workspaces outgrow it.

import { Page } from './model';

export interface SearchHit {
  page: Page;
  score: number;
  /** Block text around the first body match, when the body matched. */
  snippet?: { before: string; match: string; after: string };
}

const norm = (s: string) => s.toLowerCase();

/** Pages matching every word of `query`, best first. An empty query lists recent pages. */
export function searchPages(pages: Page[], query: string, limit = 30): SearchHit[] {
  const live = pages.filter((p) => !p.deletedAt && !p.folder);
  const q = norm(query.trim());
  if (!q) return [...live].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 8).map((page) => ({ page, score: 0 }));
  const words = q.split(/\s+/);
  const hits: SearchHit[] = [];
  for (const page of live) {
    const title = norm(page.title);
    const meta = [...page.aliases, ...page.tags].map(norm);
    const body = page.blocks.map((b) => b.text);
    const lowBody = body.map(norm);
    let score = 0;
    let ok = true;
    for (const w of words) {
      const inTitle = title.includes(w);
      const inMeta = meta.some((m) => m.includes(w));
      const inBody = lowBody.some((t) => t.includes(w));
      if (!inTitle && !inMeta && !inBody) {
        ok = false;
        break;
      }
      score += inTitle ? (title.startsWith(w) ? 30 : 20) : inMeta ? 12 : 4;
    }
    if (!ok) continue;
    if (title === q) score += 100;
    else if (title.startsWith(q)) score += 40;
    let snippet: SearchHit['snippet'];
    if (!words.every((w) => title.includes(w))) {
      const i = lowBody.findIndex((t) => t.includes(words[0]));
      if (i >= 0) {
        const text = body[i].replace(/\s+/g, ' ');
        const at = norm(text).indexOf(words[0]);
        const start = Math.max(0, at - 40);
        snippet = {
          before: (start > 0 ? '…' : '') + text.slice(start, at),
          match: text.slice(at, at + words[0].length),
          after: text.slice(at + words[0].length, at + words[0].length + 60),
        };
      }
    }
    hits.push({ page, score: score + Math.min(page.updatedAt / 1e13, 0.5), snippet });
  }
  return hits.sort((a, b) => b.score - a.score).slice(0, limit);
}
