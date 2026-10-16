# Self-hosting Vellune

One Docker image runs the API, the WebSocket sync gateway (`/api/v1/sync`), and the web app on a single origin. The only other service is PostgreSQL 16. Redis is not used.

## Requirements

- A Linux server with Docker Engine and the Compose plugin.
- About 1 GB RAM; the image is about 150 MB.
- A domain name and TLS for anything beyond local testing (browsers need HTTPS for the Web Crypto features Vellune uses on non-localhost origins).

## Quick start

```sh
git clone https://github.com/vellune/vellune.git && cd vellune/deploy
cp .env.example .env
# edit .env: POSTGRES_PASSWORD, VELLUNE_SECRET, APP_URL
docker compose up -d
```

Generate secrets with `openssl rand -hex 32` (`VELLUNE_SECRET`) and `openssl rand -hex 24` (`POSTGRES_PASSWORD`). Open `APP_URL`. The first account created on a fresh database becomes the admin.

`APP_URL` must be the exact public origin, for example `https://notes.example.com`, with no trailing slash. It drives the OAuth redirect URI, the cookie `Secure` flag (https only), and the WebSocket `Origin` check. A mismatch makes sync fail.

Do not change `VELLUNE_SECRET` after first start. The server refuses to start without one (at least 32 characters).

## Admin panel

`${APP_URL}/admin` (admins only):

- **OAuth:** register an app at GitHub or Google with redirect URI `${APP_URL}/api/v1/auth/oauth/github/callback` or `${APP_URL}/api/v1/auth/oauth/google/callback`, then enter client ID and secret.
- **Server settings:** toggle open signups, restrict signups to allowed email domains, override the public URL.
- Users, sessions, and the audit log.

## HTTPS and reverse proxies

Easiest: Caddy. In `deploy/docker-compose.yml` uncomment the `caddy` service and volumes, set your domain in `deploy/Caddyfile`, and bind the app to loopback (`127.0.0.1:8080:8080`) or drop its `ports`. Caddy gets certificates automatically.

Any other proxy (nginx, Traefik, cloud LB) must:

- forward the WebSocket upgrade for `/api/v1/sync` (`Upgrade` and `Connection` headers, long read timeout);
- pass the `Host` header and the browser's `Origin` header unchanged;
- serve the site at exactly the origin in `APP_URL`.

## Backups

```sh
docker compose exec -T postgres pg_dump -U vellune vellune | gzip > vellune-$(date +%F).sql.gz
```

Restore into an empty database with `gunzip -c file.sql.gz | docker compose exec -T postgres psql -U vellune vellune`. Back up the `.env` file too (`VELLUNE_SECRET`).

Page titles and content are ciphertext, so a leaked dump does not expose notes. It is also useless without each user's password: the server cannot decrypt anything and cannot reset a password. Losing a password means losing that user's notes.

## Upgrading

```sh
cd vellune && git pull
cd deploy && docker compose up -d --build
```

Database migrations are embedded in the binary and run on start. Take a backup first.

## Security notes

- End-to-end encrypted: the server stores, orders, authorizes, and relays ciphertext only. See [ADR 0002](adr/0002-e2e-encrypted-sync.md) and [`security.md`](security.md).
- Server-side search, previews, and merging of content are not possible by design.
- Only expose port 80/443 via the proxy; keep PostgreSQL unpublished (the compose file does not publish it).
- The container runs as a non-root user. `/healthz` returns `ok` and backs the Docker healthcheck.

## Known limitations

- `Cargo.lock` is gitignored, so image builds resolve dependency versions fresh; two builds can differ.
- No pre-built registry image yet; you build from source (first build takes a few minutes).
