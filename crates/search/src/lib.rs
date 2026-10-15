//! Search indexing and query abstractions.

use serde::{Deserialize, Serialize};
use vellune_domain::{PageId, WorkspaceId};

/// Search query parameters.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchQuery {
    pub workspace_id: WorkspaceId,
    pub term: String,
    pub limit: usize,
}

/// A matched search result item.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchHit {
    pub page_id: PageId,
    pub title: String,
    pub snippet: String,
    pub score: f32,
}

/// Search service abstraction.
pub trait SearchIndex: Send + Sync {
    fn index_page(&self, page_id: PageId, title: &str, content: &str) -> Result<(), String>;
    fn search(&self, query: &SearchQuery) -> Result<Vec<SearchHit>, String>;
}
