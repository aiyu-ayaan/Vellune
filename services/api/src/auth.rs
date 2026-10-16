//! Accounts, sessions, and E2E key bundles.
//!
//! The browser never sends the login password: it sends `auth_hash`, derived from the password
//! with PBKDF2 + HKDF. The server stores argon2id(auth_hash) and an opaque key bundle it cannot
//! open. See development/Docs/security.md.

use std::{collections::HashMap, sync::Mutex, time::{Duration, Instant}};

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use axum::{
    async_trait,
    extract::{FromRequestParts, State},
    http::{header, request::Parts, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use base64::{engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD}, Engine};
use hmac::{Hmac, Mac};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use sqlx::{PgPool, Postgres, Transaction};
use uuid::Uuid;

use crate::{error::{ApiError, ApiResult}, settings::{self, ServerSettings}, Shared};

pub const SESSION_COOKIE: &str = "vellune_session";
const SESSION_DAYS: i64 = 30;
pub const MIN_KDF_ITERATIONS: i32 = 600_000;

// ---------- users & sessions ----------

#[derive(Debug, Clone, Serialize)]
pub struct AuthUser {
    pub id: Uuid,
    pub email: String,
    pub name: String,
    pub is_admin: bool,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

/// Rejects with 403 unless the session user is an admin.
pub struct AdminUser(pub AuthUser);

pub fn random_token() -> String {
    let mut b = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut b);
    URL_SAFE_NO_PAD.encode(b)
}

pub fn sha256(s: &str) -> Vec<u8> {
    Sha256::digest(s.as_bytes()).to_vec()
}

pub fn cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .filter_map(|kv| kv.trim().split_once('='))
        .find(|(k, _)| *k == name)
        .map(|(_, v)| v.to_string())
}

pub fn set_cookie(name: &str, value: &str, path: &str, max_age: i64, s: &ServerSettings) -> HeaderValue {
    let secure = if s.secure_cookies() { "; Secure" } else { "" };
    HeaderValue::from_str(&format!("{name}={value}; Path={path}; HttpOnly; SameSite=Lax; Max-Age={max_age}{secure}"))
        .expect("cookie is ascii")
}

pub async fn user_from_headers(db: &PgPool, headers: &HeaderMap) -> ApiResult<Option<AuthUser>> {
    let Some(token) = cookie(headers, SESSION_COOKIE) else { return Ok(None) };
    let row: Option<(Uuid, String, String, bool, chrono::DateTime<chrono::Utc>)> = sqlx::query_as(
        "SELECT u.id, u.email, u.name, u.is_admin, u.created_at FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = $1 AND s.expires_at > now() AND NOT u.disabled",
    )
    .bind(sha256(&token))
    .fetch_optional(db)
    .await?;
    Ok(row.map(|(id, email, name, is_admin, created_at)| AuthUser { id, email, name, is_admin, created_at }))
}

#[async_trait]
impl FromRequestParts<Shared> for AuthUser {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, st: &Shared) -> ApiResult<Self> {
        user_from_headers(&st.db, &parts.headers).await?.ok_or_else(ApiError::unauthorized)
    }
}

#[async_trait]
impl FromRequestParts<Shared> for AdminUser {
    type Rejection = ApiError;
    async fn from_request_parts(parts: &mut Parts, st: &Shared) -> ApiResult<Self> {
        let u = AuthUser::from_request_parts(parts, st).await?;
        if !u.is_admin {
            return Err(ApiError::forbidden());
        }
        Ok(AdminUser(u))
    }
}

/// Creates a session and returns its `Set-Cookie` header.
pub async fn create_session(db: &PgPool, user_id: Uuid, s: &ServerSettings) -> ApiResult<HeaderValue> {
    let token = random_token();
    sqlx::query("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1, $2, now() + make_interval(days => $3))")
        .bind(sha256(&token))
        .bind(user_id)
        .bind(SESSION_DAYS as i32)
        .execute(db)
        .await?;
    // Opportunistic cleanup keeps the table bounded without a cron job.
    sqlx::query("DELETE FROM sessions WHERE expires_at < now()").execute(db).await?;
    Ok(set_cookie(SESSION_COOKIE, &token, "/", SESSION_DAYS * 86_400, s))
}

