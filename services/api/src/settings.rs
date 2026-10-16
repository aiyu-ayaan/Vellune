//! Admin-editable server settings, stored as one JSONB row.

use serde::{Deserialize, Serialize};
use sqlx::PgPool;

use crate::error::{ApiError, ApiResult};

pub const MIN_UPDATE_BYTES: u32 = 1024;
pub const MAX_UPDATE_BYTES: u32 = 8 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct ServerSettings {
    pub allow_signups: bool,
    pub allowed_email_domains: Vec<String>,
    pub app_url: String,
    pub max_update_bytes: u32,
}

impl Default for ServerSettings {
    fn default() -> Self {
        Self {
            allow_signups: true,
            allowed_email_domains: Vec::new(),
            app_url: std::env::var("APP_URL").unwrap_or_else(|_| "http://localhost:3000".into()),
            max_update_bytes: 1024 * 1024,
        }
    }
}

impl ServerSettings {
    pub fn app_url(&self) -> &str {
        self.app_url.trim_end_matches('/')
    }

    pub fn secure_cookies(&self) -> bool {
        self.app_url.starts_with("https://")
    }

    pub fn email_allowed(&self, email: &str) -> bool {
        self.allowed_email_domains.is_empty()
            || email
                .rsplit_once('@')
                .is_some_and(|(_, d)| self.allowed_email_domains.iter().any(|a| a == d))
    }

    pub fn validate(mut self) -> ApiResult<Self> {
        if !(self.app_url.starts_with("http://") || self.app_url.starts_with("https://")) {
            return Err(ApiError::invalid("app_url must start with http:// or https://"));
        }
        if !(MIN_UPDATE_BYTES..=MAX_UPDATE_BYTES).contains(&self.max_update_bytes) {
            return Err(ApiError::invalid("max_update_bytes must be between 1 KiB and 8 MiB"));
        }
        self.allowed_email_domains = self
            .allowed_email_domains
            .iter()
            .map(|d| d.trim().trim_start_matches('@').to_lowercase())
            .filter(|d| !d.is_empty())
            .collect();
        if self.allowed_email_domains.iter().any(|d| d.contains(['@', ' ']) || !d.contains('.')) {
            return Err(ApiError::invalid("allowed_email_domains must be domains like example.com"));
        }
        Ok(self)
    }
}

pub async fn load(db: &PgPool) -> ApiResult<ServerSettings> {
    let row: Option<(serde_json::Value,)> = sqlx::query_as("SELECT value FROM server_settings WHERE id = 1")
        .fetch_optional(db)
        .await?;
    Ok(row
        .and_then(|(v,)| serde_json::from_value(v).ok())
        .unwrap_or_default())
}

pub async fn save(db: &PgPool, s: &ServerSettings) -> ApiResult<()> {
    sqlx::query("INSERT INTO server_settings (id, value) VALUES (1, $1) ON CONFLICT (id) DO UPDATE SET value = $1")
        .bind(serde_json::to_value(s).map_err(|_| ApiError::internal())?)
        .execute(db)
        .await?;
    Ok(())
}
