// Backlink, tag, and graph index derived from pages. Rebuildable at any time (derived data).

import { pageReferences } from './markdown';
import { Page } from './model';

export interface Backlink {
  sourceId: string;
  /** Text of the block (or sticky) containing the link, for context. */
  context: string;
}

export interface LinkIndex {
  resolve(name: string): Page | undefined;
  outgoing: Map<string, { resolved: Set<string>; unresolved: Set<string> }>;
  backlinks: Map<string, Backlink[]>;
  tags: Map<string, Set<string>>;
}

const norm = (s: string) => s.trim().toLowerCase();

export function buildIndex(allPages: Page[]): LinkIndex {
  // Folders are sidebar structure, not notes: never link targets or graph nodes.
  const pages = allPages.filter((p) => !p.deletedAt && !p.folder);
  // Titles win over aliases; first page wins on duplicates.
  const titles = new Map<string, Page>();
  const aliases = new Map<string, Page>();
  for (const p of pages) {
    if (!titles.has(norm(p.title))) titles.set(norm(p.title), p);
    for (const a of p.aliases) if (!aliases.has(norm(a))) aliases.set(norm(a), p);
  }
  const resolve = (name: string) => titles.get(norm(name)) ?? aliases.get(norm(name));
  const byId = new Map(pages.map((p) => [p.id, p]));

  const outgoing: LinkIndex['outgoing'] = new Map();
  const backlinks: LinkIndex['backlinks'] = new Map();
  const tags: LinkIndex['tags'] = new Map();

  for (const p of pages) {
    const out = { resolved: new Set<string>(), unresolved: new Set<string>() };
    const refs = pageReferences(p);
    for (const tag of refs.tags) {
      if (!tags.has(tag)) tags.set(tag, new Set());
      tags.get(tag)!.add(p.id);
    }
    const contexts = [
      ...p.blocks.filter((b) => b.kind !== 'code').map((b) => b.text),
      ...p.canvas.items.filter((i) => i.type === 'sticky').map((i) => i.text ?? ''),
    ];
    for (const link of refs.links) {
      const target = resolve(link.target);
      if (!target) {
        out.unresolved.add(link.target);
        continue;
      }
      if (target.id === p.id || out.resolved.has(target.id)) continue;
      out.resolved.add(target.id);
      const context = contexts.find((c) => c.includes(`[[${link.target}`)) ?? '';
      if (!backlinks.has(target.id)) backlinks.set(target.id, []);
      backlinks.get(target.id)!.push({ sourceId: p.id, context });
    }
    for (const embedId of refs.embeds) {
      if (embedId === p.id || !byId.has(embedId) || out.resolved.has(embedId)) continue;
      out.resolved.add(embedId);
      if (!backlinks.has(embedId)) backlinks.set(embedId, []);
      backlinks.get(embedId)!.push({ sourceId: p.id, context: 'Embedded on canvas' });
    }
    outgoing.set(p.id, out);
  }
  return { resolve, outgoing, backlinks, tags };
}

export type GraphNodeKind = 'page' | 'journal' | 'unresolved' | 'tag';

export interface GraphNode {
  id: string;
  label: string;
  kind: GraphNodeKind;
  degree: number;
}

export interface GraphEdge {
  source: string;
  target: string;
}

export interface GraphOptions {
  /** Restrict to the N-hop neighborhood of this page (local graph). */
  focusId?: string;
  depth?: number;
  showUnresolved?: boolean;
  showTags?: boolean;
  showOrphans?: boolean;
  /** Only pages carrying this tag (plus their tag node). */
  tag?: string;
}

