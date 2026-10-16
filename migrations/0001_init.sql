-- Vellune cloud schema v1: accounts, E2E key bundles, per-page ACL, encrypted update log.
-- The server never holds plaintext titles or content: document_updates.payload is ciphertext.

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               TEXT NOT NULL UNIQUE,           -- lowercased
    name                TEXT NOT NULL,
    auth_hash           TEXT,                           -- argon2id(client auth hash); NULL for OAuth-only accounts
    is_admin            BOOLEAN NOT NULL DEFAULT FALSE,
    disabled            BOOLEAN NOT NULL DEFAULT FALSE,
    -- E2E key bundle (opaque to the server)
    kdf_salt            BYTEA,
    kdf_iterations      INTEGER,
    public_key          JSONB,
    wrapped_private_key TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oauth_accounts (
    provider         TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_user_id)
);
CREATE INDEX oauth_accounts_user ON oauth_accounts(user_id);

CREATE TABLE sessions (
    token_hash BYTEA PRIMARY KEY,                       -- sha256(token); the token itself is never stored
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE oauth_states (
    state         TEXT PRIMARY KEY,
    provider      TEXT NOT NULL,
    pkce_verifier TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oauth_providers (
    provider      TEXT PRIMARY KEY CHECK (provider IN ('github', 'google')),
    enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    client_id     TEXT NOT NULL DEFAULT '',
    client_secret TEXT NOT NULL DEFAULT ''
);
INSERT INTO oauth_providers (provider) VALUES ('github'), ('google');

CREATE TABLE server_settings (
    id    INTEGER PRIMARY KEY CHECK (id = 1),
    value JSONB NOT NULL
);

CREATE TABLE pages (
    id         UUID PRIMARY KEY,
    owner_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    public     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pages_owner ON pages(owner_id);

CREATE TABLE page_members (
    page_id     UUID NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
    wrapped_key TEXT NOT NULL,                          -- page key encrypted to the member's public key
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (page_id, user_id)
);
CREATE INDEX page_members_user ON page_members(user_id);

CREATE TABLE document_updates (
    seq        BIGSERIAL PRIMARY KEY,
    page_id    UUID NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
    op_id      UUID NOT NULL,
    user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    payload    BYTEA NOT NULL,                          -- iv || AES-GCM ciphertext of a Yjs update
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, op_id)
);
CREATE INDEX document_updates_page_seq ON document_updates(page_id, seq);

CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_email TEXT,
    action      TEXT NOT NULL,
    target      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_created ON audit_events(created_at DESC);
