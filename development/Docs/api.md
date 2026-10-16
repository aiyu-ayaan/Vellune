# Vellune HTTP & WebSocket API (v1)

> **Status:** Implemented by `services/api` (`vellune-api`). Contract for web, desktop, and admin clients.
> **Base path:** `/api/v1`. All bodies are JSON. Binary values are standard base64 strings.

Related: [`security.md`](security.md) (E2E key model), [`sync-protocol.md`](sync-protocol.md) (wire format), ADR [0002](adr/0002-e2e-encrypted-sync.md).

---

## 1. Conventions

- **Auth:** opaque session token in the `vellune_session` cookie (`HttpOnly`, `SameSite=Lax`, `Secure` when `APP_URL` is https). Clients call with `credentials: 'include'`; in dev, Vite proxies `/api` (HTTP + WS) to the API so the app is same-origin.
- **CSRF:** every non-GET request must send `Content-Type: application/json` (or no body) **and** the header `X-Vellune: 1`. Cross-site forms cannot set either.
- **Errors:** non-2xx responses carry `{ "error": { "code": "snake_case", "message": "human text" } }`.
  Common codes: `unauthorized` (401), `forbidden` (403), `not_found` (404), `conflict` (409), `invalid` (400), `signups_disabled` (403), `rate_limited` (429).
- **IDs:** UUID strings. **Times:** RFC 3339 strings.

### Shared objects

```ts
type User = { id: string; email: string; name: string; is_admin: boolean; created_at: string };

// E2E key bundle. The server stores it but can never use it.
type KeyBundle = {
  kdf_salt: string;            // base64, 16 bytes
  kdf_iterations: number;      // PBKDF2-SHA256 iterations, >= 600000
  public_key: JsonWebKey;      // ECDH P-256 public key
  wrapped_private_key: string; // base64(iv[12] || AES-GCM(pkcs8 private key))
};

type Role = 'owner' | 'editor' | 'viewer';
```

---

## 2. Authentication

The login password never leaves the browser. The client derives
`master = PBKDF2-SHA256(password, kdf_salt, kdf_iterations)` and sends only
`auth_hash = base64(HKDF(master, info="vellune-auth"))`. The server stores `argon2id(auth_hash)`.
The vault key `HKDF(master, info="vellune-vault")` unwraps the private key client-side.

| Method & path | Body | Response |
|---|---|---|
| `POST /auth/prelogin` | `{ email }` | `{ kdf_salt, kdf_iterations }` — unknown emails get a stable fake salt (no account enumeration). |
| `POST /auth/signup` | `{ email, name, auth_hash, keys: KeyBundle }` | `{ user: User, keys: KeyBundle }` + cookie. First account on a fresh server becomes admin. |
| `POST /auth/login` | `{ email, auth_hash }` | `{ user, keys }` + cookie. Generic `unauthorized` on any mismatch. |
| `POST /auth/logout` | – | `204`, cookie cleared, session deleted. |
| `GET /auth/me` | – | `{ user, keys: KeyBundle \| null }`. `keys` is null for OAuth users who have not set an encryption passphrase yet. |
| `PUT /auth/keys` | `KeyBundle` | `{ keys }`. Only when the user has no keys (OAuth first run), else `409 conflict`. |
| `GET /auth/providers` | – | `[{ id: 'github' \| 'google', name }]` — enabled providers only; public. |
| `GET /auth/oauth/{provider}/start` | – | `302` to provider. |
| `GET /auth/oauth/{provider}/callback` | – | `302` to `APP_URL/` with session cookie set, or `APP_URL/?auth_error=<code>`. |

OAuth links to an existing account only by a **verified** provider email.

---

## 3. Pages, sharing, permissions

Every page has its own random AES-256-GCM **page key**. The server sees page IDs, roles, and ciphertext only — titles and content are inside the encrypted updates.
`wrapped_key` is a page key encrypted to a member's ECDH public key:
`base64(JSON{ epk: JsonWebKey, ct: base64(iv[12] || AES-GCM ciphertext) })` (ephemeral ECDH → HKDF → AES-GCM).

