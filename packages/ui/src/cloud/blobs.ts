// Encrypted image blobs. Bytes are sealed with the page key before upload, so the server's
// blob store (BLOB_DIR) only ever holds ciphertext. Blocks reference them as `vblob:<id>:<mime>`.

import { open, seal } from './crypto';
import { sync } from './sync';

const PREFIX = 'vblob:';
export const isBlobRef = (src?: string) => !!src?.startsWith(PREFIX);

const url = (pageId: string, id?: string) => `/api/v1/pages/${encodeURIComponent(pageId)}/blobs${id ? `/${encodeURIComponent(id)}` : ''}`;

/** Upload `data` sealed with the page key; null when the page is not in the cloud or upload fails. */
export async function uploadBlob(pageId: string, data: Blob): Promise<string | null> {
  const key = sync.pageKey(pageId);
  if (!key) return null;
  try {
    const sealed = await seal(key, new Uint8Array(await data.arrayBuffer()));
    const res = await fetch(url(pageId), {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/octet-stream', 'X-Vellune': '1' },
      body: sealed,
    });
    if (!res.ok) return null;
    const { id } = (await res.json()) as { id: string };
    return `${PREFIX}${id}:${data.type || 'application/octet-stream'}`;
  } catch {
    return null;
  }
}

// shortcut: in-memory cache only, refetched each session; add an IndexedDB cache for offline viewing.
const cache = new Map<string, Promise<string>>();

/** Object URL of a decrypted blob ref. */
export function blobObjectUrl(pageId: string, ref: string, key: CryptoKey | undefined): Promise<string> {
  const hit = cache.get(ref);
  if (hit) return hit;
  const [id, ...mime] = ref.slice(PREFIX.length).split(':');
  const p = (async () => {
    if (!key) throw new Error('No key for this page');
    const res = await fetch(url(pageId, id), { credentials: 'include' });
    if (!res.ok) throw new Error(`Image unavailable (${res.status})`);
    const plain = await open(key, new Uint8Array(await res.arrayBuffer()));
    return URL.createObjectURL(new Blob([plain], { type: mime.join(':') }));
  })();
  cache.set(ref, p);
  p.catch(() => cache.delete(ref));
  return p;
}
