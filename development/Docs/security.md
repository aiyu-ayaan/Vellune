# Vellune Security & Privacy Architecture

> **Document Status:** Active  
> **Audience:** Developers, Contributors, Security Auditors

---

## 1. Security Architecture Principles

- **Zero-Trust Workspace Isolation:** Every API route and WebSocket subscription verifies tenant membership server-side. Workspace IDs in requests are checked against authenticated user session claims.
- **Local Data Protection:** Local SQLite databases on desktop and mobile support platform-native encryption at rest (e.g. SQLCipher, OS keychain key management).
- **Least Privilege Access:** Cloud credentials and object storage tokens are presigned, ephemeral, and strictly scoped to specific asset paths.

---

## 2. Authentication & Authorization

### Session Management
- Sessions are opaque 256-bit tokens in an `HttpOnly`, `SameSite=Lax` cookie (`Secure` on https). Only `sha256(token)` is stored in PostgreSQL; sessions last 30 days.
- Writes require the `X-Vellune: 1` header (CSRF). WebSocket upgrades with a foreign `Origin` are treated as anonymous.
- Revoking sessions, disabling a user, or changing their page role closes their live WebSocket connections immediately.
- Login and prelogin are rate limited per email (in-process; move to Redis for multiple instances).

### End-to-End Encryption
See [ADR 0002](adr/0002-e2e-encrypted-sync.md) and [`api.md`](api.md).
- The password never leaves the device. The client sends `auth_hash = HKDF(PBKDF2-SHA256(password, salt, 600k), "vellune-auth")`; the server stores `argon2id(auth_hash)`.
- Prelogin returns a stable HMAC-derived fake salt for unknown emails, so it does not reveal which accounts exist.
- Each account has an ECDH P-256 key pair; the private key is stored only wrapped under `HKDF(master, "vellune-vault")`. On the device it is a non-extractable `CryptoKey` in IndexedDB, wiped on sign-out.
- Each page has its own AES-256-GCM key, wrapped per member. Titles, blocks, and canvas data exist on the server only as ciphertext.
- Public links keep the page key in the URL fragment.
- **Limitations:** no account recovery; public keys are trust-on-first-use (fingerprints shown, not enforced); revocation does not rotate page keys; page IDs, membership, timing, and update sizes are visible to the server.
- OAuth client secrets are stored in plaintext in PostgreSQL and never returned by the API. Encrypt them with a KMS key before multi-tenant hosting.

### Access Control Matrix
Per-page roles (implemented):

| Role | Read | Edit | Share / publish / delete |
|---|---|---|---|
| **Owner** | Yes | Yes | Yes |
| **Editor** | Yes | Yes | No |
| **Viewer** | Yes | No (server rejects pushes) | No |
| **Public link** | Yes, with the key from the link | No | No |

Server admins (`users.is_admin`, first account on a fresh server) manage accounts, OAuth providers, and settings. They **cannot** read page content.

---

## 3. Storage & Attachment Security

**Implemented today (images):** clients downscale an image, seal it with the page key (AES-256-GCM), and upload the ciphertext to `POST /pages/{id}/blobs`. The API writes it to `BLOB_DIR/<page_id>/<blob_id>` (write-then-rename) and indexes it in the `blobs` table. The server never holds the key, so the files on disk, in backups, or in a bind-mounted folder are unreadable without the page key. Reads require page membership or a public page (whose key is only in the share link fragment). Blob files are removed when their page or owning user is deleted. Not yet: per-user quotas, orphan cleanup for images removed from a page, and S3 storage.

The target design for general attachments:

- **Direct-to-S3 Uploads:** Files are never routed directly through the API server memory. Clients request presigned PUT URLs with pre-enforced size limits and content-type restrictions.
- **Orphan File Garbage Collection:** Attachments unlinked from documents are retained for a 30-day recovery window before automated deletion.
- **Binary Content Isolation:** Attachment downloads are served from distinct CDN/storage subdomains with sandboxed `Content-Security-Policy` headers to prevent stored XSS attacks.

---

## 4. Privacy & Auditing

- **Telemetry & Logs:** Production server logs explicitly exclude user note content, document titles, passwords, and authorization tokens.
- **Audit Trails:** Sensitive administrative operations (member invites, role alterations, workspace deletions) generate tamper-evident audit logs.
