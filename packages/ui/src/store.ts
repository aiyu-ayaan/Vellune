// Workspace state + IndexedDB persistence. Every edit applies locally first; writes are
// batched and never depend on the network.

import { useSyncExternalStore } from 'react';
import { Page, newBlock, newId, newPage, todayKey } from './model';
import { seedPages } from './seed';
import { sync } from './cloud/sync';
import { session } from './cloud/session';

export type View = 'page' | 'edgeless' | 'graph' | 'database' | 'trash';

export interface State {
  pages: Record<string, Page>;
  activeId: string | null;
  view: View;
  loaded: boolean;
  persistence: 'indexeddb' | 'memory';
  databaseTag: string | null;
}

type Listener = () => void;

const DB_NAME = 'vellune';
const STORE = 'pages';
const SAVE_DELAY_MS = 300;

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function prefs(): { activeId?: string; view?: View } {
  try {
    return JSON.parse(localStorage.getItem('vellune:prefs') ?? '{}');
  } catch {
    return {};
  }
}

class Store {
  private state: State = {
    pages: {},
    activeId: null,
    view: 'page',
    loaded: false,
    persistence: 'memory',
    databaseTag: null,
  };
  private listeners = new Set<Listener>();
  private db: IDBDatabase | null = null;
  private dirty = new Set<string>();
  private timer: ReturnType<typeof setTimeout> | null = null;

  get = () => this.state;

