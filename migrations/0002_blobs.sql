-- Encrypted image/attachment blobs. Bytes live on disk under BLOB_DIR/<page_id>/<id>, sealed
-- client-side with the page key (iv || AES-GCM ciphertext); this table is the index for
-- authorization, quotas, and cleanup.
CREATE TABLE blobs (
    id         UUID PRIMARY KEY,
    page_id    UUID NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
    user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    size       BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX blobs_page ON blobs(page_id);
