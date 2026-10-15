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
    /// Verbatim YAML between the `---` fences, re-emitted on export so unknown keys survive.
    pub raw: Option<String>,
}

/// A Markdown file split into Vellune's model.
#[derive(Debug, Clone, PartialEq)]
pub struct MarkdownDocument {
    pub frontmatter: Frontmatter,
    pub blocks: Vec<Block>,
    pub links: Vec<WikiLink>,
}

/// Prefix of the HTML comment that carries blocks Markdown cannot express.
const META_PREFIX: &str = "<!-- vellune-meta: ";
const META_SUFFIX: &str = " -->";

#[derive(Serialize, Deserialize)]
struct MetaBlock {
    kind: BlockKind,
    content: String,
}

/// Convert markdown text into document blocks, skipping frontmatter.
pub fn parse_markdown_to_blocks(page_id: PageId, markdown: &str) -> Vec<Block> {
    import_markdown(page_id, markdown).blocks
}

/// Parse a Markdown file: frontmatter, blocks, and outgoing wikilinks.
pub fn import_markdown(page_id: PageId, markdown: &str) -> MarkdownDocument {
    let (frontmatter, body) = split_frontmatter(markdown);
    let blocks = parse_blocks(page_id, body);
    let links = blocks
        .iter()
        .filter(|b| !matches!(b.kind, BlockKind::CodeBlock { .. }))
        .flat_map(|b| extract_wikilinks(&b.content))
        .collect();
    MarkdownDocument {
        frontmatter,
        blocks,
        links,
    }
}

/// Serialize blocks (in `order_index` order) back to Markdown.
pub fn export_markdown(frontmatter: &Frontmatter, blocks: &[Block]) -> String {
    let mut out = String::new();
    if let Some(yaml) = frontmatter
        .raw
        .clone()
        .or_else(|| render_frontmatter(frontmatter))
    {
        out.push_str("---\n");
        out.push_str(&yaml);
        if !yaml.is_empty() && !yaml.ends_with('\n') {
            out.push('\n');
        }
        out.push_str("---\n");
        if !blocks.is_empty() {
            out.push('\n');
        }
    }

    let mut ordered: Vec<&Block> = blocks.iter().collect();
    ordered.sort_by(|a, b| a.order_index.total_cmp(&b.order_index));
    let rendered: Vec<String> = ordered.iter().map(|b| render_block(b)).collect();
    out.push_str(&rendered.join("\n\n"));
    if !rendered.is_empty() {
        out.push('\n');
    }
    out
}

/// Extract `[[target]]`, `[[target|alias]]`, and `![[embed]]` links from text.
/// `#heading` / `^block` suffixes are dropped from the target.
pub fn extract_wikilinks(text: &str) -> Vec<WikiLink> {
    let mut links = Vec::new();
    let mut rest = text;
    while let Some(start) = rest.find("[[") {
        rest = &rest[start + 2..];
        let Some(end) = rest.find("]]") else { break };
        let inner = &rest[..end];
        // `[[a [[b]]` : restart at the inner opening.
        if let Some(nested) = inner.rfind("[[") {
            rest = &rest[nested..];
            continue;
        }
        rest = &rest[end + 2..];
        if inner.contains('\n') {
            continue;
        }
        let (target, alias) = match inner.split_once('|') {
            Some((t, a)) => (t, Some(a.trim()).filter(|a| !a.is_empty())),
            None => (inner, None),
        };
        let target = target.split(['#', '^']).next().unwrap_or("").trim();
        if !target.is_empty() {
            links.push(WikiLink {
                target: target.to_string(),
                alias: alias.map(str::to_string),
            });
        }
    }
    links
}

fn split_frontmatter(markdown: &str) -> (Frontmatter, &str) {
    let Some(after_open) = markdown
        .strip_prefix("---\n")
        .or_else(|| markdown.strip_prefix("---\r\n"))
    else {
        return (Frontmatter::default(), markdown);
    };
    let mut offset = 0;
    for line in after_open.split_inclusive('\n') {
        if matches!(line.trim_end(), "---" | "...") {
            let raw = &after_open[..offset];
            let mut fm = parse_frontmatter_fields(raw);
            fm.raw = Some(raw.to_string());
            let body = &after_open[offset + line.len()..];
            return (fm, body);
        }
        offset += line.len();
    }
    // Unterminated fence: treat everything as body so no content is lost.
    (Frontmatter::default(), markdown)
}

