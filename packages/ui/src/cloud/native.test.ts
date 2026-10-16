// Contract with the native Android client (crates/mobile + apps/android): both must read each
// other's Yjs documents and E2E crypto. `WRITE_FIXTURES=1 pnpm --filter @vellune/ui test` rewrites
// the web-side fixtures; the Rust and Kotlin tests read them.

// @ts-expect-error -- no @types/node in this package; vitest runs on Node.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import * as Y from 'yjs';
import { b64, createKeys, derive, exportPageKey, newPageKey, seal, unb64, wrapPageKey } from './crypto';
import { docToPage, pageToDoc } from './ydoc';
import { Page } from '../model';

declare const process: { env: Record<string, string | undefined> };

const root = new URL('../../../../', import.meta.url);
const fixture = (name: string) => new URL(`crates/mobile/tests/fixtures/${name}`, root);
const write = process.env.WRITE_FIXTURES === '1';

export const contractPage: Page = {
  id: '0b8f3c2e-1d4a-4c6b-9a7e-5f2d1c0b9a8e',
  title: 'Shared from the web 🌙',
  blocks: [
    { id: 'b1', kind: 'heading', level: 1, text: 'Moonlit notes' },
    { id: 'b2', kind: 'todo', checked: true, text: 'Write with a pen 😀' },
    { id: 'b3', kind: 'code', language: 'rust', text: 'fn main() {}' },
  ],
  tags: ['canvas', 'guide'],
  aliases: ['moon'],
  favorite: false,
  canvas: {
    items: [{ id: 'note', type: 'note', x: 0, y: 0, w: 640, h: 480 }],
    strokes: [{ id: 's1', points: [10, 20, 0.5, 30.5, 40, 0.75], color: '#5b53a8', width: 3 }],
  },
  createdAt: 1760600000000,
  updatedAt: 1760600123456,
  parentId: 'folder-1',
  order: 2,
};

describe('native client contract', () => {
  it('web document decodes in Rust (fixture written for crates/mobile)', () => {
    const doc = new Y.Doc();
    pageToDoc(doc, contractPage);
    const bytes = Y.encodeStateAsUpdate(doc);
    if (write) {
      writeFileSync(fixture('web_page.bin'), bytes);
      writeFileSync(fixture('web_page.json'), JSON.stringify(contractPage, null, 2));
    }
    expect(existsSync(fixture('web_page.bin'))).toBe(true);
  });

  it('reads the document the Rust core writes', () => {
    const file = fixture('rust_page.bin');
    const doc = new Y.Doc();
    Y.applyUpdate(doc, new Uint8Array(readFileSync(file)));
    expect(docToPage(doc)).toMatchObject(JSON.parse(readFileSync(fixture('rust_page.json'), 'utf8')));
  });

  it('writes crypto fixtures the Kotlin client must open', async () => {
    if (!write) return;
    const password = 'correct horse battery staple';
    const { bundle } = await createKeys(password);
    const { authHash } = await derive(password, unb64(bundle.kdf_salt), bundle.kdf_iterations);
    const pageKey = await newPageKey();
    const plain = new TextEncoder().encode('sealed by the web');
    writeFileSync(
      new URL('apps/android/app/src/test/resources/web-crypto.json', root),
      JSON.stringify({
        password,
        bundle,
        authHash,
        wrappedPageKey: await wrapPageKey(pageKey, bundle.public_key),
        pageKey: await exportPageKey(pageKey),
        sealed: b64(await seal(pageKey, plain)),
        plain: 'sealed by the web',
      }, null, 2),
    );
  });
});
