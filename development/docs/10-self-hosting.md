# 10 — Self-hosting

Goal: a person can run the whole server on a small VPS or home box with one command and no paid license.

## Deployment targets

1. **Single binary + SQLite + local files** (default). `vellune-server --data ./data`.
2. **Docker image** (static musl build, non-root user), one volume.
3. **Compose file** adding Postgres and S3-compatible storage (MinIO) for larger teams.

No Redis, queue, or separate search service is required for single-node.

## Configuration

Single TOML file plus environment overrides.

| Key | Purpose |
|---|---|
| `server.bind`, `server.public_url` | listen address, external URL used in invite links |
| `db.url` | `sqlite://...` or `postgres://...` |
| `blobs.backend` | `fs` or `s3` with endpoint/bucket/credentials |
| `auth.allow_signup` | open, invite-only, closed |
| `auth.oidc.*` | optional OIDC provider |
| `limits.*` | max update size, max blob size, per-user quota |
| `mail.smtp.*` | optional; invites work by link without mail |

## Operations

- `GET /healthz` and `/readyz`; Prometheus-style `/metrics` behind a token.
- `vellune-server backup <file>` and `restore <file>`: consistent snapshot of DB plus blob index; blobs copied or referenced.
- Online SQLite backup via the backup API, not file copy.
- Migrations run on start, forward-only, with a refusal to start on a newer DB than the binary understands.
- Structured logs (JSON optional). No telemetry unless explicitly enabled.

## Security defaults

- First user becomes owner via setup token printed at first start.
- Rate limits on auth and sync endpoints.
- Documented reverse proxy configs (Caddy, nginx) including WebSocket upgrade and body size.
- Upgrade notes and a versioned compatibility table between client and server protocol versions.

## Android client connection

- App asks for server URL, validates `/api/version`, then signs in. Supports self-signed CA via user-installed certificate (system trust store), no custom TLS bypass.
- Works fully offline with no server configured (local-only workspace) and can later be attached to a server.
