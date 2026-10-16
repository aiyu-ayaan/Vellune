// End-to-end encryption primitives (WebCrypto only). The server receives auth_hash, public keys,
// wrapped keys, and ciphertext — never passwords, private keys, page keys, or plaintext.
//
//   master    = PBKDF2-SHA256(password, kdf_salt, iterations)
//   auth_hash = HKDF(master, "vellune-auth")    -> sent to server (argon2id'd there)
//   vault key = HKDF(master, "vellune-vault")   -> AES-GCM, wraps the ECDH P-256 private key
//   page key  = random AES-256-GCM per page     -> encrypts Yjs updates
//   wrapped page key = ECDH(ephemeral, member public key) -> HKDF -> AES-GCM(page key)

export const KDF_ITERATIONS = 600_000;

const subtle = globalThis.crypto.subtle;
const enc = new TextEncoder();

export const b64 = (bytes: ArrayBuffer | Uint8Array): string => {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = '';
  for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000));
  return btoa(s);
};

export const unb64 = (s: string): Uint8Array<ArrayBuffer> => {
  const bin = atob(s);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
};

export const b64url = (bytes: ArrayBuffer | Uint8Array) => b64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export const unb64url = (s: string) => unb64(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));

export const randomBytes = (n: number) => crypto.getRandomValues(new Uint8Array(n));

async function hkdf(ikm: ArrayBuffer, info: string): Promise<ArrayBuffer> {
  const k = await subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits']);
  return subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: enc.encode(info) }, k, 256);
}

async function aesKey(raw: ArrayBuffer | Uint8Array<ArrayBuffer>, extractable = false): Promise<CryptoKey> {
  return subtle.importKey('raw', raw, 'AES-GCM', extractable, ['encrypt', 'decrypt']);
}

/** iv[12] || AES-GCM ciphertext */
export async function seal(key: CryptoKey, plain: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const iv = randomBytes(12);
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, key, plain));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv);
  out.set(ct, 12);
  return out;
}

export async function open(key: CryptoKey, sealed: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: sealed.subarray(0, 12) }, key, sealed.subarray(12)));
}

export interface Derived {
  authHash: string;
  vaultKey: CryptoKey;
}

/** Slow on purpose (~0.5–1 s): stretches the password before anything leaves the device. */
export async function derive(password: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<Derived> {
  // KDF params come from the server; a malicious one could otherwise ask for 1 round and brute-force the password.
  if (!Number.isInteger(iterations) || iterations < KDF_ITERATIONS || iterations > 10_000_000 || salt.length !== 16) {
    throw new Error('The server sent unsafe key-derivation parameters. Refusing to continue.');
  }
  const pw = await subtle.importKey('raw', enc.encode(password.normalize('NFKC')), 'PBKDF2', false, ['deriveBits']);
  const master = await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, pw, 256);
  const [auth, vault] = await Promise.all([hkdf(master, 'vellune-auth'), hkdf(master, 'vellune-vault')]);
  return { authHash: b64(auth), vaultKey: await aesKey(vault) };
}

export interface KeyBundle {
  kdf_salt: string;
  kdf_iterations: number;
  public_key: JsonWebKey;
  wrapped_private_key: string;
}

export interface Identity {
  privateKey: CryptoKey; // non-extractable, deriveBits only
  publicKey: JsonWebKey;
}

const ECDH = { name: 'ECDH', namedCurve: 'P-256' } as const;

/** New account keys, wrapped under a password/passphrase. Returns the bundle and auth hash. */
export async function createKeys(password: string): Promise<{ bundle: KeyBundle; authHash: string; identity: Identity }> {
  const salt = randomBytes(16);
  const { authHash, vaultKey } = await derive(password, salt, KDF_ITERATIONS);
  const pair = (await subtle.generateKey(ECDH, true, ['deriveBits'])) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await subtle.exportKey('pkcs8', pair.privateKey));
  const publicKey = await subtle.exportKey('jwk', pair.publicKey);
  const bundle: KeyBundle = {
    kdf_salt: b64(salt),
    kdf_iterations: KDF_ITERATIONS,
    public_key: { kty: publicKey.kty, crv: publicKey.crv, x: publicKey.x, y: publicKey.y },
    wrapped_private_key: b64(await seal(vaultKey, pkcs8)),
  };
  const privateKey = await subtle.importKey('pkcs8', pkcs8, ECDH, false, ['deriveBits']);
  pkcs8.fill(0);
  return { bundle, authHash, identity: { privateKey, publicKey: bundle.public_key } };
}

/** Throws when the vault key is wrong (AES-GCM authentication fails). */
export async function unlockKeys(bundle: KeyBundle, vaultKey: CryptoKey): Promise<Identity> {
  const pkcs8 = await open(vaultKey, unb64(bundle.wrapped_private_key));
  const privateKey = await subtle.importKey('pkcs8', pkcs8, ECDH, false, ['deriveBits']);
  pkcs8.fill(0);
  return { privateKey, publicKey: bundle.public_key };
}

// ---------- page keys ----------

export const newPageKey = (): Promise<CryptoKey> =>
  subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']) as Promise<CryptoKey>;

export const exportPageKey = async (k: CryptoKey) => b64url(await subtle.exportKey('raw', k));
export const importPageKey = (raw: string) => aesKey(unb64url(raw), true);

async function kek(priv: CryptoKey, pub: JsonWebKey): Promise<CryptoKey> {
  const pubKey = await subtle.importKey('jwk', pub, ECDH, false, []);
  const shared = await subtle.deriveBits({ name: 'ECDH', public: pubKey }, priv, 256);
  return aesKey(await hkdf(shared, 'vellune-page-key'));
}

/** Encrypt a page key to a member's public key (ephemeral-static ECDH). */
export async function wrapPageKey(pageKey: CryptoKey, recipient: JsonWebKey): Promise<string> {
  const eph = (await subtle.generateKey(ECDH, true, ['deriveBits'])) as CryptoKeyPair;
  const raw = new Uint8Array(await subtle.exportKey('raw', pageKey));
  const ct = await seal(await kek(eph.privateKey, recipient), raw);
  const epk = await subtle.exportKey('jwk', eph.publicKey);
  return b64(enc.encode(JSON.stringify({ epk: { kty: epk.kty, crv: epk.crv, x: epk.x, y: epk.y }, ct: b64(ct) })));
}

export async function unwrapPageKey(wrapped: string, me: Identity): Promise<CryptoKey> {
  const { epk, ct } = JSON.parse(new TextDecoder().decode(unb64(wrapped)));
  return aesKey(await open(await kek(me.privateKey, epk), unb64(ct)), true);
}

/** Short human-checkable fingerprint of a public key, for out-of-band verification. */
export async function fingerprint(pub: JsonWebKey): Promise<string> {
  const h = new Uint8Array(await subtle.digest('SHA-256', enc.encode(`${pub.x}.${pub.y}`)));
  return [...h.subarray(0, 8)].map((x) => x.toString(16).padStart(2, '0')).join('').replace(/(.{4})(?!$)/g, '$1 ');
}
