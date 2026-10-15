//! Shared REST API DTOs and payload types for client and server.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vellune_domain::{Page, PageId, WorkspaceId};

/// Request to create a new workspace.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateWorkspaceRequest {
    pub name: String,
}

/// Request to create a new page in a workspace.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreatePageRequest {
    pub workspace_id: WorkspaceId,
    pub title: String,
    pub slug: Option<String>,
}

/// Response payload containing page details.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PageResponse {
    pub id: PageId,
    pub workspace_id: WorkspaceId,
    pub title: String,
    pub slug: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl From<Page> for PageResponse {
    fn from(p: Page) -> Self {
        Self {
            id: p.id,
            workspace_id: p.workspace_id,
            title: p.title,
            slug: p.slug,
            created_at: p.created_at,
            updated_at: p.updated_at,
        }
    }
}

/// Request for a presigned attachment upload URL.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttachmentUploadRequest {
    pub workspace_id: WorkspaceId,
    pub filename: String,
    pub mime_type: String,
    pub size_bytes: u64,
}

/// Response containing presigned upload URL and attachment ID.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttachmentUploadResponse {
    pub attachment_id: Uuid,
    pub upload_url: String,
    pub expires_at: DateTime<Utc>,
}
