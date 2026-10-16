//! GitHub and Google sign-in (authorization code + PKCE). Providers are configured in the admin panel.
//! OAuth gives identity only; E2E keys still come from the user's encryption passphrase.

use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::{
    auth::{audit, check_signup_allowed, cookie, create_session, lock_accounts, normalize_email, random_token, set_cookie, AuthUser},
    error::{ApiError, ApiResult},
    settings::{self, ServerSettings},
    Shared,
};

const STATE_COOKIE: &str = "vellune_oauth";
const STATE_PATH: &str = "/api/v1/auth/oauth";

struct Provider {
    authorize: &'static str,
    token: &'static str,
    scope: &'static str,
    name: &'static str,
}

fn provider(id: &str) -> ApiResult<Provider> {
    match id {
        "github" => Ok(Provider {
            authorize: "https://github.com/login/oauth/authorize",
            token: "https://github.com/login/oauth/access_token",
            scope: "read:user user:email",
            name: "GitHub",
        }),
        "google" => Ok(Provider {
            authorize: "https://accounts.google.com/o/oauth2/v2/auth",
            token: "https://oauth2.googleapis.com/token",
            scope: "openid email profile",
            name: "Google",
        }),
        _ => Err(ApiError::not_found()),
    }
}

async fn credentials(st: &Shared, id: &str) -> ApiResult<(String, String)> {
    let row: Option<(bool, String, String)> =
        sqlx::query_as("SELECT enabled, client_id, client_secret FROM oauth_providers WHERE provider = $1")
            .bind(id)
            .fetch_optional(&st.db)
            .await?;
    match row {
        Some((true, cid, sec)) if !cid.is_empty() && !sec.is_empty() => Ok((cid, sec)),
        _ => Err(ApiError::not_found()),
    }
}

fn redirect_uri(s: &ServerSettings, id: &str) -> String {
    format!("{}{STATE_PATH}/{id}/callback", s.app_url())
}

pub async fn providers(State(st): State<Shared>) -> ApiResult<Json<Vec<Value>>> {
    let ids: Vec<String> = sqlx::query_scalar(
        "SELECT provider FROM oauth_providers WHERE enabled AND client_id <> '' AND client_secret <> '' ORDER BY provider",
    )
    .fetch_all(&st.db)
    .await?;
    Ok(Json(ids.iter().filter_map(|id| provider(id).ok().map(|p| json!({ "id": id, "name": p.name }))).collect()))
}

fn redirect(to: &str, cookies: &[HeaderValue]) -> Response {
    let mut r = (StatusCode::FOUND, [(header::LOCATION, to.to_string())]).into_response();
    for c in cookies {
        r.headers_mut().append(header::SET_COOKIE, c.clone());
    }
    r
}

pub async fn start(State(st): State<Shared>, Path(id): Path<String>) -> ApiResult<Response> {
    let p = provider(&id)?;
    let (client_id, _) = credentials(&st, &id).await?;
    let s = settings::load(&st.db).await?;
    let state = random_token();
    let verifier = random_token();
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    sqlx::query("DELETE FROM oauth_states WHERE created_at < now() - interval '10 minutes'").execute(&st.db).await?;
    sqlx::query("INSERT INTO oauth_states (state, provider, pkce_verifier) VALUES ($1, $2, $3)")
        .bind(&state)
        .bind(&id)
        .bind(&verifier)
        .execute(&st.db)
        .await?;
    let url = reqwest::Url::parse_with_params(
        p.authorize,
        &[
            ("client_id", client_id.as_str()),
            ("redirect_uri", &redirect_uri(&s, &id)),
            ("response_type", "code"),
            ("scope", p.scope),
            ("state", &state),
            ("code_challenge", &challenge),
            ("code_challenge_method", "S256"),
            ("prompt", "select_account"),
        ],
    )
    .map_err(|_| ApiError::internal())?;
    // Binds the flow to this browser so a forged callback link cannot log someone in.
    let c = set_cookie(STATE_COOKIE, &state, STATE_PATH, 600, &s);
    Ok(redirect(url.as_str(), &[c]))
}

#[derive(Deserialize)]
pub struct CallbackQ {
    code: Option<String>,
    state: Option<String>,
}

struct Identity {
    subject: String,
    email: String,
    name: String,
}

pub async fn callback(
    State(st): State<Shared>,
    Path(id): Path<String>,
    headers: HeaderMap,
    Query(q): Query<CallbackQ>,
) -> Response {
    let s = settings::load(&st.db).await.unwrap_or_default();
    let clear = set_cookie(STATE_COOKIE, "", STATE_PATH, 0, &s);
    match finish(&st, &s, &id, &headers, q).await {
        Ok(session) => redirect(&format!("{}/", s.app_url()), &[clear, session]),
        Err(code) => redirect(&format!("{}/?auth_error={code}", s.app_url()), &[clear]),
    }
}