| Method & path | Who | Body | Response |
|---|---|---|---|
| `GET /pages` | user | – | `[{ id, role, wrapped_key, owner_id, public, created_at }]` |
| `POST /pages` | user | `{ id, wrapped_key }` | the row above (`role: 'owner'`). Idempotent for the same owner. |
| `DELETE /pages/{id}` | owner | – | `204`; deletes updates and members. |
| `GET /pages/{id}/members` | member | – | `[{ user: { id, email, name }, role }]` |
| `PUT /pages/{id}/members` | owner | `{ user_id, role: 'editor' \| 'viewer', wrapped_key }` | `204` (upsert). |
| `DELETE /pages/{id}/members/{user_id}` | owner | – | `204`. |
| `PUT /pages/{id}/public` | owner | `{ public: boolean }` | `204`. Public pages are readable (not writable) by anyone holding the link; the page key travels in the URL fragment (`/share/{id}#k=...`), never to the server. |
| `GET /pages/{id}/history?after=0&limit=500` | member | – | `{ updates: [{ seq, at, user: { id, name } \| null, payload }], more }`. The encrypted update log for client-side version history (limit ≤ 1000; page with `after` = last `seq`). |
| `GET /users/lookup?email=` | user | – | `{ id, email, name, public_key }` or `404` (user must have signed up and set keys). |

Server enforcement: only `owner`/`editor` may push updates; `viewer` and public readers are read-only. Revoking a member stops future access; they keep anything already decrypted (no key rotation yet).

---

## 4. Admin (requires `is_admin`)

| Method & path | Body | Response |
|---|---|---|
| `GET /admin/stats` | – | `{ users, admins, disabled_users, pages, updates, update_bytes, active_sessions, online_connections }` |
| `GET /admin/users?q=&offset=0&limit=50` | – | `{ users: AdminUser[], total }` |
| `PATCH /admin/users/{id}` | `{ is_admin?, disabled?, name? }` | `AdminUser`. Admins cannot demote or disable themselves (`invalid`). Disabling revokes sessions. |
| `DELETE /admin/users/{id}` | – | `204`. Cannot delete self. Deletes owned pages. |
| `DELETE /admin/users/{id}/sessions` | – | `204` — sign the user out everywhere. |
| `GET /admin/oauth` | – | `OAuthProviderConfig[]` (always both providers) |
| `PUT /admin/oauth/{provider}` | `{ enabled, client_id, client_secret? }` | `OAuthProviderConfig`. Omit `client_secret` to keep the stored one. Enabling requires both id and secret. |
| `GET /admin/settings` | – | `ServerSettings` |
| `PUT /admin/settings` | `ServerSettings` | `ServerSettings` |
| `GET /admin/audit?limit=100` | – | `AuditEvent[]` newest first |

```ts
type AdminUser = User & { disabled: boolean; providers: string[]; pages: number; has_keys: boolean };

type OAuthProviderConfig = {
  provider: 'github' | 'google';
  enabled: boolean;
  client_id: string;
  has_secret: boolean;     // the secret is never returned
  redirect_uri: string;    // paste into the provider console
};

type ServerSettings = {
  allow_signups: boolean;
  allowed_email_domains: string[]; // empty = any domain
  app_url: string;                 // e.g. http://localhost:3000
  max_update_bytes: number;        // per pushed update, 1 KiB .. 8 MiB
};

type AuditEvent = { id: number; actor_email: string | null; action: string; target: string | null; created_at: string };
```

Audit actions: `user.signup`, `user.login_oauth`, `admin.user_update`, `admin.user_delete`, `admin.sessions_revoke`, `admin.oauth_update`, `admin.settings_update`, `page.share`, `page.unshare`, `page.public`.

---

## 5. WebSocket sync — `GET /api/v1/sync`

Upgrade authenticates with the session cookie; without one the socket is anonymous and may only subscribe to public pages. The cookie only counts when `Origin` equals `APP_URL` or is same-origin (its host equals the `Host` header, e.g. `127.0.0.1` or a LAN IP in dev); a cross-site Origin gets an anonymous socket. The first server frame is `hello`, so a client that expected to be signed in can stop instead of retrying. JSON text frames, tagged by `type`. Full semantics in [`sync-protocol.md`](sync-protocol.md).

```ts
// client -> server
{ type: 'subscribe', page_id, after: number }        // after = last seq applied (0 = from start)
{ type: 'unsubscribe', page_id }
{ type: 'push', page_id, op_id, payload }             // payload = base64(iv[12] || AES-GCM(page key, Yjs update))
{ type: 'awareness', page_id, payload }               // ≤ 1 KiB; base64(iv || AES-GCM(page key, JSON cursor)); members only, never stored
{ type: 'ping' }

// server -> client
{ type: 'hello', authenticated: boolean }              // first frame on every connection
{ type: 'updates', page_id, updates: [{ seq, payload }], cursor }
{ type: 'ack', page_id, op_id, seq }
{ type: 'presence', page_id, users: [{ id, name }], conns: number[] }   // conns: live connection ids in the room
{ type: 'awareness', page_id, conn, user: { id, name }, payload }       // a collaborator's cursor; not echoed to its sender
{ type: 'error', code, message, page_id?, op_id? }
{ type: 'pong' }
```
