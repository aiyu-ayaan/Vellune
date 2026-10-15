//! Editor transactions, user commands, and undo/redo state management.

use vellune_document::{Block, BlockKind, Document};
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

/// Reasons a command cannot be applied to a document.
#[derive(Debug, Clone, PartialEq)]
pub enum EditorError {
    BlockNotFound(BlockId),
    DuplicateBlock(BlockId),
    PageMismatch { expected: PageId, found: PageId },
    InvalidOrder(f64),
}

impl std::fmt::Display for EditorError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EditorError::BlockNotFound(id) => write!(f, "block not found: {id}"),
            EditorError::DuplicateBlock(id) => write!(f, "block already exists: {id}"),
            EditorError::PageMismatch { expected, found } => {
                write!(f, "block belongs to page {found}, expected {expected}")
            }
            EditorError::InvalidOrder(o) => write!(f, "invalid order index: {o}"),
        }
    }
}

impl std::error::Error for EditorError {}

impl EditorCommand {
    /// Apply to `doc`, returning the command that reverts it.
    fn apply(&self, doc: &mut Document) -> Result<EditorCommand, EditorError> {
        let index_of = |doc: &Document, id: &BlockId| {
            doc.blocks
                .iter()
                .position(|b| &b.id == id)
                .ok_or(EditorError::BlockNotFound(*id))
        };

        match self {
            EditorCommand::InsertBlock { page_id, block } => {
                for found in [*page_id, block.page_id] {
                    if found != doc.page_id {
                        return Err(EditorError::PageMismatch {
                            expected: doc.page_id,
                            found,
                        });
                    }
                }
                if !block.order_index.is_finite() {
                    return Err(EditorError::InvalidOrder(block.order_index));
                }
                if doc.blocks.iter().any(|b| b.id == block.id) {
                    return Err(EditorError::DuplicateBlock(block.id));
                }
                doc.blocks.push(block.clone());
                sort_blocks(doc);
                Ok(EditorCommand::DeleteBlock { block_id: block.id })
            }
            EditorCommand::UpdateBlockContent { block_id, content } => {
                let i = index_of(doc, block_id)?;
                let old = std::mem::replace(&mut doc.blocks[i].content, content.clone());
                Ok(EditorCommand::UpdateBlockContent {
                    block_id: *block_id,
                    content: old,
                })
            }
            EditorCommand::ChangeBlockKind { block_id, kind } => {
                let i = index_of(doc, block_id)?;
                let old = std::mem::replace(&mut doc.blocks[i].kind, kind.clone());
                Ok(EditorCommand::ChangeBlockKind {
                    block_id: *block_id,
                    kind: old,
                })
            }
            EditorCommand::DeleteBlock { block_id } => {
                let i = index_of(doc, block_id)?;
                let block = doc.blocks.remove(i);
                Ok(EditorCommand::InsertBlock {
                    page_id: doc.page_id,
                    block,
                })
            }
            EditorCommand::MoveBlock {
                block_id,
                new_order,
            } => {
                if !new_order.is_finite() {
                    return Err(EditorError::InvalidOrder(*new_order));
                }
                let i = index_of(doc, block_id)?;
                let old = std::mem::replace(&mut doc.blocks[i].order_index, *new_order);
                sort_blocks(doc);
                Ok(EditorCommand::MoveBlock {
                    block_id: *block_id,
                    new_order: old,
                })
            }
        }
    }
}

// Stable sort keeps insertion order for equal indexes, so ties stay deterministic.
fn sort_blocks(doc: &mut Document) {
    doc.blocks
        .sort_by(|a, b| a.order_index.total_cmp(&b.order_index));
}

/// Transaction representing one or more commands applied atomically.
#[derive(Debug, Clone, PartialEq)]
pub struct Transaction {
    pub commands: Vec<EditorCommand>,
}

impl Transaction {
    /// Apply all commands or none. On success returns the inverse transaction.
    pub fn apply(&self, doc: &mut Document) -> Result<Transaction, EditorError> {
        let mut inverse = Vec::with_capacity(self.commands.len());
        for command in &self.commands {
            match command.apply(doc) {
                Ok(undo) => inverse.push(undo),
                Err(e) => {
                    // Roll back what already applied; inverses of applied commands cannot fail.
                    for undo in inverse.iter().rev() {
                        undo.apply(doc).expect("rollback of applied command");
                    }
                    return Err(e);
                }
            }
        }
        inverse.reverse();
        Ok(Transaction { commands: inverse })
    }
}

/// Undo/redo stacks of inverse transactions for one document.
#[derive(Debug, Default, Clone)]
pub struct History {
    undo_stack: Vec<Transaction>,
    redo_stack: Vec<Transaction>,
}

impl History {
    pub fn new() -> Self {
        Self::default()
    }

    /// Apply a new user transaction. Clears the redo stack.
    pub fn apply(&mut self, doc: &mut Document, tx: &Transaction) -> Result<(), EditorError> {
        let inverse = tx.apply(doc)?;
        self.undo_stack.push(inverse);
        self.redo_stack.clear();
        Ok(())
    }

    /// Revert the latest transaction. Returns false when nothing to undo.
    pub fn undo(&mut self, doc: &mut Document) -> Result<bool, EditorError> {
        Self::step(doc, &mut self.undo_stack, &mut self.redo_stack)
    }