export function buildGraph(pages: Page[], index: LinkIndex, opts: GraphOptions = {}): { nodes: GraphNode[]; edges: GraphEdge[] } {
  const live = pages.filter((p) => !p.deletedAt && (!opts.tag || index.tags.get(opts.tag)?.has(p.id)));
  const nodes = new Map<string, GraphNode>();
  const edges: GraphEdge[] = [];
  for (const p of live) nodes.set(p.id, { id: p.id, label: p.title || 'Untitled', kind: p.journal ? 'journal' : 'page', degree: 0 });

  for (const p of live) {
    const out = index.outgoing.get(p.id);
    if (!out) continue;
    for (const t of out.resolved) if (nodes.has(t)) edges.push({ source: p.id, target: t });
    if (opts.showUnresolved) {
      for (const name of out.unresolved) {
        const id = `unresolved:${name.toLowerCase()}`;
        if (!nodes.has(id)) nodes.set(id, { id, label: name, kind: 'unresolved', degree: 0 });
        edges.push({ source: p.id, target: id });
      }
    }
  }
  if (opts.showTags) {
    for (const [tag, ids] of index.tags) {
      if (opts.tag && tag !== opts.tag) continue;
      const id = `tag:${tag}`;
      for (const pid of ids) {
        if (!nodes.has(pid)) continue;
        if (!nodes.has(id)) nodes.set(id, { id, label: `#${tag}`, kind: 'tag', degree: 0 });
        edges.push({ source: pid, target: id });
      }
    }
  }

  let keep: Set<string> | null = null;
  if (opts.focusId && nodes.has(opts.focusId)) {
    // Undirected BFS: local graph shows both backlinks and outgoing links.
    const adj = new Map<string, string[]>();
    for (const e of edges) {
      (adj.get(e.source) ?? adj.set(e.source, []).get(e.source)!).push(e.target);
      (adj.get(e.target) ?? adj.set(e.target, []).get(e.target)!).push(e.source);
    }
    keep = new Set([opts.focusId]);
    let frontier = [opts.focusId];
    for (let d = 0; d < (opts.depth ?? 1); d++) {
      const next: string[] = [];
      for (const id of frontier) for (const n of adj.get(id) ?? []) if (!keep.has(n)) (keep.add(n), next.push(n));
      frontier = next;
    }
  }

  const finalEdges = keep ? edges.filter((e) => keep!.has(e.source) && keep!.has(e.target)) : edges;
  for (const e of finalEdges) {
    nodes.get(e.source)!.degree++;
    nodes.get(e.target)!.degree++;
  }
  const finalNodes = [...nodes.values()].filter(
    (n) => (!keep || keep.has(n.id)) && (opts.showOrphans !== false || n.degree > 0 || n.id === opts.focusId),
  );
  return { nodes: finalNodes, edges: finalEdges };
}

export interface UnlinkedMention {
  sourceId: string;
  blockId: string;
  /** Offset and length of the mention in the block text. */
  start: number;
  length: number;
  context: string;
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Plain-text mentions of `target`'s title or aliases in other pages (Obsidian's "unlinked
 * mentions"): whole words, any case, outside wikilinks, inline code and code blocks.
 */
export function unlinkedMentions(pages: Page[], target: Page, limit = 50): UnlinkedMention[] {
  const names = [target.title, ...target.aliases].map((n) => n.trim()).filter((n) => n.length >= 3);
  if (!names.length) return [];
  // Longest name first, so "Rust sync" wins over "Rust" at the same spot.
  const re = new RegExp(`(?<![\\p{L}\\p{N}_])(${names.sort((a, b) => b.length - a.length).map(escapeRe).join('|')})(?![\\p{L}\\p{N}_])`, 'giu');
  const out: UnlinkedMention[] = [];
  for (const p of pages) {
    if (p.id === target.id || p.deletedAt || p.folder) continue;
    for (const b of p.blocks) {
      if (b.kind === 'code' || b.kind === 'image' || b.kind === 'table') continue;
      // Ranges already inside [[…]] or `…` do not count.
      const masked = b.text.replace(/\[\[[^\]]*\]\]|`[^`]*`|\[[^\]]*\]\([^)]*\)/g, (m) => ' '.repeat(m.length));
      for (const m of masked.matchAll(re)) {
        const s = Math.max(0, m.index! - 40);
        out.push({ sourceId: p.id, blockId: b.id, start: m.index!, length: m[0].length, context: (s ? '…' : '') + b.text.slice(s, m.index! + m[0].length + 40) });
        if (out.length >= limit) return out;
      }
    }
  }
  return out;
}

/** Block text with the mention turned into a wikilink to `title`, keeping the words as written. */
export function linkMention(text: string, start: number, length: number, title: string): string {
  const said = text.slice(start, start + length);
  return `${text.slice(0, start)}[[${said === title ? title : `${title}|${said}`}]]${text.slice(start + length)}`;
}
