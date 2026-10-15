//! Canonical document and block hierarchy model for Vellune.

use serde::{Deserialize, Serialize};
use vellune_domain::{BlockId, PageId};

/// Types of blocks supported in Vellune documents.
/// Serialized internally tagged (`{"type": "Heading", "level": 1}`) per docs/document-format.md.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum BlockKind {
    Paragraph,
    Heading { level: u8 },
    Checklist { checked: bool },
    CodeBlock { language: Option<String> },
    DrawingBlock,
    ImageAttachment { attachment_id: uuid::Uuid },
    EmbeddedPage { target_page_id: PageId },
    CanvasNode { x: f64, y: f64, width: f64, height: f64 },
}

/// A block is the elemental composable unit of content in a page.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Block {
    pub id: BlockId,
    pub page_id: PageId,
    pub parent_id: Option<BlockId>,
    pub kind: BlockKind,
    pub content: String,
    pub order_index: f64,
}

/// Represents a loaded page with all of its constituent blocks.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Document {
    pub page_id: PageId,
    pub blocks: Vec<Block>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn block_kind_json_matches_spec() {
        let kind = BlockKind::Heading { level: 1 };
        let json = serde_json::to_value(&kind).unwrap();
        assert_eq!(json, serde_json::json!({"type": "Heading", "level": 1}));
        assert_eq!(serde_json::from_value::<BlockKind>(json).unwrap(), kind);
        assert_eq!(
            serde_json::to_value(BlockKind::Paragraph).unwrap(),
            serde_json::json!({"type": "Paragraph"})
        );
    }
}