pub async fn audit(db: &PgPool, actor: Option<&AuthUser>, action: &str, target: Option<String>) {
    let r = sqlx::query("INSERT INTO audit_events (actor_id, actor_email, action, target) VALUES ($1, $2, $3, $4)")
        .bind(actor.map(|a| a.id))
        .bind(actor.map(|a| a.email.clone()))
        .bind(action)
        .bind(target)
        .execute(db)
        .await;
    if let Err(e) = r {
        tracing::warn!(error = %e, action, "audit write failed");
    }
}

/// Serializes account creation so exactly one account becomes the first admin.
pub async fn lock_accounts(tx: &mut Transaction<'_, Postgres>) -> ApiResult<bool> {
    sqlx::query("SELECT pg_advisory_xact_lock(7340001)").execute(&mut **tx).await?;
    let (any,): (bool,) = sqlx::query_as("SELECT EXISTS (SELECT 1 FROM users)").fetch_one(&mut **tx).await?;
    Ok(!any)
}

pub fn check_signup_allowed(s: &ServerSettings, email: &str, first: bool) -> ApiResult<()> {
    if first {
        return Ok(());
    }
    if !s.allow_signups {
        return Err(ApiError::new(StatusCode::FORBIDDEN, "signups_disabled", "Sign-ups are disabled on this server"));
    }
    if !s.email_allowed(email) {
        return Err(ApiError::new(StatusCode::FORBIDDEN, "signups_disabled", "This email domain is not allowed"));
    }
    Ok(())
}

pub fn normalize_email(email: &str) -> ApiResult<String> {
    let e = email.trim().to_lowercase();
    let ok = e.len() <= 254
        && e.split_once('@').is_some_and(|(l, d)| !l.is_empty() && d.contains('.') && !d.starts_with('.'))
        && !e.contains(char::is_whitespace);
    if ok { Ok(e) } else { Err(ApiError::invalid("Enter a valid email address")) }
}

fn normalize_name(name: &str) -> ApiResult<String> {
    let n = name.trim();
    if n.is_empty() || n.chars().count() > 100 {
        return Err(ApiError::invalid("Name must be 1-100 characters"));
    }
    Ok(n.to_string())
}

// ---------- rate limiting ----------

/// Fixed-window limiter keyed by string.
/// shortcut: per-process memory; move to Redis when running more than one API instance.
#[derive(Default)]
pub struct RateLimiter(Mutex<HashMap<String, (Instant, u32)>>);

impl RateLimiter {
    pub fn check(&self, key: &str, max: u32, window: Duration) -> ApiResult<()> {
        let mut m = self.0.lock().unwrap();
        let now = Instant::now();
        if m.len() > 10_000 {
            m.retain(|_, (t, _)| now.duration_since(*t) < window);
            // Under a key-rotation flood, forget everything rather than grow without bound.
            if m.len() > 50_000 {
                m.clear();
            }
        }
        let e = m.entry(key.to_string()).or_insert((now, 0));
        if now.duration_since(e.0) >= window {
            *e = (now, 0);
        }
        e.1 += 1;
        if e.1 > max { Err(ApiError::rate_limited()) } else { Ok(()) }
    }
}

// ---------- key bundles ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyBundle {
    pub kdf_salt: String,
    pub kdf_iterations: i32,
    pub public_key: Value,
    pub wrapped_private_key: String,
}

impl KeyBundle {
    fn validate(&self) -> ApiResult<Vec<u8>> {
        let salt = STANDARD.decode(&self.kdf_salt).map_err(|_| ApiError::invalid("kdf_salt must be base64"))?;
        if salt.len() != 16 {
            return Err(ApiError::invalid("kdf_salt must be 16 bytes"));
        }
        if !(MIN_KDF_ITERATIONS..=10_000_000).contains(&self.kdf_iterations) {
            return Err(ApiError::invalid("kdf_iterations out of range"));
        }
        let pk = &self.public_key;
        if pk.get("kty") != Some(&json!("EC")) || pk.get("crv") != Some(&json!("P-256")) || pk.get("d").is_some() || pk.to_string().len() > 1024 {
            return Err(ApiError::invalid("public_key must be a P-256 public JWK"));
        }
        if self.wrapped_private_key.len() > 4096 || STANDARD.decode(&self.wrapped_private_key).is_err() {
            return Err(ApiError::invalid("wrapped_private_key must be base64"));
        }
        Ok(salt)
    }
}

