//! Storage and persistence abstractions for local SQLite and cloud PostgreSQL.

pub mod sqlite;

pub use sqlite::SqliteStore;

use vellune_document::Document;
use vellune_domain::{Page, PageId};

/// Error types for storage operations.
#[derive(Debug)]
pub enum StorageError {
    NotFound(String),
    ConnectionFailed(String),
    Serialization(String),
    Internal(String),
}

impl std::fmt::Display for StorageError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StorageError::NotFound(s) => write!(f, "Not found: {}", s),
            StorageError::ConnectionFailed(s) => write!(f, "Connection failed: {}", s),
            StorageError::Serialization(s) => write!(f, "Serialization error: {}", s),
            StorageError::Internal(s) => write!(f, "Internal error: {}", s),
        }
    }
}

impl std::error::Error for StorageError {}

/// Abstract document repository for persisting pages and blocks.
pub trait DocumentRepository: Send + Sync {
    fn get_page(&self, page_id: &PageId) -> Result<Option<Page>, StorageError>;
    fn save_page(&self, page: &Page) -> Result<(), StorageError>;
    fn get_document(&self, page_id: &PageId) -> Result<Option<Document>, StorageError>;
    fn save_document(&self, doc: &Document) -> Result<(), StorageError>;
}

/// Abstract blob store for media, attachments, and exports.
pub trait BlobStore: Send + Sync {
    fn put_blob(&self, key: &str, data: &[u8]) -> Result<(), StorageError>;
    fn get_blob(&self, key: &str) -> Result<Option<Vec<u8>>, StorageError>;
    fn delete_blob(&self, key: &str) -> Result<(), StorageError>;
}