// shortcut: reads only top-level title/tags/aliases with flat lists; unknown keys survive via `raw`.
// Upgrade to a YAML parser when nested frontmatter values need editing.
fn parse_frontmatter_fields(yaml: &str) -> Frontmatter {
    let mut fm = Frontmatter::default();
    let mut list_key: Option<String> = None;
    for line in yaml.lines() {
        if let Some(item) = line
            .trim_start()
            .strip_prefix("- ")
            .filter(|_| line.starts_with([' ', '-']))
        {
            if let Some(key) = &list_key {
                push_list(&mut fm, key, unquote(item));
            }
            continue;
        }
        list_key = None;
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        if line.starts_with(' ') {
            continue;
        }
        let key = key.trim();
        let value = value.trim();
        match key {
            "title" if !value.is_empty() => fm.title = Some(unquote(value).to_string()),
            "tags" | "tag" | "aliases" | "alias" => {
                if value.is_empty() {
                    list_key = Some(key.to_string());
                } else if let Some(inner) =
                    value.strip_prefix('[').and_then(|v| v.strip_suffix(']'))
                {
                    for item in inner.split(',') {
                        push_list(&mut fm, key, unquote(item));
                    }
                } else {
                    push_list(&mut fm, key, unquote(value));
                }
            }
            _ => {}
        }
    }
    fm
}

fn push_list(fm: &mut Frontmatter, key: &str, item: &str) {
    if item.is_empty() {
        return;
    }
    match key {
        "tags" | "tag" => fm.tags.push(item.trim_start_matches('#').to_string()),
        _ => fm.aliases.push(item.to_string()),
    }
}

fn unquote(s: &str) -> &str {
    let s = s.trim();
    for q in ['"', '\''] {
        if let Some(inner) = s.strip_prefix(q).and_then(|v| v.strip_suffix(q)) {
            return inner;
        }
    }
    s
}

fn render_frontmatter(fm: &Frontmatter) -> Option<String> {
    if fm.title.is_none() && fm.tags.is_empty() && fm.aliases.is_empty() {
        return None;
    }
    let mut yaml = String::new();
    if let Some(title) = &fm.title {
        yaml.push_str(&format!("title: {}\n", yaml_scalar(title)));
    }
    for (key, items) in [("tags", &fm.tags), ("aliases", &fm.aliases)] {
        if !items.is_empty() {
            // Block lists: the reader splits inline `[a, b]` lists on every comma.
            yaml.push_str(&format!("{key}:\n"));
            for item in items {
                yaml.push_str(&format!("  - {}\n", yaml_scalar(item)));
            }
        }
    }
    Some(yaml)
}

fn yaml_scalar(s: &str) -> String {
    let plain = !s.is_empty()
        && !s.starts_with(|c: char| c.is_whitespace() || "-?:,[]{}#&*!|>'\"%@`".contains(c))
        && !s.ends_with(char::is_whitespace)
        && !s.contains(": ")
        && !s.contains(" #")
        && !s.contains([',', '[', ']', '{', '}', '\n']);
    if plain {
        s.to_string()
    } else {
        format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
    }
}

fn parse_blocks(page_id: PageId, body: &str) -> Vec<Block> {
    let mut blocks = Vec::new();
    let mut push = |kind: BlockKind, content: String| {
        let order_index = blocks.len() as f64 + 1.0;
        blocks.push(Block {
            id: uuid::Uuid::new_v4(),
            page_id,
            parent_id: None,
            kind,
            content,
            order_index,
        });
    };

    let mut paragraph: Vec<&str> = Vec::new();
    let mut lines = body.lines();
    while let Some(line) = lines.next() {
        let leaf = parse_leaf_line(line);
        let is_fence = fence_marker(line).is_some();
        if (line.trim().is_empty() || leaf.is_some() || is_fence) && !paragraph.is_empty() {
            push(BlockKind::Paragraph, paragraph.join("\n"));
            paragraph.clear();
        }

        if let Some((fence_char, fence_len, language)) = fence_marker(line) {
            let mut code = Vec::new();
            for inner in lines.by_ref() {
                let t = inner.trim();
                if t.len() >= fence_len && t.chars().all(|c| c == fence_char) {
                    break;
                }
                code.push(inner);
            }
            push(BlockKind::CodeBlock { language }, code.join("\n"));
        } else if let Some((kind, content)) = leaf {
            push(kind, content);
        } else if !line.trim().is_empty() {
            paragraph.push(line.trim_end());
        }
    }
    if !paragraph.is_empty() {
        push(BlockKind::Paragraph, paragraph.join("\n"));
    }
    blocks
}

