//! Core domain entities and invariants for Vellune.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// Unique identifier for a workspace.
pub type WorkspaceId = Uuid;
/// Unique identifier for a page.
pub type PageId = Uuid;
/// Unique identifier for a block.
pub type BlockId = Uuid;
/// Unique identifier for a stroke.
pub type StrokeId = Uuid;

/// A Vellune workspace representing an isolated knowledge boundary.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Workspace {
    pub id: WorkspaceId,
    pub name: String,
    pub owner_id: Uuid,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// A Page is the primary unit of connected content.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Page {
    pub id: PageId,
    pub workspace_id: WorkspaceId,
    pub title: String,
    pub slug: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// Stylus tools supported by the inking engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PenTool {
    Pen,
    Pencil,
    Highlighter,
    Eraser,
}

/// A single coordinate sample from a stylus or pointer device.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PenPoint {
    pub x: f32,
    pub y: f32,
    pub pressure: f32,
    pub tilt_x: f32,
    pub tilt_y: f32,
    pub timestamp_ms: u64,
}

/// A vector stroke created by pen/stylus input.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Stroke {
    pub id: StrokeId,
    pub points: Vec<PenPoint>,
    pub color_rgba: u32,
    pub base_width: f32,
    pub tool: PenTool,
}
