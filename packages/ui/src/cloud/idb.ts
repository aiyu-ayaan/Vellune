// Tiny promise wrapper over the `vellune-cloud` IndexedDB: identity keys, Yjs states, pending ops.

const NAME = 'vellune-cloud';
export type StoreName = 'kv' | 'docs' | 'pending';

let dbp: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((resolve, reject) => {
    const r = indexedDB.open(NAME, 1);
    r.onupgradeneeded = () => {
      r.result.createObjectStore('kv');
      r.result.createObjectStore('docs', { keyPath: 'id' });
      r.result.createObjectStore('pending', { keyPath: 'opId' });
    };
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => {
      dbp = null;
      reject(r.error);
    };
  });
  return dbp;
}

const wrap = <T>(r: IDBRequest<T>) =>
  new Promise<T>((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });

async function os(name: StoreName, mode: IDBTransactionMode = 'readonly') {
  return (await db()).transaction(name, mode).objectStore(name);
}

export const idbGet = async <T>(s: StoreName, key: string) => wrap<T | undefined>((await os(s)).get(key));
export const idbAll = async <T>(s: StoreName) => wrap<T[]>((await os(s)).getAll());
export const idbPut = async (s: StoreName, value: unknown, key?: string) => {
  await wrap((await os(s, 'readwrite')).put(value, key));
};
export const idbDel = async (s: StoreName, key: string) => {
  await wrap((await os(s, 'readwrite')).delete(key));
};
export const idbClear = async () => {
  for (const s of ['kv', 'docs', 'pending'] as const) await wrap((await os(s, 'readwrite')).clear());
};
