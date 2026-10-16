// Local-first sync engine. Every page has a Y.Doc persisted in IndexedDB. Local edits are diffed
// into it, coalesced, encrypted with the page key, queued durably, and pushed over WebSocket;
// remote updates are decrypted and merged. Editing never waits on the network.

import { useSyncExternalStore } from 'react';
import * as Y from 'yjs';
import { Page } from '../model';
import { req } from './http';
import { Identity, b64, exportPageKey, importPageKey, newPageKey, open, seal, unb64, unwrapPageKey, wrapPageKey } from './crypto';
import { idbAll, idbDel, idbPut } from './idb';
import { LOCAL, docToPage, pageToDoc } from './ydoc';

export type Role = 'owner' | 'editor' | 'viewer';
export interface PresenceUser {
  id: string;
  name: string;
}

interface DocRec {
  id: string;
  state: Uint8Array;
  cursor: number;
  registered: boolean;
  role?: Role;
  /** Local changes were made while not syncing; push full state on next connect. */
  unsynced?: boolean;
}

interface PendingOp {
  opId: string;
  pageId: string;
  update: Uint8Array;
}

interface ServerPage {
  id: string;
  role: Role;
  wrapped_key: string;
  owner_id: string;
  public: boolean;
}

export interface SyncState {
  connection: 'off' | 'connecting' | 'online' | 'offline';
  pending: number;
  roles: Record<string, Role>;
  publicPages: Record<string, boolean>;
  presence: Record<string, PresenceUser[]>;
}

/** Hooks into the page store, set by store.ts to avoid an import cycle. */
export interface StoreBridge {
  pages: () => Record<string, Page>;
  applyRemote: (p: Page) => void;
  removeLocal: (id: string) => void;
}

const REMOTE = Symbol('remote');
const COALESCE_MS = 250;
const PERSIST_MS = 400;
const PING_MS = 25_000;