    /// Re-apply the latest undone transaction. Returns false when nothing to redo.
    pub fn redo(&mut self, doc: &mut Document) -> Result<bool, EditorError> {
        Self::step(doc, &mut self.redo_stack, &mut self.undo_stack)
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo_stack.is_empty()
    }

    fn step(
        doc: &mut Document,
        from: &mut Vec<Transaction>,
        to: &mut Vec<Transaction>,
    ) -> Result<bool, EditorError> {
        let Some(tx) = from.pop() else {
            return Ok(false);
        };
        match tx.apply(doc) {
            Ok(inverse) => {
                to.push(inverse);
                Ok(true)
            }
            Err(e) => {
                from.push(tx);
                Err(e)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn block(page_id: PageId, content: &str, order: f64) -> Block {
        Block {
            id: Uuid::new_v4(),
            page_id,
            parent_id: None,
            kind: BlockKind::Paragraph,
            content: content.into(),
            order_index: order,
        }
    }

    fn doc() -> Document {
        Document {
            page_id: Uuid::new_v4(),
            blocks: vec![],
        }
    }

    fn contents(doc: &Document) -> Vec<&str> {
        doc.blocks.iter().map(|b| b.content.as_str()).collect()
    }

    fn insert(page_id: PageId, b: Block) -> EditorCommand {
        EditorCommand::InsertBlock { page_id, block: b }
    }

    #[test]
    fn insert_keeps_blocks_ordered() {
        let mut d = doc();
        let p = d.page_id;
        let tx = Transaction {
            commands: vec![insert(p, block(p, "b", 2.0)), insert(p, block(p, "a", 1.0))],
        };
        tx.apply(&mut d).unwrap();
        assert_eq!(contents(&d), ["a", "b"]);
    }

    #[test]
    fn failed_transaction_rolls_back() {
        let mut d = doc();
        let p = d.page_id;
        let a = block(p, "a", 1.0);
        Transaction {
            commands: vec![insert(p, a.clone())],
        }
        .apply(&mut d)
        .unwrap();
        let before = d.clone();

        let tx = Transaction {
            commands: vec![
                EditorCommand::UpdateBlockContent {
                    block_id: a.id,
                    content: "changed".into(),
                },
                EditorCommand::DeleteBlock { block_id: a.id },
                EditorCommand::DeleteBlock {
                    block_id: Uuid::new_v4(),
                },
            ],
        };
        assert!(matches!(
            tx.apply(&mut d),
            Err(EditorError::BlockNotFound(_))
        ));
        assert_eq!(d, before);
    }

    #[test]
    fn rejects_invalid_inserts_and_moves() {
        let mut d = doc();
        let p = d.page_id;
        let other_page = block(Uuid::new_v4(), "x", 1.0);
        let tx = Transaction {
            commands: vec![insert(p, other_page)],
        };
        assert!(matches!(
            tx.apply(&mut d),
            Err(EditorError::PageMismatch { .. })
        ));

        let a = block(p, "a", 1.0);
        let tx = Transaction {
            commands: vec![insert(p, a.clone()), insert(p, a.clone())],
        };
        assert_eq!(tx.apply(&mut d), Err(EditorError::DuplicateBlock(a.id)));
        assert!(d.blocks.is_empty());

        let nan = Transaction {
            commands: vec![insert(p, block(p, "n", f64::NAN))],
        };
        assert!(matches!(
            nan.apply(&mut d),
            Err(EditorError::InvalidOrder(_))
        ));
    }

    #[test]
    fn undo_redo_round_trip() {
        let mut d = doc();
        let p = d.page_id;
        let mut h = History::new();
        let a = block(p, "a", 1.0);
        let b = block(p, "b", 2.0);
        h.apply(
            &mut d,
            &Transaction {
                commands: vec![insert(p, a.clone()), insert(p, b.clone())],
            },
        )
        .unwrap();
        let after_insert = d.clone();

        h.apply(
            &mut d,
            &Transaction {
                commands: vec![
                    EditorCommand::MoveBlock {
                        block_id: a.id,
                        new_order: 3.0,
                    },
                    EditorCommand::ChangeBlockKind {
                        block_id: b.id,
                        kind: BlockKind::Heading { level: 1 },
                    },
                    EditorCommand::UpdateBlockContent {
                        block_id: b.id,
                        content: "B".into(),
                    },
                    EditorCommand::DeleteBlock { block_id: a.id },
                ],
            },
        )
        .unwrap();
        let after_edit = d.clone();
        assert_eq!(contents(&d), ["B"]);

        assert!(h.undo(&mut d).unwrap());
        assert_eq!(d, after_insert);
        assert!(h.undo(&mut d).unwrap());
        assert!(d.blocks.is_empty());
        assert!(!h.undo(&mut d).unwrap());

        assert!(h.redo(&mut d).unwrap());
        assert_eq!(d, after_insert);
        assert!(h.redo(&mut d).unwrap());
        assert_eq!(d, after_edit);
        assert!(!h.can_redo());
    }

    #[test]
    fn new_edit_clears_redo() {
        let mut d = doc();
        let p = d.page_id;
        let mut h = History::new();
        h.apply(
            &mut d,
            &Transaction {
                commands: vec![insert(p, block(p, "a", 1.0))],
            },
        )
        .unwrap();
        h.undo(&mut d).unwrap();
        assert!(h.can_redo());
        h.apply(
            &mut d,
            &Transaction {
                commands: vec![insert(p, block(p, "b", 1.0))],
            },
        )
        .unwrap();
        assert!(!h.can_redo());
    }
}
