//! Editor transactions, user commands, and undo/redo state management.

use vellune_document::{Block, BlockKind};
use vellune_domain::{BlockId, PageId};

/// Atomic editing command applied to a document.
#[derive(Debug, Clone, PartialEq)]
pub enum EditorCommand {
    InsertBlock { page_id: PageId, block: Block },
    UpdateBlockContent { block_id: BlockId, content: String },
    ChangeBlockKind { block_id: BlockId, kind: BlockKind },
    DeleteBlock { block_id: BlockId },
    MoveBlock { block_id: BlockId, new_order: f64 },
}

/// Transaction representing one or more commands applied atomically.
#[derive(Debug, Clone)]
pub struct Transaction {
    pub commands: Vec<EditorCommand>,
}
