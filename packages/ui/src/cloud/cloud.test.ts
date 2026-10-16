import { describe, expect, it } from 'vitest';
import * as Y from 'yjs';
import { createKeys, derive, exportPageKey, importPageKey, newPageKey, open, seal, unb64, unlockKeys, unwrapPageKey, wrapPageKey } from './crypto';
import { docToPage, pageToDoc } from './ydoc';
import { newBlock, newPage } from '../model';

const enc = new TextEncoder();

describe('e2e crypto', () => {
  it('password derives the same auth hash and unlocks the private key; wrong password fails', async () => {
    const { bundle, authHash } = await createKeys('correct horse battery');
    const again = await derive('correct horse battery', unb64(bundle.kdf_salt), bundle.kdf_iterations);
    expect(again.authHash).toBe(authHash);
    await expect(unlockKeys(bundle, again.vaultKey)).resolves.toBeTruthy();
    const wrong = await derive('wrong password!!', unb64(bundle.kdf_salt), bundle.kdf_iterations);
    expect(wrong.authHash).not.toBe(authHash);
    await expect(unlockKeys(bundle, wrong.vaultKey)).rejects.toThrow();
    expect(JSON.stringify(bundle)).not.toContain('"d"'); // no private JWK material leaks
  });

  it('refuses weak KDF parameters a malicious server could send', async () => {
    await expect(derive('pw', new Uint8Array(16), 1)).rejects.toThrow(/unsafe/);
    await expect(derive('pw', new Uint8Array(4), 600_000)).rejects.toThrow(/unsafe/);
  });

  it('page key wrapped for a member is only unwrappable by that member', async () => {
    const alice = await createKeys('alice-password-1');
    const bob = await createKeys('bob-password-22');
    const key = await newPageKey();
    const wrapped = await wrapPageKey(key, bob.bundle.public_key);
    const bobKey = await unwrapPageKey(wrapped, bob.identity);
    const sealed = await seal(key, enc.encode('secret note'));
    expect(new TextDecoder().decode(await open(bobKey, sealed))).toBe('secret note');
    await expect(unwrapPageKey(wrapped, alice.identity)).rejects.toThrow();
  });

  it('public link key round-trips and tampered ciphertext is rejected', async () => {
    const key = await newPageKey();
    const same = await importPageKey(await exportPageKey(key));
    const sealed = await seal(key, enc.encode('hello'));
    expect(new TextDecoder().decode(await open(same, sealed))).toBe('hello');
    sealed[sealed.length - 1] ^= 1;
    await expect(open(same, sealed)).rejects.toThrow();
  });
});

describe('page <-> yjs', () => {
  const sync = (a: Y.Doc, b: Y.Doc) => {
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a, Y.encodeStateVector(b)));
    Y.applyUpdate(a, Y.encodeStateAsUpdate(b, Y.encodeStateVector(a)));
  };

  it('round-trips a page', () => {
    const page = newPage('Plan', { tags: ['x'], blocks: [newBlock('heading', 'Title', { level: 2 }), newBlock('todo', 'ship', { checked: true, color: 'red' })] });
    const doc = new Y.Doc();
    pageToDoc(doc, page);
    expect(docToPage(doc)).toEqual(page);
  });

  it('merges concurrent edits to different blocks and the same block text', () => {
    const base = newPage('Doc', { blocks: [newBlock('paragraph', 'hello world'), newBlock('paragraph', 'second')] });
    const a = new Y.Doc();
    pageToDoc(a, base);
    const b = new Y.Doc();
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a));

    const pa = docToPage(a)!;
    pageToDoc(a, { ...pa, blocks: [{ ...pa.blocks[0], text: 'hello brave world' }, pa.blocks[1]] });
    const pb = docToPage(b)!;
    pageToDoc(b, { ...pb, title: 'Renamed', blocks: [{ ...pb.blocks[0], text: 'hello world!' }, { ...pb.blocks[1], text: 'second line' }] });

    sync(a, b);
    const ra = docToPage(a)!;
    expect(ra).toEqual(docToPage(b));
    expect(ra.title).toBe('Renamed');
    expect(ra.blocks.map((x) => x.text)).toEqual(['hello brave world!', 'second line']);
  });

  it('keeps strokes added concurrently on two devices', () => {
    const base = newPage('Canvas');
    const a = new Y.Doc();
    pageToDoc(a, base);
    const b = new Y.Doc();
    Y.applyUpdate(b, Y.encodeStateAsUpdate(a));
    const stroke = (id: string) => ({ id, points: [0, 0, 1], color: '#000', width: 2 });
    pageToDoc(a, { ...base, canvas: { items: [], strokes: [stroke('s1')] } });
    pageToDoc(b, { ...base, canvas: { items: [], strokes: [stroke('s2')] } });
    sync(a, b);
    expect(docToPage(a)!.canvas.strokes.map((s) => s.id).sort()).toEqual(['s1', 's2']);
  });

  it('applies block insert, move, and delete as a minimal diff', () => {
    const [x, y, z] = [newBlock('paragraph', 'x'), newBlock('paragraph', 'y'), newBlock('paragraph', 'z')];
    const page = newPage('Order', { blocks: [x, y, z] });
    const doc = new Y.Doc();
    pageToDoc(doc, page);
    const w = newBlock('bullet', 'w');
    pageToDoc(doc, { ...page, blocks: [z, w, x] });
    expect(docToPage(doc)!.blocks.map((b) => b.text)).toEqual(['z', 'w', 'x']);
  });
});
