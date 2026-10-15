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
- Authentication generates cryptographically secure, signed session tokens (JWT or opaque bearer tokens stored in Redis with short lifetimes).
- Revocation of a user session immediately terminates WebSocket sync connections and invalidates cached credentials.

### Access Control Matrix
| Role | Read Pages | Edit Pages | Invite Members | Manage Billing / Delete Workspace |
|---|---|---|---|---|
| **Owner** | Yes | Yes | Yes | Yes |
| **Admin** | Yes | Yes | Yes | No |
| **Editor** | Yes | Yes | No | No |
| **Viewer** | Yes | No | No | No |

---

## 3. Storage & Attachment Security

- **Direct-to-S3 Uploads:** Files are never routed directly through the API server memory. Clients request presigned PUT URLs with pre-enforced size limits and content-type restrictions.
- **Orphan File Garbage Collection:** Attachments unlinked from documents are retained for a 30-day recovery window before automated deletion.
- **Binary Content Isolation:** Attachment downloads are served from distinct CDN/storage subdomains with sandboxed `Content-Security-Policy` headers to prevent stored XSS attacks.

---

## 4. Privacy & Auditing

- **Telemetry & Logs:** Production server logs explicitly exclude user note content, document titles, passwords, and authorization tokens.
- **Audit Trails:** Sensitive administrative operations (member invites, role alterations, workspace deletions) generate tamper-evident audit logs.