  subscribe = (l: Listener) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };

  private set(patch: Partial<State>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((l) => l());
    if ('activeId' in patch || 'view' in patch) {
      try {
        localStorage.setItem('vellune:prefs', JSON.stringify({ activeId: this.state.activeId, view: this.state.view }));
      } catch {
        // Prefs are a convenience; private mode may block storage.
      }
    }
  }

  async load() {
    let pages: Page[] = [];
    try {
      this.db = await openDb();
      pages = await new Promise<Page[]>((resolve, reject) => {
        const req = this.db!.transaction(STORE).objectStore(STORE).getAll();
        req.onsuccess = () => resolve(req.result as Page[]);
        req.onerror = () => reject(req.error);
      });
    } catch (e) {
      console.warn('[vellune] IndexedDB unavailable, edits stay in memory only', e);
      this.db = null;
    }
    const isNew = pages.length === 0;
    if (isNew) pages = seedPages();
    const map = Object.fromEntries(pages.map((p) => [p.id, p]));
    const p = prefs();
    const activeId = p.activeId && map[p.activeId] && !map[p.activeId].deletedAt ? p.activeId : (pages.find((x) => !x.deletedAt)?.id ?? null);
    this.set({ pages: map, activeId, view: p.view === 'trash' ? 'page' : (p.view ?? 'page'), loaded: true, persistence: this.db ? 'indexeddb' : 'memory' });
    if (isNew) pages.forEach((x) => this.markDirty(x.id));
    await sync.init({ pages: () => this.state.pages, applyRemote: this.applyRemote, removeLocal: this.removeLocal });
    window.addEventListener('pagehide', this.flush);
    document.addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && this.flush());
  }

  private markDirty(id: string) {
    this.dirty.add(id);
    // Throttle, not debounce: a steady stream of edits or remote updates must not postpone the save forever.
    this.timer ??= setTimeout(this.flush, SAVE_DELAY_MS);
  }

  flush = () => {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    sync.flush();
    if (!this.db || this.dirty.size === 0) return;
    const ids = [...this.dirty];
    this.dirty.clear();
    const tx = this.db.transaction(STORE, 'readwrite');
    const os = tx.objectStore(STORE);
    for (const id of ids) {
      const page = this.state.pages[id];
      if (page) os.put(page);
      else os.delete(id);
    }
    // Re-queue on failure so an aborted transaction does not drop edits.
    tx.onerror = () => ids.forEach((id) => this.markDirty(id));
  };

  // ---------- actions ----------

  createPage = (title = '', init: Partial<Page> = {}, open = true): Page => {
    const page = newPage(title, init);
    this.set({ pages: { ...this.state.pages, [page.id]: page }, ...(open ? { activeId: page.id, view: 'page' as View } : {}) });
    this.markDirty(page.id);
    sync.localChange(page);
    return page;
  };

  addPages = (pages: Page[]) => {
    this.set({ pages: { ...this.state.pages, ...Object.fromEntries(pages.map((p) => [p.id, p])) } });
    pages.forEach((p) => {
      this.markDirty(p.id);
      sync.localChange(p);
    });
  };

  updatePage = (id: string, fn: (p: Page) => Page) => {
    const old = this.state.pages[id];
    if (!old) return;
    const next = { ...fn(old), updatedAt: Date.now() };
    delete next.seeded;
    if (next.blocks.length === 0) next.blocks = [newBlock()];
    this.set({ pages: { ...this.state.pages, [id]: next } });
    this.markDirty(id);
    sync.localChange(next);
  };

  /** A merged remote version of a page arrived; render and cache it without echoing it back. */
  applyRemote = (page: Page) => {
    page = { ...page, favorite: this.state.pages[page.id]?.favorite ?? false };
    // A fresh device may have dropped its starter pages; show the first synced page instead of nothing.
    const activeId = this.state.activeId && this.state.pages[this.state.activeId] ? this.state.activeId : page.deletedAt ? null : page.id;
    this.set({ pages: { ...this.state.pages, [page.id]: page }, activeId });
    this.markDirty(page.id);
  };

  /** Drop a page from this device only (e.g. access to a shared page was revoked). */
  removeLocal = (id: string) => {
    const { [id]: _gone, ...rest } = this.state.pages;
    const activeId = this.state.activeId === id ? (Object.values(rest).find((p) => !p.deletedAt)?.id ?? null) : this.state.activeId;
    this.set({ pages: rest, activeId });
    this.markDirty(id);
  };

  /** Logout: wipe every local page and start from the seed workspace. */
  reset = () => {
    const seeded = seedPages();
    for (const id of Object.keys(this.state.pages)) this.dirty.add(id);
    this.set({ pages: Object.fromEntries(seeded.map((p) => [p.id, p])), activeId: seeded[0]?.id ?? null, view: 'page' });
    seeded.forEach((p) => {
      this.markDirty(p.id);
      sync.localChange(p);
    });
  };

  open = (id: string, view?: View) => {
    const keep = this.state.view === 'edgeless' ? 'edgeless' : 'page';
    this.set({ activeId: id, view: view ?? keep });
  };

  setView = (view: View) => this.set({ view });

  setDatabaseTag = (tag: string | null) => this.set({ databaseTag: tag, view: 'database' });

  /** Follow a wikilink: open the page it resolves to, or create it. */
  openOrCreate = (title: string, resolve: (t: string) => Page | undefined) => {
    const hit = resolve(title);
    if (hit) this.open(hit.id, 'page');
    else this.createPage(title.trim());
  };

  openToday = () => {
    const key = todayKey();
    const hit = Object.values(this.state.pages).find((p) => p.journal === key && !p.deletedAt);
    if (hit) this.open(hit.id, 'page');
    else this.createPage(key, { journal: key, tags: ['journal'] });
  };

  /** Ids of every page below `id` in the folder/doc tree. */
  descendants = (id: string): string[] => {
    const out: string[] = [];
    const walk = (pid: string) => {
      for (const p of Object.values(this.state.pages)) if (p.parentId === pid && !out.includes(p.id)) {
        out.push(p.id);
        walk(p.id);
      }
    };
    walk(id);
    return out;
  };

  /** Children of `parentId` (null = root) in display order: manual order, then folders first, then title. */
  children = (parentId: string | null, includeDeleted = false): Page[] => {
    const pages = this.state.pages;
    const atRoot = (p: Page) => !p.parentId || !pages[p.parentId] || (!includeDeleted && !!pages[p.parentId].deletedAt);
    return Object.values(pages)
      .filter((p) => (includeDeleted || !p.deletedAt) && (parentId === null ? atRoot(p) : p.parentId === parentId))
      .sort(
        (a, b) =>
          (a.order ?? Infinity) - (b.order ?? Infinity) ||
          Number(!!b.folder) - Number(!!a.folder) ||
          (a.title || 'Untitled').localeCompare(b.title || 'Untitled'),
      );
  };

  createFolder = (title = 'New folder', parentId?: string): Page =>
    this.createPage(title, { folder: true, parentId, blocks: [] }, false);

  /**
   * Move `id` into `parentId` (null = root), before sibling `beforeId` (or last).
   * Refuses to move a folder into itself or its own subtree.
   */
  move = (id: string, parentId: string | null, beforeId?: string | null) => {
    if (id === parentId || (parentId && this.descendants(id).includes(parentId))) return false;
    const siblings = this.children(parentId).filter((p) => p.id !== id);
    const at = beforeId ? siblings.findIndex((p) => p.id === beforeId) : -1;
    const idx = at < 0 ? siblings.length : at;
    // Give siblings explicit positions once, so a manual move is stable on every device.
    siblings.forEach((p, i) => {
      const want = i < idx ? i : i + 1;
      if (p.order !== want) this.updatePage(p.id, (x) => ({ ...x, order: want }));
    });
    this.updatePage(id, (p) => ({ ...p, parentId: parentId ?? undefined, order: idx }));
    return true;
  };

  trash = (id: string) => {
    const now = Date.now();
    for (const pid of [id, ...this.descendants(id)]) {
      if (!this.state.pages[pid]?.deletedAt) this.updatePage(pid, (p) => ({ ...p, deletedAt: now, favorite: false }));
    }
    if (this.state.activeId && this.state.pages[this.state.activeId]?.deletedAt) {
      const next = Object.values(this.state.pages).find((p) => !p.deletedAt && !p.folder);
      this.set({ activeId: next?.id ?? null });
    }
  };

  /** Restores the page, everything trashed with it below it, and any trashed ancestors it needs. */
  restore = (id: string) => {
    const when = this.state.pages[id]?.deletedAt;
    for (const pid of this.descendants(id)) if (when && this.state.pages[pid]?.deletedAt === when) this.updatePage(pid, (p) => ({ ...p, deletedAt: undefined }));
    for (let p: Page | undefined = this.state.pages[id]; p; p = p.parentId ? this.state.pages[p.parentId] : undefined) {
      if (p.deletedAt) this.updatePage(p.id, (x) => ({ ...x, deletedAt: undefined }));
    }
  };

  purge = (id: string) => {
    const { [id]: _gone, ...rest } = this.state.pages;
    this.set({ pages: rest });
    this.markDirty(id);
    void sync.deleteRemote(id);
  };

  /** Copy a doc (fresh page and block ids) right after the original, same parent. */
  duplicate = (id: string): Page | null => {
    const src = this.state.pages[id];
    if (!src || src.folder) return null;
    const sibs = this.children(src.parentId && this.state.pages[src.parentId] ? src.parentId : null);
    const next = sibs[sibs.findIndex((p) => p.id === id) + 1];
    const copy = this.createPage(
      `Copy of ${src.title || 'Untitled'}`,
      {
        blocks: src.blocks.map((b) => ({ ...b, id: newId() })),
        tags: [...src.tags],
        canvas: structuredClone(src.canvas),
        extraFrontmatter: src.extraFrontmatter,
        parentId: src.parentId,
      },
      false,
    );
    this.move(copy.id, src.parentId && this.state.pages[src.parentId] ? src.parentId : null, next?.id);
    return this.state.pages[copy.id];
  };
}

export const store = new Store();

export function useStore<T>(select: (s: State) => T): T {
  return useSyncExternalStore(store.subscribe, () => select(store.get()));
}

/** Sign out and wipe this device: synced docs, pending ops, keys, and local pages. */
session.onAccountSwitch = () => {
  sync.reset();
  store.reset();
};

export async function signOut() {
  sync.reset();
  await session.logout();
  store.reset();
}