async fn keys_of(db: &PgPool, user_id: Uuid) -> ApiResult<Option<KeyBundle>> {
    let row: Option<(Option<Vec<u8>>, Option<i32>, Option<Value>, Option<String>)> =
        sqlx::query_as("SELECT kdf_salt, kdf_iterations, public_key, wrapped_private_key FROM users WHERE id = $1")
            .bind(user_id)
            .fetch_optional(db)
            .await?;
    Ok(match row {
        Some((Some(salt), Some(it), Some(pk), Some(w))) => Some(KeyBundle {
            kdf_salt: STANDARD.encode(salt),
            kdf_iterations: it,
            public_key: pk,
            wrapped_private_key: w,
        }),
        _ => None,
    })
}

// ---------- password (auth hash) hashing ----------

fn decode_auth_hash(h: &str) -> ApiResult<Vec<u8>> {
    match STANDARD.decode(h) {
        Ok(b) if b.len() == 32 => Ok(b),
        _ => Err(ApiError::invalid("auth_hash must be 32 base64 bytes")),
    }
}

async fn hash_auth(auth: Vec<u8>) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || {
        Argon2::default()
            .hash_password(&auth, &SaltString::generate(&mut OsRng))
            .map(|h| h.to_string())
    })
    .await
    .map_err(|_| ApiError::internal())?
    .map_err(|_| ApiError::internal())
}

async fn verify_auth(auth: Vec<u8>, stored: Option<String>) -> bool {
    // Verifying against a real dummy hash keeps timing similar for unknown accounts.
    static DUMMY: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    let known = stored.is_some();
    let ok = tokio::task::spawn_blocking(move || {
        let stored = stored.unwrap_or_else(|| {
            DUMMY
                .get_or_init(|| Argon2::default().hash_password(b"vellune-dummy", &SaltString::generate(&mut OsRng)).unwrap().to_string())
                .clone()
        });
        PasswordHash::new(&stored).is_ok_and(|h| Argon2::default().verify_password(&auth, &h).is_ok())
    })
    .await
    .unwrap_or(false);
    known && ok
}

// ---------- handlers ----------

#[derive(Deserialize)]
pub struct PreloginReq {
    email: String,
}

pub async fn prelogin(State(st): State<Shared>, Json(r): Json<PreloginReq>) -> ApiResult<Json<Value>> {
    let email = normalize_email(&r.email)?;
    st.limiter.check(&format!("prelogin:{email}"), 30, Duration::from_secs(300))?;
    let row: Option<(Option<Vec<u8>>, Option<i32>)> =
        sqlx::query_as("SELECT kdf_salt, kdf_iterations FROM users WHERE email = $1")
            .bind(&email)
            .fetch_optional(&st.db)
            .await?;
    let (salt, iterations) = match row {
        Some((Some(s), Some(i))) => (s, i),
        // Unknown or OAuth-only account: a stable fake salt so responses do not reveal which emails exist.
        _ => {
            let mut mac = Hmac::<Sha256>::new_from_slice(&st.secret).expect("hmac key");
            mac.update(b"vellune-fake-salt:");
            mac.update(email.as_bytes());
            (mac.finalize().into_bytes()[..16].to_vec(), MIN_KDF_ITERATIONS)
        }
    };
    Ok(Json(json!({ "kdf_salt": STANDARD.encode(salt), "kdf_iterations": iterations })))
}

#[derive(Deserialize)]
pub struct SignupReq {
    email: String,
    name: String,
    auth_hash: String,
    keys: KeyBundle,
}

pub async fn signup(State(st): State<Shared>, Json(r): Json<SignupReq>) -> ApiResult<Response> {
    let email = normalize_email(&r.email)?;
    let name = normalize_name(&r.name)?;
    let auth = decode_auth_hash(&r.auth_hash)?;
    let salt = r.keys.validate()?;
    st.limiter.check("signup", 30, Duration::from_secs(60))?;
    let s = settings::load(&st.db).await?;
    let hashed = hash_auth(auth).await?;

    let mut tx = st.db.begin().await?;
    let first = lock_accounts(&mut tx).await?;
    check_signup_allowed(&s, &email, first)?;
    let id = Uuid::new_v4();
    let inserted = sqlx::query(
        "INSERT INTO users (id, email, name, auth_hash, is_admin, kdf_salt, kdf_iterations, public_key, wrapped_private_key)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (email) DO NOTHING",
    )
    .bind(id)
    .bind(&email)
    .bind(&name)
    .bind(hashed)
    .bind(first)
    .bind(salt)
    .bind(r.keys.kdf_iterations)
    .bind(&r.keys.public_key)
    .bind(&r.keys.wrapped_private_key)
    .execute(&mut *tx)
    .await?;
    if inserted.rows_affected() == 0 {
        return Err(ApiError::conflict("An account with this email already exists"));
    }
    tx.commit().await?;

    let user = AuthUser { id, email, name, is_admin: first, created_at: chrono::Utc::now() };
    audit(&st.db, Some(&user), "user.signup", None).await;
    let c = create_session(&st.db, id, &s).await?;
    Ok(([(header::SET_COOKIE, c)], Json(json!({ "user": user, "keys": r.keys }))).into_response())
}

