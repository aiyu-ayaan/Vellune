//! Encrypted blob store for images. Clients seal each file with the page key before upload, so
//! the server stores and serves ciphertext it cannot read. Files live under `BLOB_DIR`
//! (`<page_id>/<blob_id>`); the `blobs` table is the index used for access checks and cleanup.

use std::path::PathBuf;

use axum::{
    body::Bytes,
    extract::{Path, State},
    http::{header, HeaderMap},
    response::IntoResponse,
    Json,
};
use serde_json::{json, Value};
use uuid::Uuid;

use crate::{
    auth::{user_from_headers, AuthUser},
    error::{ApiError, ApiResult},
    pages::role_of,
    Shared,
};

/// Upper bound on one sealed blob (client downscales images well below this).
pub const MAX_BLOB_BYTES: usize = 8 * 1024 * 1024;

pub fn blob_dir() -> PathBuf {
    std::env::var("BLOB_DIR").unwrap_or_else(|_| "data/blobs".into()).into()
}

fn blob_path(st: &Shared, page: Uuid, id: Uuid) -> PathBuf {
    st.blob_dir.join(page.to_string()).join(id.to_string())
}

pub async fn upload(State(st): State<Shared>, user: AuthUser, Path(page): Path<Uuid>, body: Bytes) -> ApiResult<Json<Value>> {
    match role_of(&st.db, page, user.id).await?.as_deref() {
        Some("owner" | "editor") => {}
        Some(_) => return Err(ApiError::forbidden()),
        None => return Err(ApiError::not_found()),
    }
    // Sealed payload is at least a 12-byte IV plus a 16-byte GCM tag.
    if body.len() < 28 || body.len() > MAX_BLOB_BYTES {
        return Err(ApiError::invalid("Blob must be an encrypted payload of at most 8 MiB"));
    }
    st.limiter.check(&format!("blob:{}", user.id), 120, std::time::Duration::from_secs(60))?;
    let id = Uuid::new_v4();
    let path = blob_path(&st, page, id);
    let write = async {
        tokio::fs::create_dir_all(path.parent().unwrap()).await?;
        // Write then rename, so a crash never leaves a truncated blob under its final name.
        let tmp = path.with_extension("part");
        tokio::fs::write(&tmp, &body).await?;
        tokio::fs::rename(&tmp, &path).await
    };
    write.await.map_err(|e| {
        tracing::error!("blob write failed: {e}");
        ApiError::internal()
    })?;
    sqlx::query("INSERT INTO blobs (id, page_id, user_id, size) VALUES ($1, $2, $3, $4)")
        .bind(id)
        .bind(page)
        .bind(user.id)
        .bind(body.len() as i64)
        .execute(&st.db)
        .await?;
    Ok(Json(json!({ "id": id })))
}

/// Members of the page, or anyone when the page is public (the link's key decrypts it).
pub async fn download(State(st): State<Shared>, headers: HeaderMap, Path((page, id)): Path<(Uuid, Uuid)>) -> ApiResult<impl IntoResponse> {
    let member = match user_from_headers(&st.db, &headers).await? {
        Some(u) => role_of(&st.db, page, u.id).await?.is_some(),
        None => false,
    };
    let allowed = member
        || sqlx::query_scalar::<_, bool>("SELECT public FROM pages WHERE id = $1").bind(page).fetch_optional(&st.db).await?.unwrap_or(false);
    let known: bool = sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM blobs WHERE id = $1 AND page_id = $2)")
        .bind(id)
        .bind(page)
        .fetch_one(&st.db)
        .await?;
    if !allowed || !known {
        return Err(ApiError::not_found());
    }
    let bytes = tokio::fs::read(blob_path(&st, page, id)).await.map_err(|_| ApiError::not_found())?;
    Ok((
        [
            (header::CONTENT_TYPE, "application/octet-stream"),
            // Blobs are immutable: a new image always gets a new id.
            (header::CACHE_CONTROL, "private, max-age=31536000, immutable"),
        ],
        bytes,
    ))
}

/// Best-effort removal of a deleted page's blob directory (rows go with the page via CASCADE).
pub async fn remove_page(st: &Shared, page: Uuid) {
    let dir = st.blob_dir.join(page.to_string());
    if let Err(e) = tokio::fs::remove_dir_all(&dir).await {
        if e.kind() != std::io::ErrorKind::NotFound {
            tracing::warn!("could not remove blobs for page {page}: {e}");
        }
    }
}
