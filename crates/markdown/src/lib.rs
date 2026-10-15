//! Markdown parsing, serialization, and [[wikilink]] extraction.

use serde::{Deserialize, Serialize};
use vellune_document::{Block, BlockKind};
use vellune_domain::PageId;

/// Represents an extracted wikilink `[[Target Page|Alias]]`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WikiLink {
    pub target: String,
    pub alias: Option<String>,
}

/// Extracted YAML frontmatter metadata.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Frontmatter {
    pub title: Option<String>,
    pub tags: Vec<String>,
    pub aliases: Vec<String>,
}

/// Convert simple markdown text into document blocks.
pub fn parse_markdown_to_blocks(page_id: PageId, markdown: &str) -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut order = 1.0;

    for line in markdown.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let kind = if let Some(_h) = trimmed.strip_prefix("### ") {
            BlockKind::Heading { level: 3 }
        } else if let Some(_h) = trimmed.strip_prefix("## ") {
            BlockKind::Heading { level: 2 }
        } else if let Some(_h) = trimmed.strip_prefix("# ") {
            BlockKind::Heading { level: 1 }
        } else if let Some(_c) = trimmed.strip_prefix("- [ ] ") {
            BlockKind::Checklist { checked: false }
        } else if let Some(_c) = trimmed.strip_prefix("- [x] ") {
            BlockKind::Checklist { checked: true }
        } else {
            BlockKind::Paragraph
        };

        blocks.push(Block {
            id: uuid::Uuid::new_v4(),
            page_id,
            parent_id: None,
            kind,
            content: trimmed.to_string(),
            order_index: order,
        });
        order += 1.0;
    }

    blocks
}