async fn finish(st: &Shared, s: &ServerSettings, id: &str, headers: &HeaderMap, q: CallbackQ) -> Result<HeaderValue, &'static str> {
    let (Some(code), Some(state)) = (q.code, q.state) else { return Err("oauth_cancelled") };
    if cookie(headers, STATE_COOKIE).as_deref() != Some(state.as_str()) {
        return Err("oauth_state");
    }
    let verifier: Option<String> = sqlx::query_scalar(
        "DELETE FROM oauth_states WHERE state = $1 AND provider = $2 AND created_at > now() - interval '10 minutes' RETURNING pkce_verifier",
    )
    .bind(&state)
    .bind(id)
    .fetch_optional(&st.db)
    .await
    .map_err(|_| "oauth_failed")?;
    let verifier = verifier.ok_or("oauth_state")?;
    let p = provider(id).map_err(|_| "oauth_failed")?;
    let (client_id, secret) = credentials(st, id).await.map_err(|_| "oauth_disabled")?;

    let token: Value = st
        .http
        .post(p.token)
        .header(header::ACCEPT, "application/json")
        .form(&[
            ("client_id", client_id.as_str()),
            ("client_secret", secret.as_str()),
            ("code", code.as_str()),
            ("redirect_uri", &redirect_uri(s, id)),
            ("grant_type", "authorization_code"),
            ("code_verifier", verifier.as_str()),
        ])
        .send()
        .await
        .map_err(|_| "oauth_failed")?
        .json()
        .await
        .map_err(|_| "oauth_failed")?;
    let access = token.get("access_token").and_then(Value::as_str).ok_or("oauth_failed")?;
    let ident = match id {
        "github" => github_identity(st, access).await,
        _ => google_identity(st, access).await,
    }
    .ok_or("oauth_no_verified_email")?;

    let user_id = resolve_user(st, s, id, &ident).await?;
    if let Ok(Some(u)) = sqlx::query_as::<_, (Uuid, String, String, bool, chrono::DateTime<chrono::Utc>)>(
        "SELECT id, email, name, is_admin, created_at FROM users WHERE id = $1",
    )
    .bind(user_id)
    .fetch_optional(&st.db)
    .await
    {
        let user = AuthUser { id: u.0, email: u.1, name: u.2, is_admin: u.3, created_at: u.4 };
        audit(&st.db, Some(&user), "user.login_oauth", Some(id.to_string())).await;
    }
    create_session(&st.db, user_id, s).await.map_err(|_| "oauth_failed")
}

async fn get_json(st: &Shared, url: &str, token: &str) -> Option<Value> {
    st.http
        .get(url)
        .bearer_auth(token)
        .header(header::USER_AGENT, "vellune")
        .header(header::ACCEPT, "application/json")
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?
        .json()
        .await
        .ok()
}

async fn github_identity(st: &Shared, token: &str) -> Option<Identity> {
    let user = get_json(st, "https://api.github.com/user", token).await?;
    let emails = get_json(st, "https://api.github.com/user/emails", token).await?;
    let verified: Vec<&Value> = emails.as_array()?.iter().filter(|e| e["verified"] == true).collect();
    let email = verified.iter().find(|e| e["primary"] == true).or(verified.first())?["email"].as_str()?;
    let login = user["login"].as_str().unwrap_or("GitHub user");
    Some(Identity {
        subject: user["id"].as_i64()?.to_string(),
        email: email.to_string(),
        name: user["name"].as_str().filter(|n| !n.is_empty()).unwrap_or(login).to_string(),
    })
}

async fn google_identity(st: &Shared, token: &str) -> Option<Identity> {
    let u = get_json(st, "https://openidconnect.googleapis.com/v1/userinfo", token).await?;
    if u["email_verified"] != true {
        return None;
    }
    let email = u["email"].as_str()?;
    Some(Identity {
        subject: u["sub"].as_str()?.to_string(),
        email: email.to_string(),
        name: u["name"].as_str().unwrap_or(email).to_string(),
    })
}

/// Existing link, else link by verified email, else create an account (first one becomes admin).
async fn resolve_user(st: &Shared, s: &ServerSettings, provider: &str, ident: &Identity) -> Result<Uuid, &'static str> {
    let fail = |_| "oauth_failed";
    let email = normalize_email(&ident.email).map_err(|_| "oauth_no_verified_email")?;
    let mut tx = st.db.begin().await.map_err(fail)?;
    let first = lock_accounts(&mut tx).await.map_err(|_| "oauth_failed")?;
    let linked: Option<(Uuid, bool)> = sqlx::query_as(
        "SELECT u.id, u.disabled FROM oauth_accounts o JOIN users u ON u.id = o.user_id WHERE o.provider = $1 AND o.provider_user_id = $2",
    )
    .bind(provider)
    .bind(&ident.subject)
    .fetch_optional(&mut *tx)
    .await
    .map_err(fail)?;
    let (user_id, disabled) = match linked {
        Some(x) => x,
        None => {
            let by_email: Option<(Uuid, bool, bool)> =
                sqlx::query_as("SELECT id, disabled, auth_hash IS NOT NULL FROM users WHERE email = $1")
                    .bind(&email)
                    .fetch_optional(&mut *tx)
                    .await
                    .map_err(fail)?;
            let (uid, dis) = match by_email {
                // Password signups never verify the email, so linking would hand a squatter's account
                // (and its keys) to whoever proves the address later. Only OAuth-only accounts link.
                Some((_, _, true)) => return Err("oauth_email_taken"),
                Some((id, d, false)) => (id, d),
                None => {
                    check_signup_allowed(s, &email, first).map_err(|_| "signups_disabled")?;
                    let uid = Uuid::new_v4();
                    let name: String = ident.name.chars().take(100).collect();
                    sqlx::query("INSERT INTO users (id, email, name, is_admin) VALUES ($1, $2, $3, $4)")
                        .bind(uid)
                        .bind(&email)
                        .bind(name.trim())
                        .bind(first)
                        .execute(&mut *tx)
                        .await
                        .map_err(fail)?;
                    (uid, false)
                }
            };
            sqlx::query("INSERT INTO oauth_accounts (provider, provider_user_id, user_id) VALUES ($1, $2, $3)")
                .bind(provider)
                .bind(&ident.subject)
                .bind(uid)
                .execute(&mut *tx)
                .await
                .map_err(fail)?;
            (uid, dis)
        }
    };
    if disabled {
        return Err("account_disabled");
    }
    tx.commit().await.map_err(fail)?;
    Ok(user_id)
}