export const wsUrl = () => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/v1/sync`;

class SyncEngine {
  private state: SyncState = { connection: 'off', pending: 0, roles: {}, publicPages: {}, presence: {} };
  private listeners = new Set<() => void>();
  private bridge: StoreBridge | null = null;
  private docs = new Map<string, Y.Doc>();
  private recs = new Map<string, DocRec>();
  private keys = new Map<string, CryptoKey>();
  private pending = new Map<string, PendingOp>();
  private inflight = new Set<string>();
  private subscribed = new Set<string>();
  private localBuf = new Map<string, Uint8Array[]>();
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private identity: Identity | null = null;
  private ws: WebSocket | null = null;
  private retry = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private ready: Promise<void> | null = null;

  get = () => this.state;
  subscribe = (l: () => void) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };
  private set(p: Partial<SyncState>) {
    this.state = { ...this.state, ...p };
    this.listeners.forEach((l) => l());
  }
  private get online() {
    return this.identity !== null;
  }

  /** Load persisted Yjs state for every page. Runs once after the page store loads, signed in or not. */
  init(bridge: StoreBridge) {
    this.bridge = bridge;
    this.ready ??= (async () => {
      const [recs, ops] = await Promise.all([
        idbAll<DocRec>('docs').catch(() => [] as DocRec[]),
        idbAll<PendingOp>('pending').catch(() => [] as PendingOp[]),
      ]);
      recs.forEach((r) => this.recs.set(r.id, r));
      ops.forEach((o) => this.pending.set(o.opId, o));
      for (const page of Object.values(bridge.pages())) this.localChange(page);
      // Docs known only to the sync DB (e.g. shared pages before their first render) still load.
      for (const r of recs) if (!this.docs.has(r.id)) this.doc(r.id);
      this.set({ pending: this.pending.size, roles: Object.fromEntries(recs.filter((r) => r.role).map((r) => [r.id, r.role!])) });
    })();
    return this.ready;
  }

  private doc(id: string): Y.Doc {
    let doc = this.docs.get(id);
    if (doc) return doc;
    doc = new Y.Doc();
    const rec = this.recs.get(id);
    if (rec?.state.length) Y.applyUpdate(doc, rec.state, REMOTE);
    doc.on('update', (u: Uint8Array, origin: unknown) => this.onDocUpdate(id, u, origin));
    this.docs.set(id, doc);
    if (!rec) this.recs.set(id, { id, state: new Uint8Array(), cursor: 0, registered: false });
    return doc;
  }

  /** Called by the store after every local mutation of a page. */
  localChange(page: Page) {
    pageToDoc(this.doc(page.id), page);
  }

  private debounce(key: string, ms: number, fn: () => void) {
    clearTimeout(this.timers.get(key));
    this.timers.set(key, setTimeout(() => {
      this.timers.delete(key);
      fn();
    }, ms));
  }

  private onDocUpdate(id: string, update: Uint8Array, origin: unknown) {
    this.debounce(`persist:${id}`, PERSIST_MS, () => this.persist(id));
    const rec = this.recs.get(id);
    if (origin !== LOCAL || !rec || rec.role === 'viewer') return;
    if (!this.online || !rec.registered) {
      // Unregistered pages upload full state on registration; registered ones catch up on next connect.
      if (rec.registered && !rec.unsynced) {
        rec.unsynced = true;
        this.persist(id);
      }
      return;
    }
    const buf = this.localBuf.get(id) ?? [];
    buf.push(update);
    this.localBuf.set(id, buf);
    this.debounce(`push:${id}`, COALESCE_MS, () => this.flushLocal(id));
  }

  private flushLocal(id: string) {
    const buf = this.localBuf.get(id);
    this.localBuf.delete(id);
    if (buf?.length) this.enqueue(id, buf.length === 1 ? buf[0] : Y.mergeUpdates(buf));
  }

  private enqueue(pageId: string, update: Uint8Array) {
    const op: PendingOp = { opId: crypto.randomUUID(), pageId, update };
    this.pending.set(op.opId, op);
    idbPut('pending', op).catch(() => {});
    this.set({ pending: this.pending.size });
    void this.send(op);
  }

  private persist(id: string) {
    const doc = this.docs.get(id);
    const rec = this.recs.get(id);
    if (!doc || !rec) return;
    rec.state = Y.encodeStateAsUpdate(doc);
    idbPut('docs', rec).catch(() => {});
  }

  // ---------- connection ----------

  async start(identity: Identity) {
    this.identity = identity;
    await this.ready;
    this.connect();
  }

  stop() {
    this.identity = null;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.ws?.close();
    this.ws = null;
    this.keys.clear();
    this.subscribed.clear();
    this.inflight.clear();
    this.set({ connection: 'off', presence: {} });
  }

  /** Forget everything synced on this device (logout). */
  reset() {
    this.stop();
    this.docs.forEach((d) => d.destroy());
    this.docs.clear();
    this.recs.clear();
    this.pending.clear();
    this.set({ pending: 0, roles: {}, publicPages: {} });
  }

  private connect() {
    if (!this.identity || this.ws) return;
    this.set({ connection: 'connecting' });
    const ws = new WebSocket(wsUrl());
    this.ws = ws;
    ws.onopen = async () => {
      this.retry = 0;
      this.set({ connection: 'online' });
      this.pingTimer = setInterval(() => this.wsSend({ type: 'ping' }), PING_MS);
      try {
        await this.refreshPages();
      } catch (e) {
        console.warn('[vellune] page list refresh failed', e);
      }
    };
    ws.onmessage = (ev) => {
      try {
        void this.onMessage(JSON.parse(ev.data));
      } catch {
        /* ignore malformed frames */
      }
    };
    ws.onclose = () => {
      if (this.pingTimer) clearInterval(this.pingTimer);
      this.ws = null;
      this.subscribed.clear();
      this.inflight.clear();
      this.set({ presence: {} });
      if (!this.identity) return;
      this.set({ connection: 'offline' });
      // Exponential backoff with jitter so a server restart is not met by a reconnect storm.
      const delay = Math.min(30_000, 500 * 2 ** this.retry++) * (0.5 + Math.random());
      this.reconnectTimer = setTimeout(() => this.connect(), delay);
    };
  }

  private wsSend(m: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(m));
      return true;
    }
    return false;
  }

  /** Sync the page list: unwrap keys, register local-only pages, adopt pages shared with us. */
  async refreshPages() {
    const me = this.identity;
    if (!me || !this.bridge) return;
    const rows = await req<ServerPage[]>('GET', '/pages');
    const seen = new Set<string>();
    const roles: Record<string, Role> = {};
    const publicPages: Record<string, boolean> = {};
    for (const r of rows) {
      seen.add(r.id);
      try {
        this.keys.set(r.id, await unwrapPageKey(r.wrapped_key, me));
      } catch {
        console.warn('[vellune] cannot unwrap key for page', r.id);
        continue;
      }
      roles[r.id] = r.role;
      publicPages[r.id] = r.public;
      this.doc(r.id);
      const rec = this.recs.get(r.id)!;
      rec.registered = true;
      rec.role = r.role;
      this.persist(r.id);
    }
    for (const rec of [...this.recs.values()]) {
      if (seen.has(rec.id) || !rec.registered) continue;
      if (rec.role === 'owner') {
        // Server lost a page we own: re-upload instead of deleting local data.
        rec.registered = false;
        rec.cursor = 0;
      } else {
        // Access to a shared page was revoked.
        this.forget(rec.id);
        this.bridge.removeLocal(rec.id);
      }
    }
    for (const rec of [...this.recs.values()]) {
      if (!rec.registered && this.bridge.pages()[rec.id]) {
        try {
          await this.register(rec.id);
          roles[rec.id] = 'owner';
          publicPages[rec.id] = false;
        } catch (e) {
          console.warn('[vellune] registering page failed', rec.id, e);
        }
      }
    }
    this.set({ roles, publicPages });
    // Edits made offline/locked: one full-state update merges idempotently on every peer.
    for (const rec of this.recs.values()) {
      if (rec.unsynced && this.keys.has(rec.id) && rec.role !== 'viewer') {
        rec.unsynced = false;
        this.persist(rec.id);
        this.enqueue(rec.id, Y.encodeStateAsUpdate(this.doc(rec.id)));
      }
    }
    for (const id of this.keys.keys()) this.subscribePage(id);
  }

  private async register(id: string) {
    const me = this.identity!;
    const key = await newPageKey();
    await req('POST', '/pages', { id, wrapped_key: await wrapPageKey(key, me.publicKey) });
    this.keys.set(id, key);
    const rec = this.recs.get(id)!;
    rec.registered = true;
    rec.role = 'owner';
    this.persist(id);
    // Full state becomes the first update; anything queued before registration is included.
    for (const [opId, op] of this.pending) if (op.pageId === id) this.dropPending(opId);
    this.enqueue(id, Y.encodeStateAsUpdate(this.doc(id)));
  }

  private forget(id: string) {
    this.docs.get(id)?.destroy();
    this.docs.delete(id);
    this.recs.delete(id);
    this.keys.delete(id);
    idbDel('docs', id).catch(() => {});
    for (const [opId, op] of this.pending) if (op.pageId === id) this.dropPending(opId);
  }

  private subscribePage(id: string) {
    if (this.subscribed.has(id) || !this.wsSend({ type: 'subscribe', page_id: id, after: this.recs.get(id)?.cursor ?? 0 })) return;
    this.subscribed.add(id);
    for (const op of this.pending.values()) if (op.pageId === id) void this.send(op);
  }

  private async send(op: PendingOp) {
    const key = this.keys.get(op.pageId);
    if (!key || !this.subscribed.has(op.pageId) || this.inflight.has(op.opId)) return;
    this.inflight.add(op.opId);
    const payload = b64(await seal(key, op.update as Uint8Array<ArrayBuffer>));
    if (!this.wsSend({ type: 'push', page_id: op.pageId, op_id: op.opId, payload })) this.inflight.delete(op.opId);
  }

  private dropPending(opId: string) {
    this.pending.delete(opId);
    this.inflight.delete(opId);
    idbDel('pending', opId).catch(() => {});
    this.set({ pending: this.pending.size });
  }

  private async onMessage(m: Record<string, any>) {
    switch (m.type) {
      case 'updates': {
        const key = this.keys.get(m.page_id);
        const doc = this.docs.get(m.page_id);
        const rec = this.recs.get(m.page_id);
        if (!key || !doc || !rec) return;
        const plain: Uint8Array[] = [];
        for (const u of m.updates as { seq: number; payload: string }[]) {
          try {
            plain.push(await open(key, unb64(u.payload)));
          } catch {
            console.warn('[vellune] dropping undecryptable update', m.page_id, u.seq);
          }
        }
        // Apply and re-render synchronously so a local diff never runs against a half-applied batch.
        for (const u of plain) Y.applyUpdate(doc, u, REMOTE);
        rec.cursor = Math.max(rec.cursor, m.cursor);
        this.persist(m.page_id);
        const page = docToPage(doc);
        if (page) this.bridge?.applyRemote(page);
        return;
      }
      case 'ack':
        this.dropPending(m.op_id);
        return;
      case 'presence':
        this.set({ presence: { ...this.state.presence, [m.page_id]: m.users } });
        return;
      case 'error':
        if (m.op_id && m.code === 'invalid') {
          // Cannot succeed on retry (e.g. larger than the server's max_update_bytes).
          console.error('[vellune] server rejected update', m.page_id, m.message);
          this.dropPending(m.op_id);
        } else if (m.op_id) {
          // forbidden / internal: keep it; retried on the next reconnect.
          this.inflight.delete(m.op_id);
        }
        if (m.page_id && m.code === 'forbidden' && !m.op_id) this.subscribed.delete(m.page_id);
        return;
    }
  }

  // ---------- sharing ----------

  pageKey = (id: string) => this.keys.get(id);

  /** Owner deleted the page for everyone. */
  async deleteRemote(id: string) {
    const rec = this.recs.get(id);
    if (this.online && rec?.registered && rec.role === 'owner') await req('DELETE', `/pages/${id}`).catch(() => {});
    this.forget(id);
  }

  async publicLink(id: string): Promise<string> {
    const key = this.keys.get(id);
    if (!key) throw new Error('Page is not synced yet');
    return `${location.origin}/share/${id}#k=${await exportPageKey(key)}`;
  }

  setPublicFlag(id: string, value: boolean) {
    this.set({ publicPages: { ...this.state.publicPages, [id]: value } });
  }
}