/// Returns fence char, fence length (>= 3), and optional language for an opening fence line.
fn fence_marker(line: &str) -> Option<(char, usize, Option<String>)> {
    let trimmed = line.trim_start();
    let fence_char = trimmed.chars().next().filter(|c| *c == '`' || *c == '~')?;
    let len = trimmed.chars().take_while(|&c| c == fence_char).count();
    if len < 3 {
        return None;
    }
    let language = trimmed[len..].split_whitespace().next().map(str::to_string);
    Some((fence_char, len, language))
}

/// Single-line blocks: headings, checklist items, and vellune-meta comments.
fn parse_leaf_line(line: &str) -> Option<(BlockKind, String)> {
    let trimmed = line.trim();
    if let Some(json) = trimmed
        .strip_prefix(META_PREFIX)
        .and_then(|s| s.strip_suffix(META_SUFFIX))
    {
        if let Ok(meta) = serde_json::from_str::<MetaBlock>(json) {
            return Some((meta.kind, meta.content));
        }
    }

    let hashes = trimmed.chars().take_while(|&c| c == '#').count();
    if (1..=6).contains(&hashes) {
        if let Some(text) = trimmed[hashes..].strip_prefix(' ') {
            return Some((
                BlockKind::Heading {
                    level: hashes as u8,
                },
                text.trim().to_string(),
            ));
        }
    }

    for bullet in ["- ", "* "] {
        let Some(item) = trimmed.strip_prefix(bullet) else {
            continue;
        };
        for (mark, checked) in [("[ ] ", false), ("[x] ", true), ("[X] ", true)] {
            if let Some(text) = item.strip_prefix(mark) {
                return Some((BlockKind::Checklist { checked }, text.to_string()));
            }
        }
    }
    None
}

