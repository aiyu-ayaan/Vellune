//! Backlink and knowledge graph index structures.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use vellune_domain::PageId;

/// Directed edge between two knowledge pages.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct GraphEdge {
    pub source: PageId,
    pub target: PageId,
    pub label: Option<String>,
}

/// In-memory graph index for backlinks, forward links, and tags.
#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct KnowledgeGraph {
    /// Outgoing links: Source -> Set of Targets
    pub forward_links: HashMap<PageId, HashSet<PageId>>,
    /// Incoming backlinks: Target -> Set of Sources
    pub backlinks: HashMap<PageId, HashSet<PageId>>,
    /// Tags associated with pages: Tag -> Set of PageIds
    pub tag_index: HashMap<String, HashSet<PageId>>,
}

impl KnowledgeGraph {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_link(&mut self, source: PageId, target: PageId) {
        self.forward_links.entry(source).or_default().insert(target);
        self.backlinks.entry(target).or_default().insert(source);
    }

    pub fn remove_link(&mut self, source: &PageId, target: &PageId) {
        if let Some(targets) = self.forward_links.get_mut(source) {
            targets.remove(target);
        }
        if let Some(sources) = self.backlinks.get_mut(target) {
            sources.remove(source);
        }
    }

    pub fn get_backlinks(&self, page_id: &PageId) -> Option<&HashSet<PageId>> {
        self.backlinks.get(page_id)
    }

    pub fn get_forward_links(&self, page_id: &PageId) -> Option<&HashSet<PageId>> {
        self.forward_links.get(page_id)
    }
}
