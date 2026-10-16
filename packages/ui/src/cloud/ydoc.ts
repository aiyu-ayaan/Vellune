// Page <-> Yjs document mapping. The store keeps plain Page objects for rendering; every local
// change is diffed into the page's Y.Doc so concurrent edits from other devices merge per block,
// per field, and per character instead of last-writer-wins on the whole page.

import * as Y from 'yjs';
import { Block, CanvasItem, Page, PropValue, Stroke } from '../model';

export const LOCAL = Symbol('local');

// `favorite` is deliberately absent: favorites are per person, not part of the shared page.
const META_KEYS = ['title', 'icon', 'tags', 'aliases', 'journal', 'extraFrontmatter', 'createdAt', 'updatedAt', 'deletedAt', 'parentId', 'folder', 'order'] as const;
const BLOCK_KEYS = ['kind', 'level', 'checked', 'language', 'color', 'src', 'indent'] as const;

const same = (a: unknown, b: unknown) => a === b || JSON.stringify(a) === JSON.stringify(b);

/** Minimal splice turning `ytext` into `next` (common prefix/suffix), so concurrent typing merges. */
function setText(ytext: Y.Text, next: string) {
  const prev = ytext.toString();
  if (prev === next) return;
  let start = 0;
  while (start < prev.length && start < next.length && prev[start] === next[start]) start++;
  let endPrev = prev.length;
  let endNext = next.length;
  while (endPrev > start && endNext > start && prev[endPrev - 1] === next[endNext - 1]) {
    endPrev--;
    endNext--;
  }
  if (endPrev > start) ytext.delete(start, endPrev - start);
  if (endNext > start) ytext.insert(start, next.slice(start, endNext));
}

function blockMap(b: Block): Y.Map<unknown> {
  const m = new Y.Map<unknown>();
  m.set('id', b.id);
  for (const k of BLOCK_KEYS) if (b[k] !== undefined) m.set(k, b[k]);
  m.set('text', new Y.Text(b.text));
  return m;
}

function syncKeyed<T extends { id: string }>(ymap: Y.Map<T>, items: T[]) {
  const ids = new Set(items.map((i) => i.id));
  for (const k of [...ymap.keys()]) if (!ids.has(k)) ymap.delete(k);
  for (const i of items) if (!same(ymap.get(i.id), i)) ymap.set(i.id, i);
}

/** Apply a full Page snapshot to `doc` as the smallest set of Yjs operations. */
export function pageToDoc(doc: Y.Doc, page: Page) {
  doc.transact(() => {
    const meta = doc.getMap<unknown>('meta');
    if (meta.get('id') !== page.id) meta.set('id', page.id);
    for (const k of META_KEYS) {
      const v = page[k];
      if (v === undefined) {
        if (meta.has(k)) meta.delete(k);
      } else if (!same(meta.get(k), v)) meta.set(k, v);
    }

    const yblocks = doc.getArray<Y.Map<unknown>>('blocks');
    const want = new Set(page.blocks.map((b) => b.id));
    for (let i = yblocks.length - 1; i >= 0; i--) {
      if (!want.has(yblocks.get(i).get('id') as string)) yblocks.delete(i, 1);
    }
    page.blocks.forEach((b, i) => {
      let ym = i < yblocks.length ? yblocks.get(i) : undefined;
      if (ym?.get('id') !== b.id) {
        const j = yblocks.toArray().findIndex((m, idx) => idx > i && m.get('id') === b.id);
        // shortcut: a moved block is re-inserted, so a concurrent edit to it during the move is lost.
        if (j >= 0) yblocks.delete(j, 1);
        yblocks.insert(i, [blockMap(b)]);
        return;
      }
      for (const k of BLOCK_KEYS) {
        if (b[k] === undefined) {
          if (ym.has(k)) ym.delete(k);
        } else if (!same(ym.get(k), b[k])) ym.set(k, b[k]);
      }
      setText(ym.get('text') as Y.Text, b.text);
    });
    if (yblocks.length > page.blocks.length) yblocks.delete(page.blocks.length, yblocks.length - page.blocks.length);

    // Properties merge per key, so two people setting different properties never collide.
    const props = doc.getMap<PropValue>('props');
    const wantProps = page.props ?? {};
    for (const k of [...props.keys()]) if (!(k in wantProps)) props.delete(k);
    for (const [k, v] of Object.entries(wantProps)) if (props.get(k) !== v) props.set(k, v);

    syncKeyed(doc.getMap<CanvasItem>('canvasItems'), page.canvas.items);
    syncKeyed(doc.getMap<Stroke>('strokes'), page.canvas.strokes);
  }, LOCAL);
}

/** Read the Page a document currently describes. Returns null until its metadata has arrived. */
export function docToPage(doc: Y.Doc): Page | null {
  const meta = doc.getMap<unknown>('meta');
  const id = meta.get('id') as string | undefined;
  if (!id) return null;
  const m = Object.fromEntries(META_KEYS.map((k) => [k, meta.get(k)])) as Partial<Page>;
  const blocks = doc.getArray<Y.Map<unknown>>('blocks').toArray().map((ym) => {
    const b: Block = { id: ym.get('id') as string, kind: (ym.get('kind') as Block['kind']) ?? 'paragraph', text: (ym.get('text') as Y.Text).toString() };
    for (const k of BLOCK_KEYS) {
      const v = ym.get(k);
      if (v !== undefined && k !== 'kind') (b as unknown as Record<string, unknown>)[k] = v;
    }
    return b;
  });
  return {
    id,
    title: m.title ?? '',
    icon: m.icon,
    blocks,
    tags: m.tags ?? [],
    aliases: m.aliases ?? [],
    favorite: false,
    journal: m.journal,
    extraFrontmatter: m.extraFrontmatter,
    createdAt: m.createdAt ?? Date.now(),
    updatedAt: m.updatedAt ?? Date.now(),
    deletedAt: m.deletedAt,
    // Tree position: dropping these would flatten the sidebar on other devices, and their next edit
    // would delete the fields from the shared doc.
    parentId: m.parentId,
    folder: m.folder,
    order: m.order,
    props: doc.getMap<PropValue>('props').size ? Object.fromEntries(doc.getMap<PropValue>('props').entries()) : undefined,
    canvas: {
      items: [...doc.getMap<CanvasItem>('canvasItems').values()],
      strokes: [...doc.getMap<Stroke>('strokes').values()],
    },
  };
}