fn render_block(block: &Block) -> String {
    match &block.kind {
        BlockKind::Paragraph => block.content.clone(),
        BlockKind::Heading { level } => {
            format!(
                "{} {}",
                "#".repeat((*level).clamp(1, 6) as usize),
                block.content
            )
        }
        BlockKind::Checklist { checked } => {
            format!("- [{}] {}", if *checked { 'x' } else { ' ' }, block.content)
        }
        BlockKind::CodeBlock { language } => {
            // Lengthen the fence past any backtick run inside the code.
            let longest = block
                .content
                .lines()
                .map(|l| l.trim_start().chars().take_while(|&c| c == '`').count())
                .max()
                .unwrap_or(0);
            let fence = "`".repeat(longest.max(2) + 1);
            format!(
                "{fence}{}\n{}\n{fence}",
                language.as_deref().unwrap_or(""),
                block.content
            )
        }
        kind => {
            let meta = MetaBlock {
                kind: kind.clone(),
                content: block.content.clone(),
            };
            // "-->" can only occur inside JSON strings, where > is an equivalent escape.
            let json = serde_json::to_string(&meta)
                .expect("block kind serializes")
                .replace("-->", "--\\u003e");
            format!("{META_PREFIX}{json}{META_SUFFIX}")
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn kinds_and_contents(blocks: &[Block]) -> Vec<(BlockKind, &str)> {
        blocks
            .iter()
            .map(|b| (b.kind.clone(), b.content.as_str()))
            .collect()
    }

    #[test]
    fn parses_frontmatter_inline_and_block_lists() {
        let md = "---\ntitle: \"Local-First Design\"\ntags: [architecture, '#sync']\naliases:\n  - System Architecture\n  - Vellune Design\ncustom:\n  nested: 1\n---\n# Hello\n";
        let doc = import_markdown(Uuid::new_v4(), md);
        let fm = &doc.frontmatter;
        assert_eq!(fm.title.as_deref(), Some("Local-First Design"));
        assert_eq!(fm.tags, ["architecture", "sync"]);
        assert_eq!(fm.aliases, ["System Architecture", "Vellune Design"]);
        assert!(fm.raw.as_deref().unwrap().contains("nested: 1"));
        assert_eq!(
            kinds_and_contents(&doc.blocks),
            [(BlockKind::Heading { level: 1 }, "Hello")]
        );
    }

    #[test]
    fn unterminated_frontmatter_is_kept_as_body() {
        let doc = import_markdown(Uuid::new_v4(), "---\ntitle: x\nbody text");
        assert_eq!(doc.frontmatter, Frontmatter::default());
        assert!(doc.blocks.iter().any(|b| b.content.contains("body text")));
    }

    #[test]
    fn parses_block_kinds() {
        let md = "## Title\n\nline one\nline two\n- [ ] todo\n- [x] done\n\n```rust\nfn main() {}\n\n# not heading\n```\n###### six\n####### seven";
        let doc = import_markdown(Uuid::new_v4(), md);
        assert_eq!(
            kinds_and_contents(&doc.blocks),
            [
                (BlockKind::Heading { level: 2 }, "Title"),
                (BlockKind::Paragraph, "line one\nline two"),
                (BlockKind::Checklist { checked: false }, "todo"),
                (BlockKind::Checklist { checked: true }, "done"),
                (
                    BlockKind::CodeBlock {
                        language: Some("rust".into())
                    },
                    "fn main() {}\n\n# not heading"
                ),
                (BlockKind::Heading { level: 6 }, "six"),
                (BlockKind::Paragraph, "####### seven"),
            ]
        );
        let orders: Vec<f64> = doc.blocks.iter().map(|b| b.order_index).collect();
        assert_eq!(orders, [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0]);
    }

    #[test]
    fn extracts_wikilinks() {
        let links = extract_wikilinks("See [[Page A]], [[Page B|the b]], ![[Image]] and [[Page C#Section|c]] [[#local]] [[ ]] [[open");
        assert_eq!(
            links,
            [
                WikiLink {
                    target: "Page A".into(),
                    alias: None
                },
                WikiLink {
                    target: "Page B".into(),
                    alias: Some("the b".into())
                },
                WikiLink {
                    target: "Image".into(),
                    alias: None
                },
                WikiLink {
                    target: "Page C".into(),
                    alias: Some("c".into())
                },
            ]
        );
        assert_eq!(extract_wikilinks("[[a [[b]]")[0].target, "b");
    }

    #[test]
    fn links_skip_code_blocks() {
        let doc = import_markdown(Uuid::new_v4(), "[[Real]]\n\n```\n[[Fake]]\n```");
        assert_eq!(
            doc.links,
            [WikiLink {
                target: "Real".into(),
                alias: None
            }]
        );
    }

    #[test]
    fn round_trip_preserves_markdown() {
        let md = "---\ntitle: Note\nunknown_key: kept\n---\n\n# Heading\n\nSome text with [[Link|alias]]\nsecond line\n\n- [ ] task\n\n- [x] finished\n\n```js\nconsole.log(1)\n```\n";
        let doc = import_markdown(Uuid::new_v4(), md);
        assert_eq!(export_markdown(&doc.frontmatter, &doc.blocks), md);
    }

    #[test]
    fn rich_blocks_round_trip_through_meta_comments() {
        let page_id = Uuid::new_v4();
        let target = Uuid::new_v4();
        let blocks = vec![
            Block {
                id: Uuid::new_v4(),
                page_id,
                parent_id: None,
                kind: BlockKind::EmbeddedPage {
                    target_page_id: target,
                },
                content: "tricky --> text".into(),
                order_index: 1.0,
            },
            Block {
                id: Uuid::new_v4(),
                page_id,
                parent_id: None,
                kind: BlockKind::DrawingBlock,
                content: String::new(),
                order_index: 2.0,
            },
        ];
        let md = export_markdown(&Frontmatter::default(), &blocks);
        assert_eq!(
            md.matches("-->").count(),
            2,
            "content must not close the comment early: {md}"
        );
        let back = import_markdown(page_id, &md);
        assert_eq!(
            kinds_and_contents(&back.blocks),
            kinds_and_contents(&blocks)
        );
    }

    #[test]
    fn code_fence_grows_past_inner_backticks() {
        let page_id = Uuid::new_v4();
        let code = Block {
            id: Uuid::new_v4(),
            page_id,
            parent_id: None,
            kind: BlockKind::CodeBlock { language: None },
            content: "```\ninner\n```".into(),
            order_index: 1.0,
        };
        let md = export_markdown(&Frontmatter::default(), std::slice::from_ref(&code));
        let back = import_markdown(page_id, &md);
        assert_eq!(
            kinds_and_contents(&back.blocks),
            kinds_and_contents(&[code])
        );
    }

    #[test]
    fn generated_frontmatter_round_trips() {
        let fm = Frontmatter {
            title: Some("A: B".into()),
            tags: vec!["x".into()],
            aliases: vec!["Al, ias".into()],
            raw: None,
        };
        let md = export_markdown(&fm, &[]);
        let back = import_markdown(Uuid::new_v4(), &md).frontmatter;
        assert_eq!(
            (back.title, back.tags, back.aliases),
            (fm.title, fm.tags, fm.aliases)
        );
    }
}
