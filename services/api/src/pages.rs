//! Page registry, per-page sharing, and public links. Content stays encrypted; the server only
//! knows which user may read or write which page ID.

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::PgPool;
use uuid::Uuid;

use crate::{
    auth::{audit, normalize_email, AuthUser},
    error::{ApiError, ApiResult},
    Shared,
};

fn check_wrapped_key(k: &str) -> ApiResult<()> {
    if k.len() > 2048 || STANDARD.decode(k).is_err() {
        return Err(ApiError::invalid("wrapped_key must be base64, at most 2 KiB"));
    }
    Ok(())
}

/// Role of `user` on `page`, or None when they have no access.
pub async fn role_of(db: &PgPool, page: Uuid, user: Uuid) -> ApiResult<Option<String>> {
    Ok(sqlx::query_scalar("SELECT role FROM page_members WHERE page_id = $1 AND user_id = $2")
        .bind(page)
        .bind(user)
        .fetch_optional(db)
        .await?)
}

async fn require_owner(db: &PgPool, page: Uuid, user: &AuthUser) -> ApiResult<()> {
    match role_of(db, page, user.id).await?.as_deref() {
        Some("owner") => Ok(()),
        Some(_) => Err(ApiError::forbidden()),
        None => Err(ApiError::not_found()),
    }
}

const PAGE_ROW: &str = "SELECT p.id, m.role, m.wrapped_key, p.owner_id, p.public, p.created_at
     FROM page_members m JOIN pages p ON p.id = m.page_id WHERE m.user_id = $1";

type PageRow = (Uuid, String, String, Uuid, bool, chrono::DateTime<chrono::Utc>);

fn page_json((id, role, wrapped_key, owner_id, public, created_at): PageRow) -> Value {
    json!({ "id": id, "role": role, "wrapped_key": wrapped_key, "owner_id": owner_id, "public": public, "created_at": created_at })
}

pub async fn list(State(st): State<Shared>, user: AuthUser) -> ApiResult<Json<Vec<Value>>> {
    let rows: Vec<PageRow> = sqlx::query_as(sqlx::AssertSqlSafe(format!("{PAGE_ROW} ORDER BY p.created_at")))
        .bind(user.id)
        .fetch_all(&st.db)
        .await?;
    Ok(Json(rows.into_iter().map(page_json).collect()))
}

#[derive(Deserialize)]
pub struct CreateReq {
    id: Uuid,
    wrapped_key: String,
}

pub async fn create(State(st): State<Shared>, user: AuthUser, Json(r): Json<CreateReq>) -> ApiResult<Json<Value>> {
    check_wrapped_key(&r.wrapped_key)?;
    let mut tx = st.db.begin().await?;
    let created = sqlx::query("INSERT INTO pages (id, owner_id) VALUES ($1, $2) ON CONFLICT (id) DO NOTHING")
        .bind(r.id)
        .bind(user.id)
        .execute(&mut *tx)
        .await?
        .rows_affected()
        == 1;
    if created {
        sqlx::query("INSERT INTO page_members (page_id, user_id, role, wrapped_key) VALUES ($1, $2, 'owner', $3)")
            .bind(r.id)
            .bind(user.id)
            .bind(&r.wrapped_key)
            .execute(&mut *tx)
            .await?;
    }
    tx.commit().await?;
    // Retrying a create is fine; claiming someone else's page ID is not.
    let row: Option<PageRow> = sqlx::query_as(sqlx::AssertSqlSafe(format!("{PAGE_ROW} AND p.id = $2 AND m.role = 'owner'")))
        .bind(user.id)
        .bind(r.id)
        .fetch_optional(&st.db)
        .await?;
    row.map(|r| Json(page_json(r))).ok_or_else(|| ApiError::conflict("Page ID already in use"))
}

