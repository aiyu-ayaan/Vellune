//! Canvas geometry, spatial queries, and vector stroke models.

use serde::{Deserialize, Serialize};
use uuid::Uuid;
use vellune_domain::{PageId, Stroke};

/// Unique identifier for a canvas element.
pub type CanvasElementId = Uuid;

/// 2D bounding box representing spatial bounds of an element.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BoundingBox {
    pub min_x: f64,
    pub min_y: f64,
    pub max_x: f64,
    pub max_y: f64,
}

impl BoundingBox {
    pub fn new(min_x: f64, min_y: f64, max_x: f64, max_y: f64) -> Self {
        Self { min_x, min_y, max_x, max_y }
    }

    pub fn intersects(&self, other: &BoundingBox) -> bool {
        self.min_x <= other.max_x
            && self.max_x >= other.min_x
            && self.min_y <= other.max_y
            && self.max_y >= other.min_y
    }
}

/// Canvas viewport state for zoom and pan.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Viewport {
    pub offset_x: f64,
    pub offset_y: f64,
    pub zoom: f64,
}

impl Default for Viewport {
    fn default() -> Self {
        Self {
            offset_x: 0.0,
            offset_y: 0.0,
            zoom: 1.0,
        }
    }
}

/// Elements placed on the infinite whiteboard canvas.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum CanvasElementKind {
    Stroke(Stroke),
    StickyNote { text: String, color_hex: String },
    Shape { shape_type: String, width: f64, height: f64 },
    EmbeddedDocument { target_page_id: PageId },
}

/// Positioned canvas element.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CanvasElement {
    pub id: CanvasElementId,
    pub page_id: PageId,
    pub x: f64,
    pub y: f64,
    pub z_index: i32,
    pub bounds: BoundingBox,
    pub kind: CanvasElementKind,
}
