// Workspace state + IndexedDB persistence. Every edit applies locally first; writes are
// batched and never depend on the network.

import { useSyncExternalStore } from 'react';
import { Page, newBlock, newPage, todayKey } from './model';
import { seedPages } from './seed';

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
    window.addEventListener('pagehide', this.flush);
    document.addEventListener('visibilitychange', () => document.visibilityState === 'hidden' && this.flush());
  }

  private markDirty(id: string) {
    this.dirty.add(id);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(this.flush, SAVE_DELAY_MS);
  }

  flush = () => {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
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
    return page;
  };

  addPages = (pages: Page[]) => {
    this.set({ pages: { ...this.state.pages, ...Object.fromEntries(pages.map((p) => [p.id, p])) } });
    pages.forEach((p) => this.markDirty(p.id));
  };

  updatePage = (id: string, fn: (p: Page) => Page) => {
    const old = this.state.pages[id];
    if (!old) return;
    const next = { ...fn(old), updatedAt: Date.now() };
    if (next.blocks.length === 0) next.blocks = [newBlock()];
    this.set({ pages: { ...this.state.pages, [id]: next } });
    this.markDirty(id);
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

  trash = (id: string) => {
    this.updatePage(id, (p) => ({ ...p, deletedAt: Date.now(), favorite: false }));
    if (this.state.activeId === id) {
      const next = Object.values(this.state.pages).find((p) => !p.deletedAt);
      this.set({ activeId: next?.id ?? null });
    }
  };

  restore = (id: string) => this.updatePage(id, (p) => ({ ...p, deletedAt: undefined }));

  purge = (id: string) => {
    const { [id]: _gone, ...rest } = this.state.pages;
    this.set({ pages: rest });
    this.markDirty(id);
  };
}

export const store = new Store();

export function useStore<T>(select: (s: State) => T): T {
  return useSyncExternalStore(store.subscribe, () => select(store.get()));
}
