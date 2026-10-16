# syntax=docker/dockerfile:1.7
# Single self-hosted image: Rust API + built web app on one origin.
# Build from the repo root: docker build -f deploy/Dockerfile -t vellune .

# --- Stage 1: web app -------------------------------------------------------
FROM node:22-bookworm-slim AS web
ARG PNPM_VERSION=12.4.2
RUN npm install -g pnpm@${PNPM_VERSION}
WORKDIR /src
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
COPY packages/ui/package.json packages/ui/
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter @vellune/web...
COPY apps/web apps/web
COPY packages/ui packages/ui
RUN pnpm --filter @vellune/web build

# --- Stage 2: API binary ----------------------------------------------------
FROM rust:1-bookworm AS api
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY crates crates
COPY services services
COPY migrations migrations
# Cache mounts keep the registry and target dir between builds.
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release -p vellune-api \
 && cp target/release/vellune-api /vellune-api

# --- Stage 3: runtime -------------------------------------------------------
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home vellune
COPY --from=api /vellune-api /usr/local/bin/vellune-api
COPY --from=web /src/apps/web/dist /srv/vellune/web
ENV WEB_DIST=/srv/vellune/web \
    SERVER_HOST=0.0.0.0 \
    SERVER_PORT=8080 \
    RUST_LOG=info
USER vellune
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/healthz" || exit 1
CMD ["vellune-api"]