pub async fn delete(State(st): State<Shared>, user: AuthUser, Path(id): Path<Uuid>) -> ApiResult<StatusCode> {
    require_owner(&st.db, id, &user).await?;
    sqlx::query("DELETE FROM pages WHERE id = $1").bind(id).execute(&st.db).await?;
    crate::blobs::remove_page(&st, id).await;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn members(State(st): State<Shared>, user: AuthUser, Path(id): Path<Uuid>) -> ApiResult<Json<Vec<Value>>> {
    if role_of(&st.db, id, user.id).await?.is_none() {
        return Err(ApiError::not_found());
    }
    let rows: Vec<(Uuid, String, String, String)> = sqlx::query_as(
        "SELECT u.id, u.email, u.name, m.role FROM page_members m JOIN users u ON u.id = m.user_id
         WHERE m.page_id = $1 ORDER BY (m.role = 'owner') DESC, m.created_at",
    )
    .bind(id)
    .fetch_all(&st.db)
    .await?;
    Ok(Json(
        rows.into_iter()
            .map(|(uid, email, name, role)| json!({ "user": { "id": uid, "email": email, "name": name }, "role": role }))
            .collect(),
    ))
}

#[derive(Deserialize)]
pub struct MemberReq {
    user_id: Uuid,
    role: String,
    wrapped_key: String,
}

pub async fn put_member(
    State(st): State<Shared>,
    user: AuthUser,
    Path(id): Path<Uuid>,
    Json(r): Json<MemberReq>,
) -> ApiResult<StatusCode> {
    require_owner(&st.db, id, &user).await?;
    if r.role != "editor" && r.role != "viewer" {
        return Err(ApiError::invalid("role must be editor or viewer"));
    }
    if r.user_id == user.id {
        return Err(ApiError::invalid("You already own this page"));
    }
    check_wrapped_key(&r.wrapped_key)?;
    let ok: Option<bool> = sqlx::query_scalar("SELECT public_key IS NOT NULL AND NOT disabled FROM users WHERE id = $1")
        .bind(r.user_id)
        .fetch_optional(&st.db)
        .await?;
    if ok != Some(true) {
        return Err(ApiError::invalid("That user cannot receive shared pages yet"));
    }
    sqlx::query(
        "INSERT INTO page_members (page_id, user_id, role, wrapped_key) VALUES ($1, $2, $3, $4)
         ON CONFLICT (page_id, user_id) DO UPDATE SET role = $3, wrapped_key = $4",
    )
    .bind(id)
    .bind(r.user_id)
    .bind(&r.role)
    .bind(&r.wrapped_key)
    .execute(&st.db)
    .await?;
    audit(&st.db, Some(&user), "page.share", Some(format!("{id} -> {} ({})", r.user_id, r.role))).await;
    // Reconnect their sockets so a downgrade to viewer applies immediately.
    st.hub.kick(r.user_id);
    Ok(StatusCode::NO_CONTENT)
}

pub async fn remove_member(
    State(st): State<Shared>,
    user: AuthUser,
    Path((id, member)): Path<(Uuid, Uuid)>,
) -> ApiResult<StatusCode> {
    require_owner(&st.db, id, &user).await?;
    if member == user.id {
        return Err(ApiError::invalid("The owner cannot be removed"));
    }
    sqlx::query("DELETE FROM page_members WHERE page_id = $1 AND user_id = $2")
        .bind(id)
        .bind(member)
        .execute(&st.db)
        .await?;
    audit(&st.db, Some(&user), "page.unshare", Some(format!("{id} -> {member}"))).await;
    st.hub.kick(member);
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct PublicReq {
    public: bool,
}

pub async fn set_public(
    State(st): State<Shared>,
    user: AuthUser,
    Path(id): Path<Uuid>,
    Json(r): Json<PublicReq>,
) -> ApiResult<StatusCode> {
    require_owner(&st.db, id, &user).await?;
    sqlx::query("UPDATE pages SET public = $2 WHERE id = $1").bind(id).bind(r.public).execute(&st.db).await?;
    audit(&st.db, Some(&user), "page.public", Some(format!("{id} = {}", r.public))).await;
    if !r.public {
        st.hub.close_anonymous(id);
    }
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
pub struct LookupQ {
    email: String,
}

pub async fn lookup(State(st): State<Shared>, user: AuthUser, Query(q): Query<LookupQ>) -> ApiResult<Json<Value>> {
    st.limiter.check(&format!("lookup:{}", user.id), 60, std::time::Duration::from_secs(60))?;
    let email = normalize_email(&q.email)?;
    let row: Option<(Uuid, String, String, Value)> = sqlx::query_as(
        "SELECT id, email, name, public_key FROM users WHERE email = $1 AND public_key IS NOT NULL AND NOT disabled",
    )
    .bind(email)
    .fetch_optional(&st.db)
    .await?;
    let (id, email, name, public_key) = row.ok_or_else(ApiError::not_found)?;
    Ok(Json(json!({ "id": id, "email": email, "name": name, "public_key": public_key })))
}

#[derive(Deserialize)]
pub struct HistoryQ {
    #[serde(default)]
    after: i64,
    limit: Option<i64>,
}

/// Encrypted update log with author and time, for client-side version history. Members only.
/// The server cannot see what changed; clients decrypt and rebuild each version.
pub async fn history(State(st): State<Shared>, user: AuthUser, Path(id): Path<Uuid>, Query(q): Query<HistoryQ>) -> ApiResult<Json<Value>> {
    if role_of(&st.db, id, user.id).await?.is_none() {
        return Err(ApiError::not_found());
    }
    let limit = q.limit.unwrap_or(500).clamp(1, 1000);
    let rows: Vec<(i64, chrono::DateTime<chrono::Utc>, Option<Uuid>, Option<String>, Vec<u8>)> = sqlx::query_as(
        "SELECT d.seq, d.created_at, d.user_id, u.name, d.payload FROM document_updates d
         LEFT JOIN users u ON u.id = d.user_id WHERE d.page_id = $1 AND d.seq > $2 ORDER BY d.seq LIMIT $3",
    )
    .bind(id)
    .bind(q.after.max(0))
    .bind(limit + 1)
    .fetch_all(&st.db)
    .await?;
    let more = rows.len() as i64 > limit;
    let updates: Vec<Value> = rows
        .into_iter()
        .take(limit as usize)
        .map(|(seq, at, uid, name, payload)| {
            json!({ "seq": seq, "at": at, "user": uid.map(|id| json!({ "id": id, "name": name.unwrap_or_default() })), "payload": STANDARD.encode(payload) })
        })
        .collect();
    Ok(Json(json!({ "updates": updates, "more": more })))
}