#[derive(Deserialize)]
pub struct LoginReq {
    email: String,
    auth_hash: String,
}

pub async fn login(State(st): State<Shared>, Json(r): Json<LoginReq>) -> ApiResult<Response> {
    let email = normalize_email(&r.email)?;
    let auth = decode_auth_hash(&r.auth_hash)?;
    st.limiter.check(&format!("login:{email}"), 10, Duration::from_secs(300))?;
    let row: Option<(Uuid, Option<String>, bool)> =
        sqlx::query_as("SELECT id, auth_hash, disabled FROM users WHERE email = $1")
            .bind(&email)
            .fetch_optional(&st.db)
            .await?;
    let (id, stored, disabled) = match row {
        Some((id, h, d)) => (Some(id), h, d),
        None => (None, None, false),
    };
    if !verify_auth(auth, stored).await {
        return Err(ApiError::new(StatusCode::UNAUTHORIZED, "unauthorized", "Incorrect email or password"));
    }
    if disabled {
        return Err(ApiError::new(StatusCode::FORBIDDEN, "account_disabled", "This account has been disabled"));
    }
    let id = id.expect("verified implies known");
    let s = settings::load(&st.db).await?;
    let c = create_session(&st.db, id, &s).await?;
    let user = user_by_id(&st.db, id).await?;
    let keys = keys_of(&st.db, id).await?;
    Ok(([(header::SET_COOKIE, c)], Json(json!({ "user": user, "keys": keys }))).into_response())
}

async fn user_by_id(db: &PgPool, id: Uuid) -> ApiResult<AuthUser> {
    let (id, email, name, is_admin, created_at): (Uuid, String, String, bool, chrono::DateTime<chrono::Utc>) =
        sqlx::query_as("SELECT id, email, name, is_admin, created_at FROM users WHERE id = $1")
            .bind(id)
            .fetch_one(db)
            .await?;
    Ok(AuthUser { id, email, name, is_admin, created_at })
}

pub async fn logout(State(st): State<Shared>, headers: HeaderMap) -> ApiResult<Response> {
    if let Some(token) = cookie(&headers, SESSION_COOKIE) {
        sqlx::query("DELETE FROM sessions WHERE token_hash = $1").bind(sha256(&token)).execute(&st.db).await?;
    }
    let s = settings::load(&st.db).await?;
    Ok((StatusCode::NO_CONTENT, [(header::SET_COOKIE, set_cookie(SESSION_COOKIE, "", "/", 0, &s))]).into_response())
}

pub async fn me(State(st): State<Shared>, user: AuthUser) -> ApiResult<Json<Value>> {
    let keys = keys_of(&st.db, user.id).await?;
    Ok(Json(json!({ "user": user, "keys": keys })))
}

pub async fn put_keys(State(st): State<Shared>, user: AuthUser, Json(k): Json<KeyBundle>) -> ApiResult<Json<Value>> {
    let salt = k.validate()?;
    let r = sqlx::query(
        "UPDATE users SET kdf_salt = $2, kdf_iterations = $3, public_key = $4, wrapped_private_key = $5
         WHERE id = $1 AND public_key IS NULL",
    )
    .bind(user.id)
    .bind(salt)
    .bind(k.kdf_iterations)
    .bind(&k.public_key)
    .bind(&k.wrapped_private_key)
    .execute(&st.db)
    .await?;
    if r.rows_affected() == 0 {
        return Err(ApiError::conflict("Encryption keys are already set up for this account"));
    }
    Ok(Json(json!({ "keys": k })))
}
