# ADR 0002: End-to-end encrypted Yjs sync, served by the API process

- **Status:** Accepted
- **Date:** 2026-10-16

## Context

Vellune needs accounts, real-time multi-device editing, per-page sharing, and an admin panel. The product owner chose **true end-to-end encryption**: the server must not be able to read titles or content. (AFFiNE Cloud, the feature reference, does not do this; its server reads documents.)

E2E rules out server-side merging, server-side search of content, and server-side previews. The server can only store, order, authorize, and relay ciphertext.

## Decision

1. **CRDT:** Yjs on the client (`packages/ui/src/cloud/ydoc.ts`). Each page is one `Y.Doc`. Blocks are a `Y.Array` of `Y.Map`s with `Y.Text` content, and canvas items and strokes are keyed `Y.Map`s. Concurrent edits therefore merge per character, per field, and per stroke. Yjs updates are opaque bytes, so they can be encrypted without losing mergeability.
2. **Encryption** (`packages/ui/src/cloud/crypto.ts`, WebCrypto only):
   - Password → PBKDF2-SHA256 (600k iterations) → HKDF into `auth_hash`, which goes to the server, and a vault key, which stays on the device.
   - Each account has an ECDH P-256 key pair. The private key is wrapped under the vault key.
   - Each page has a random AES-256-GCM key. The page key is wrapped to each member's public key with ephemeral ECDH + HKDF + AES-GCM.
   - Every Yjs update is sent as `iv || AES-GCM(page key, update)`.
   - OAuth accounts set a separate encryption passphrase.
3. **Server** (`services/api`):
   - Stores argon2id(`auth_hash`), key bundles, the page ACL (`owner`/`editor`/`viewer`), and the ciphertext log `document_updates(seq, op_id)`.
   - Acknowledges a push only after the commit. Pushes are idempotent by `op_id`.
   - Serializes pushes per page so the `seq` order matches the fan-out order.
4. **Sync gateway lives in `vellune-api`** (`/api/v1/sync`) as one deployable, per ADR 0001. `services/sync` remains a placeholder until load justifies splitting it out.
5. **Public links** carry the page key in the URL fragment (`/share/{id}#k=…`), which browsers never send to the server.

## Consequences

- A lost password or passphrase means lost cloud data. The UI says so at signup, and there is no recovery key yet.
- **Public keys are trusted on first use.** A malicious server could substitute a public key when a page is shared. The share dialog shows a key fingerprint for out-of-band checking, but nothing enforces it.
- **Revocation does not rotate keys.** A removed member keeps whatever they already decrypted and the old page key. Future work: rotate the page key and re-encrypt a snapshot on revoke.
- **The update log grows without bound.** Clients must compact it later by pushing an encrypted snapshot, since the server cannot.
- **Content search and graph indexing must be client-side.** The server indexes nothing.
- **Fan-out is in-process.** Running more than one API instance needs Redis pub/sub plus a per-page DB lock.
- **An actively malicious server can substitute the wrapped page key** returned by `GET /pages`. The user's later edits would then be encrypted under a key the server knows. Mitigation (future): persist owned page keys locally and bind ciphertext to the page ID with AES-GCM AAD.
- **Updates larger than `max_update_bytes` are rejected and dropped by the client.** This includes a large page's first full-state upload. Future work: split large updates. For now, raise the limit in the admin panel (max 8 MiB).
- **Signup returns 409 for an existing email**, which reveals that the account exists; prelogin does not. Login rate limits are keyed per email, so an attacker can delay one user's logins for 5 minutes.
- **OAuth never links to an account that has a password.** Password signups do not verify email ownership, so linking would allow pre-account takeover. Such users must log in with their password.
- **Desktop (Tauri) does not sync yet.** Its webview origin cannot share the session cookie, and it needs a token-based transport.
