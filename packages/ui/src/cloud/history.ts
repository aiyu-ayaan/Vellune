// Version history, Google Docs style: the encrypted update log is fetched, decrypted on the
// device, grouped into editing sessions, and any session's end state can be rebuilt and restored.

import * as Y from 'yjs';
import { Block, Page } from '../model';
import { req } from './http';
import { open, unb64 } from './crypto';
import { docToPage } from './ydoc';

export interface LoggedUpdate {
  seq: number;
  at: number;
  user: { id: string; name: string } | null;
  update: Uint8Array;
}

export interface Version {
  /** Last update included; the version's state is everything up to and including it. */
  seq: number;
  start: number;
  end: number;
  authors: { id: string; name: string }[];
  edits: number;
}

/** Edits with gaps under `gapMs` form one version, newest first; a negative gap lists every edit. */
export function groupVersions(log: Omit<LoggedUpdate, 'update'>[], gapMs = 5 * 60_000): Version[] {
  const out: Version[] = [];
  let cur: Version | null = null;
  for (const u of log) {
    if (!cur || u.at - cur.end > gapMs || gapMs < 0) {
      cur = { seq: u.seq, start: u.at, end: u.at, authors: [], edits: 0 };
      out.push(cur);
    }
    cur.seq = u.seq;
    cur.end = u.at;
    cur.edits++;
    if (u.user && !cur.authors.some((a) => a.id === u.user!.id)) cur.authors.push(u.user);
  }
  return out.reverse();
}

/** The page as it was right after update `seq`. */
export function pageAt(log: LoggedUpdate[], seq: number): Page | null {
  const doc = new Y.Doc();
  for (const u of log) {
    if (u.seq > seq) break;
    Y.applyUpdate(doc, u.update);
  }
  const page = docToPage(doc);
  doc.destroy();
  return page;
}

export type BlockChange = 'added' | 'changed';

/** Block-level diff of `next` against `prev`, plus blocks that `next` no longer has. */
export function diffBlocks(prev: Page | null, next: Page): { changes: Record<string, BlockChange>; removed: Block[] } {
  const before = new Map((prev?.blocks ?? []).map((b) => [b.id, b]));
  const changes: Record<string, BlockChange> = {};
  for (const b of next.blocks) {
    const old = before.get(b.id);
    if (!old) changes[b.id] = 'added';
    else if (JSON.stringify(old) !== JSON.stringify(b)) changes[b.id] = 'changed';
  }
  const keep = new Set(next.blocks.map((b) => b.id));
  return { changes, removed: (prev?.blocks ?? []).filter((b) => !keep.has(b.id)) };
}

/** Download and decrypt the whole log of a synced page. Undecryptable entries are skipped. */
export async function fetchLog(pageId: string, key: CryptoKey): Promise<LoggedUpdate[]> {
  const log: LoggedUpdate[] = [];
  let after = 0;
  for (;;) {
    const r = await req<{ updates: { seq: number; at: string; user: LoggedUpdate['user']; payload: string }[]; more: boolean }>(
      'GET',
      `/pages/${pageId}/history?after=${after}&limit=1000`,
    );
    for (const u of r.updates) {
      try {
        log.push({ seq: u.seq, at: Date.parse(u.at), user: u.user, update: await open(key, unb64(u.payload)) });
      } catch {
        /* wrong key / corrupt entry: leave it out of history */
      }
      after = u.seq;
    }
    if (!r.more) return log;
  }
}
