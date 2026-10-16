//! Admin panel API. Every handler takes `AdminUser`, so non-admins get 403 before any work.

use std::sync::atomic::Ordering;

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::{
    auth::{audit, AdminUser},
    error::{ApiError, ApiResult},
    settings::{self, ServerSettings},
    Shared,
};

pub async fn stats(State(st): State<Shared>, _a: AdminUser) -> ApiResult<Json<Value>> {
    let (users, admins, disabled, pages, updates, bytes, sessions): (i64, i64, i64, i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM users),
                (SELECT count(*) FROM users WHERE is_admin),
                (SELECT count(*) FROM users WHERE disabled),
                (SELECT count(*) FROM pages),
                (SELECT count(*) FROM document_updates),
                (SELECT COALESCE(sum(octet_length(payload)), 0)::bigint FROM document_updates),
                (SELECT count(*) FROM sessions WHERE expires_at > now())",
    )
    .fetch_one(&st.db)
    .await?;
    Ok(Json(json!({
        "users": users, "admins": admins, "disabled_users": disabled, "pages": pages,
        "updates": updates, "update_bytes": bytes, "active_sessions": sessions,
        "online_connections": st.hub.online.load(Ordering::Relaxed),
    })))
}

#[derive(Deserialize)]
pub struct UsersQ {
    #[serde(default)]
    q: String,
    #[serde(default)]
    offset: i64,
    limit: Option<i64>,
}

type UserRow = (Uuid, String, String, bool, bool, chrono::DateTime<chrono::Utc>, Vec<String>, i64, bool);

const USER_SELECT: &str = "SELECT u.id, u.email, u.name, u.is_admin, u.disabled, u.created_at,
        COALESCE((SELECT array_agg(provider ORDER BY provider) FROM oauth_accounts o WHERE o.user_id = u.id), '{}') AS providers,
        (SELECT count(*) FROM pages p WHERE p.owner_id = u.id) AS pages,
        u.public_key IS NOT NULL AS has_keys
     FROM users u";

fn user_json((id, email, name, is_admin, disabled, created_at, providers, pages, has_keys): UserRow) -> Value {
    json!({ "id": id, "email": email, "name": name, "is_admin": is_admin, "disabled": disabled,
            "created_at": created_at, "providers": providers, "pages": pages, "has_keys": has_keys })
}

