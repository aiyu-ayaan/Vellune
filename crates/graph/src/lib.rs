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
    /// Reverse of `tag_index`: PageId -> Set of Tags
    #[serde(default)]
    pub page_tags: HashMap<PageId, HashSet<String>>,
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

    /// Replace all outgoing links of `source` (re-index after an edit).
    pub fn set_page_links(&mut self, source: PageId, targets: impl IntoIterator<Item = PageId>) {
        for old in self.forward_links.remove(&source).unwrap_or_default() {
            if let Some(sources) = self.backlinks.get_mut(&old) {
                sources.remove(&source);
                if sources.is_empty() {
                    self.backlinks.remove(&old);
                }
            }
        }
        for target in targets {
            self.add_link(source, target);
        }
    }

    /// Replace all tags of `page`.
    pub fn set_page_tags(&mut self, page: PageId, tags: impl IntoIterator<Item = String>) {
        for old in self.page_tags.remove(&page).unwrap_or_default() {
            if let Some(pages) = self.tag_index.get_mut(&old) {
                pages.remove(&page);
                if pages.is_empty() {
                    self.tag_index.remove(&old);
                }
            }
        }
        let tags: HashSet<String> = tags.into_iter().collect();
        for tag in &tags {
            self.tag_index.entry(tag.clone()).or_default().insert(page);
        }
        if !tags.is_empty() {
            self.page_tags.insert(page, tags);
        }
    }

    /// Drop a deleted page's outgoing links and tags. Links from other pages stay
    /// until those pages are re-indexed, so they surface as broken links.
    pub fn remove_page(&mut self, page: &PageId) {
        self.set_page_links(*page, []);
        self.set_page_tags(*page, []);
    }

    pub fn pages_with_tag(&self, tag: &str) -> Option<&HashSet<PageId>> {
        self.tag_index.get(tag)
    }

    pub fn get_backlinks(&self, page_id: &PageId) -> Option<&HashSet<PageId>> {
        self.backlinks.get(page_id)
    }

    pub fn get_forward_links(&self, page_id: &PageId) -> Option<&HashSet<PageId>> {
        self.forward_links.get(page_id)
    }
}

/// Resolves wikilink targets to pages by title, then alias (case-insensitive).
#[derive(Debug, Default, Clone)]
pub struct LinkResolver {
    titles: HashMap<String, PageId>,
    aliases: HashMap<String, PageId>,
}

impl LinkResolver {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a page. The first page registered under a name wins on collisions.
    pub fn add_page<'a>(
        &mut self,
        page: PageId,
        title: &str,
        aliases: impl IntoIterator<Item = &'a str>,
    ) {
        self.titles.entry(normalize(title)).or_insert(page);
        for alias in aliases {
            self.aliases.entry(normalize(alias)).or_insert(page);
        }
    }

    pub fn resolve(&self, target: &str) -> Option<PageId> {
        let key = normalize(target);
        self.titles
            .get(&key)
            .or_else(|| self.aliases.get(&key))
            .copied()
    }
}

fn normalize(name: &str) -> String {
    name.trim().to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn set_page_links_replaces_edges() {
        let (a, b, c) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
        let mut g = KnowledgeGraph::new();
        g.set_page_links(a, [b, c]);
        assert_eq!(g.get_backlinks(&b), Some(&HashSet::from([a])));

        g.set_page_links(a, [c]);
        assert_eq!(g.get_backlinks(&b), None);
        assert_eq!(g.get_forward_links(&a), Some(&HashSet::from([c])));

        g.remove_page(&a);
        assert_eq!(g.get_backlinks(&c), None);
        assert_eq!(g.get_forward_links(&a), None);
    }

    #[test]
    fn set_page_tags_replaces_tags() {
        let (a, b) = (Uuid::new_v4(), Uuid::new_v4());
        let mut g = KnowledgeGraph::new();
        g.set_page_tags(a, ["rust".to_string(), "sync".to_string()]);
        g.set_page_tags(b, ["rust".to_string()]);
        g.set_page_tags(a, ["sync".to_string()]);
        assert_eq!(g.pages_with_tag("rust"), Some(&HashSet::from([b])));
        assert_eq!(g.pages_with_tag("sync"), Some(&HashSet::from([a])));
        g.remove_page(&a);
        assert_eq!(g.pages_with_tag("sync"), None);
    }

    #[test]
    fn resolver_prefers_titles_over_aliases() {
        let (a, b) = (Uuid::new_v4(), Uuid::new_v4());
        let mut r = LinkResolver::new();
        r.add_page(a, "Design", ["Arch"]);
        r.add_page(b, "Arch", ["design notes"]);
        assert_eq!(r.resolve(" design "), Some(a));
        assert_eq!(r.resolve("ARCH"), Some(b));
        assert_eq!(r.resolve("Design Notes"), Some(b));
        assert_eq!(r.resolve("missing"), None);
    }
}
