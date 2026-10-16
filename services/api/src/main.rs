//! Vellune API: HTTP API, admin API, and the WebSocket sync gateway in one process
//! (modular monolith, ADR 0001). Serves the built web app too when `WEB_DIST` is set.

mod admin;
mod auth;
mod error;
mod oauth;
mod pages;
mod settings;
mod sync;

use std::sync::Arc;

use axum::{
    extract::Request,
    http::Method,
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
    Router,
};
use rand::RngCore;
use sha2::{Digest, Sha256};
use sqlx::postgres::PgPoolOptions;
use tracing::{info, warn};

pub struct AppState {
    pub db: sqlx::PgPool,
    /// Server-side secret for non-reversible derivations (fake KDF salts).
    pub secret: [u8; 32],
    pub hub: sync::Hub,
    pub http: reqwest::Client,
    pub limiter: auth::RateLimiter,
}

pub type Shared = Arc<AppState>;

/// Cross-site forms cannot set custom headers, so requiring one blocks CSRF on cookie-authenticated writes.
async fn csrf(req: Request, next: Next) -> Response {
    let safe = matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS);
    if !safe && req.headers().get("x-vellune").and_then(|v| v.to_str().ok()) != Some("1") {
        return error::ApiError::new(axum::http::StatusCode::FORBIDDEN, "forbidden", "Missing X-Vellune header").into_response();
    }
    next.run(req).await
}

fn router(state: Shared) -> Router {
    let api = Router::new()
        .route("/auth/prelogin", post(auth::prelogin))
        .route("/auth/signup", post(auth::signup))
        .route("/auth/login", post(auth::login))
        .route("/auth/logout", post(auth::logout))
        .route("/auth/me", get(auth::me))
        .route("/auth/keys", put(auth::put_keys))
        .route("/auth/providers", get(oauth::providers))
        .route("/auth/oauth/:provider/start", get(oauth::start))
        .route("/auth/oauth/:provider/callback", get(oauth::callback))
        .route("/pages", get(pages::list).post(pages::create))
        .route("/pages/:id", delete(pages::delete))
        .route("/pages/:id/members", get(pages::members).put(pages::put_member))
        .route("/pages/:id/members/:user_id", delete(pages::remove_member))
        .route("/pages/:id/public", put(pages::set_public))
        .route("/users/lookup", get(pages::lookup))
        .route("/admin/stats", get(admin::stats))
        .route("/admin/users", get(admin::users))
        .route("/admin/users/:id", axum::routing::patch(admin::update_user).delete(admin::delete_user))
        .route("/admin/users/:id/sessions", delete(admin::revoke_sessions))
        .route("/admin/oauth", get(admin::oauth_list))
        .route("/admin/oauth/:provider", put(admin::oauth_update))
        .route("/admin/settings", get(admin::get_settings).put(admin::put_settings))
        .route("/admin/audit", get(admin::audit_log))
        .route("/sync", get(sync::ws_handler))
        .layer(middleware::from_fn(csrf));

    let app = Router::new()
        .nest("/api/v1", api)
        .route("/healthz", get(|| async { "ok" }))
        .with_state(state)
        .layer(tower_http::trace::TraceLayer::new_for_http());

    match std::env::var("WEB_DIST") {
        Ok(dir) => {
            let index = format!("{dir}/index.html");
            app.fallback_service(tower_http::services::ServeDir::new(dir).fallback(tower_http::services::ServeFile::new(index)))
        }
        Err(_) => app,
    }
}

fn server_secret() -> [u8; 32] {
    match std::env::var("VELLUNE_SECRET") {
        Ok(s) if s.len() >= 32 => Sha256::digest(s.as_bytes()).into(),
        _ => {
            warn!("VELLUNE_SECRET unset or shorter than 32 chars; using a random per-process secret (dev only)");
            let mut b = [0u8; 32];
            rand::rngs::OsRng.fill_bytes(&mut b);
            b
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Local dev convenience; real deployments pass environment variables directly.
    dotenvy::dotenv().ok();
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let url = std::env::var("DATABASE_URL")
        .unwrap_or_else(|_| "postgres://vellune:vellunepassword@localhost:5432/vellune_dev".into());
    let max_conn = std::env::var("DATABASE_MAX_CONNECTIONS").ok().and_then(|v| v.parse().ok()).unwrap_or(20);
    let db = PgPoolOptions::new().max_connections(max_conn).connect(&url).await?;
    sqlx::migrate!("../../migrations").run(&db).await?;

    let state = Arc::new(AppState {
        db,
        secret: server_secret(),
        hub: sync::Hub::default(),
        http: reqwest::Client::builder().timeout(std::time::Duration::from_secs(15)).build()?,
        limiter: auth::RateLimiter::default(),
    });

    let host = std::env::var("SERVER_HOST").unwrap_or_else(|_| "127.0.0.1".into());
    let port = std::env::var("SERVER_PORT").unwrap_or_else(|_| "8080".into());
    let listener = tokio::net::TcpListener::bind(format!("{host}:{port}")).await?;
    info!("Vellune API listening on http://{host}:{port}");
    axum::serve(listener, router(state)).await?;
    Ok(())
}