pub async fn users(State(st): State<Shared>, _a: AdminUser, Query(q): Query<UsersQ>) -> ApiResult<Json<Value>> {
    let pattern = format!("%{}%", q.q.trim().replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_"));
    let limit = q.limit.unwrap_or(50).clamp(1, 200);
    let rows: Vec<UserRow> = sqlx::query_as(sqlx::AssertSqlSafe(format!(
        "{USER_SELECT} WHERE u.email ILIKE $1 OR u.name ILIKE $1 ORDER BY u.created_at LIMIT $2 OFFSET $3"
    )))
    .bind(&pattern)
    .bind(limit)
    .bind(q.offset.max(0))
    .fetch_all(&st.db)
    .await?;
    let total: i64 = sqlx::query_scalar("SELECT count(*) FROM users u WHERE u.email ILIKE $1 OR u.name ILIKE $1")
        .bind(&pattern)
        .fetch_one(&st.db)
        .await?;
    Ok(Json(json!({ "users": rows.into_iter().map(user_json).collect::<Vec<_>>(), "total": total })))
}

#[derive(Deserialize)]
pub struct UserPatch {
    is_admin: Option<bool>,
    disabled: Option<bool>,
    name: Option<String>,
}

pub async fn update_user(
    State(st): State<Shared>,
    AdminUser(a): AdminUser,
    Path(id): Path<Uuid>,
    Json(p): Json<UserPatch>,
) -> ApiResult<Json<Value>> {
    if id == a.id && (p.is_admin == Some(false) || p.disabled == Some(true)) {
        return Err(ApiError::invalid("You cannot demote or disable your own account"));
    }
    if let Some(n) = &p.name {
        if n.trim().is_empty() || n.chars().count() > 100 {
            return Err(ApiError::invalid("Name must be 1-100 characters"));
        }
    }
    let r = sqlx::query(
        "UPDATE users SET is_admin = COALESCE($2, is_admin), disabled = COALESCE($3, disabled), name = COALESCE($4, name) WHERE id = $1",
    )
    .bind(id)
    .bind(p.is_admin)
    .bind(p.disabled)
    .bind(p.name.as_deref().map(str::trim))
    .execute(&st.db)
    .await?;
    if r.rows_affected() == 0 {
        return Err(ApiError::not_found());
    }
    if p.disabled == Some(true) {
        sqlx::query("DELETE FROM sessions WHERE user_id = $1").bind(id).execute(&st.db).await?;
        st.hub.kick(id);
    }
    audit(&st.db, Some(&a), "admin.user_update", Some(format!("{id} admin={:?} disabled={:?}", p.is_admin, p.disabled))).await;
    let row: UserRow = sqlx::query_as(sqlx::AssertSqlSafe(format!("{USER_SELECT} WHERE u.id = $1"))).bind(id).fetch_one(&st.db).await?;
    Ok(Json(user_json(row)))
}

pub async fn delete_user(State(st): State<Shared>, AdminUser(a): AdminUser, Path(id): Path<Uuid>) -> ApiResult<StatusCode> {
    if id == a.id {
        return Err(ApiError::invalid("You cannot delete your own account"));
    }
    let email: Option<String> = sqlx::query_scalar("DELETE FROM users WHERE id = $1 RETURNING email")
        .bind(id)
        .fetch_optional(&st.db)
        .await?;
    let email = email.ok_or_else(ApiError::not_found)?;
    st.hub.kick(id);
    audit(&st.db, Some(&a), "admin.user_delete", Some(email)).await;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn revoke_sessions(State(st): State<Shared>, AdminUser(a): AdminUser, Path(id): Path<Uuid>) -> ApiResult<StatusCode> {
    sqlx::query("DELETE FROM sessions WHERE user_id = $1").bind(id).execute(&st.db).await?;
    st.hub.kick(id);
    audit(&st.db, Some(&a), "admin.sessions_revoke", Some(id.to_string())).await;
    Ok(StatusCode::NO_CONTENT)
}

// ---------- OAuth providers ----------

fn provider_json(s: &ServerSettings, provider: &str, enabled: bool, client_id: &str, secret: &str) -> Value {
    json!({
        "provider": provider, "enabled": enabled, "client_id": client_id, "has_secret": !secret.is_empty(),
        "redirect_uri": format!("{}/api/v1/auth/oauth/{provider}/callback", s.app_url()),
    })
}

pub async fn oauth_list(State(st): State<Shared>, _a: AdminUser) -> ApiResult<Json<Vec<Value>>> {
    let s = settings::load(&st.db).await?;
    let rows: Vec<(String, bool, String, String)> =
        sqlx::query_as("SELECT provider, enabled, client_id, client_secret FROM oauth_providers ORDER BY provider")
            .fetch_all(&st.db)
            .await?;
    Ok(Json(rows.iter().map(|(p, e, id, sec)| provider_json(&s, p, *e, id, sec)).collect()))
}

#[derive(Deserialize)]
pub struct OAuthPut {
    enabled: bool,
    client_id: String,
    client_secret: Option<String>,
}

pub async fn oauth_update(
    State(st): State<Shared>,
    AdminUser(a): AdminUser,
    Path(provider): Path<String>,
    Json(b): Json<OAuthPut>,
) -> ApiResult<Json<Value>> {
    let current: Option<String> = sqlx::query_scalar("SELECT client_secret FROM oauth_providers WHERE provider = $1")
        .bind(&provider)
        .fetch_optional(&st.db)
        .await?;
    let current = current.ok_or_else(ApiError::not_found)?;
    let client_id = b.client_id.trim();
    let secret = match b.client_secret.as_deref().map(str::trim) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => current,
    };
    if client_id.len() > 512 || secret.len() > 512 {
        return Err(ApiError::invalid("Client ID and secret must be at most 512 characters"));
    }
    if b.enabled && (client_id.is_empty() || secret.is_empty()) {
        return Err(ApiError::invalid("Client ID and client secret are required to enable this provider"));
    }
    // shortcut: secret stored in plaintext in Postgres; encrypt with a KMS-held key before multi-tenant hosting.
    sqlx::query("UPDATE oauth_providers SET enabled = $2, client_id = $3, client_secret = $4 WHERE provider = $1")
        .bind(&provider)
        .bind(b.enabled)
        .bind(client_id)
        .bind(&secret)
        .execute(&st.db)
        .await?;
    audit(&st.db, Some(&a), "admin.oauth_update", Some(format!("{provider} enabled={}", b.enabled))).await;
    let s = settings::load(&st.db).await?;
    Ok(Json(provider_json(&s, &provider, b.enabled, client_id, &secret)))
}

// ---------- settings & audit ----------

pub async fn get_settings(State(st): State<Shared>, _a: AdminUser) -> ApiResult<Json<ServerSettings>> {
    Ok(Json(settings::load(&st.db).await?))
}

pub async fn put_settings(State(st): State<Shared>, AdminUser(a): AdminUser, Json(s): Json<ServerSettings>) -> ApiResult<Json<ServerSettings>> {
    let s = s.validate()?;
    settings::save(&st.db, &s).await?;
    audit(&st.db, Some(&a), "admin.settings_update", None).await;
    Ok(Json(s))
}

#[derive(Deserialize)]
pub struct AuditQ {
    limit: Option<i64>,
}

pub async fn audit_log(State(st): State<Shared>, _a: AdminUser, Query(q): Query<AuditQ>) -> ApiResult<Json<Vec<Value>>> {
    let rows: Vec<(i64, Option<String>, String, Option<String>, chrono::DateTime<chrono::Utc>)> = sqlx::query_as(
        "SELECT id, actor_email, action, target, created_at FROM audit_events ORDER BY id DESC LIMIT $1",
    )
    .bind(q.limit.unwrap_or(100).clamp(1, 500))
    .fetch_all(&st.db)
    .await?;
    Ok(Json(
        rows.into_iter()
            .map(|(id, actor_email, action, target, created_at)| {
                json!({ "id": id, "actor_email": actor_email, "action": action, "target": target, "created_at": created_at })
            })
            .collect(),
    ))
}
