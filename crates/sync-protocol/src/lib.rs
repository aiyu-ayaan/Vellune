//! Wire protocol messages and synchronization contracts.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vellune_crdt::CrdtUpdate;
use vellune_domain::{PageId, WorkspaceId};

/// Current wire protocol schema version.
pub const SYNC_PROTOCOL_VERSION: u32 = 1;

/// Cursor representing a client's last acknowledged position in the log.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SyncCursor {
    pub sequence: u64,
    pub timestamp: DateTime<Utc>,
}

/// Messages sent from client to sync gateway.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ClientSyncMessage {
    Handshake {
        protocol_version: u32,
        client_id: String,
        workspace_id: WorkspaceId,
        auth_token: String,
    },
    SubscribePage {
        page_id: PageId,
        after_cursor: Option<SyncCursor>,
    },
    PushUpdates {
        page_id: PageId,
        updates: Vec<CrdtUpdate>,
    },
    Heartbeat {
        timestamp_ms: u64,
    },
}

/// Messages sent from sync gateway to connected clients.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum ServerSyncMessage {
    HandshakeAck {
        server_version: u32,
        session_id: Uuid,
    },
    PageUpdates {
        page_id: PageId,
        updates: Vec<CrdtUpdate>,
        cursor: SyncCursor,
    },
    Error {
        code: String,
        message: String,
    },
    HeartbeatAck {
        timestamp_ms: u64,
    },
}
