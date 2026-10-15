//! CRDT adapter and merge logic for concurrent offline-first synchronization.

use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Unique client identifier for causality tracking.
pub type ClientId = u64;

/// Logical clock representing state progression.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Clock {
    pub client_id: ClientId,
    pub sequence: u64,
}

/// A serialized CRDT update chunk.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CrdtUpdate {
    pub id: Uuid,
    pub clock: Clock,
    pub payload: Vec<u8>,
}