export const sync = new SyncEngine();

export function useSync<T>(select: (s: SyncState) => T): T {
  return useSyncExternalStore(sync.subscribe, () => select(sync.get()));
}

/** Read-only live view of a public page using the key from the link fragment. */
export function openPublicPage(pageId: string, rawKey: string, onPage: (p: Page | null, error?: string) => void): () => void {
  let closed = false;
  let ws: WebSocket | null = null;
  let retry = 0;
  const doc = new Y.Doc();
  const keyP = importPageKey(rawKey);
  let cursor = 0;
  const connect = () => {
    if (closed) return;
    ws = new WebSocket(wsUrl());
    ws.onopen = () => {
      retry = 0;
      ws!.send(JSON.stringify({ type: 'subscribe', page_id: pageId, after: cursor }));
    };
    ws.onmessage = async (ev) => {
      const m = JSON.parse(ev.data);
      if (m.type === 'error' && m.page_id === pageId) return onPage(null, m.message);
      if (m.type !== 'updates' || m.page_id !== pageId) return;
      try {
        const key = await keyP;
        for (const u of m.updates) Y.applyUpdate(doc, await open(key, unb64(u.payload)));
        cursor = m.cursor;
        onPage(docToPage(doc));
      } catch {
        onPage(null, 'This link’s key cannot decrypt the page.');
      }
    };
    ws.onclose = () => {
      if (!closed) setTimeout(connect, Math.min(30_000, 500 * 2 ** retry++));
    };
  };
  keyP.then(connect, () => onPage(null, 'This link is missing its decryption key.'));
  return () => {
    closed = true;
    ws?.close();
    doc.destroy();
  };
}
