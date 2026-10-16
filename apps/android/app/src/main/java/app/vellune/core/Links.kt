package app.vellune.core

// Backlink, tag and graph index derived from pages; a port of packages/ui/src/links.ts and the
// reference rules of markdown.ts. Derived data: rebuilt whenever pages change.

data class WikiLink(val target: String, val alias: String? = null)

/** `[[target]]`, `[[target|alias]]`; `#heading` / `^block` suffixes dropped. */
fun extractWikilinks(text: String): List<WikiLink> {
    val links = mutableListOf<WikiLink>()
    var rest = text
    while (true) {
        val start = rest.indexOf("[[")
        if (start < 0) break
        rest = rest.substring(start + 2)
        val end = rest.indexOf("]]")
        if (end < 0) break
        val inner = rest.substring(0, end)
        val nested = inner.lastIndexOf("[[")
        if (nested >= 0) {
            rest = rest.substring(nested)
            continue
        }
        rest = rest.substring(end + 2)
        if ('\n' in inner) continue
        val bar = inner.indexOf('|')
        val raw = if (bar >= 0) inner.substring(0, bar) else inner
        val alias = if (bar >= 0) inner.substring(bar + 1).trim() else ""
        val target = raw.split('#', '^')[0].trim()
        if (target.isNotEmpty()) links += WikiLink(target, alias.ifEmpty { null })
    }
    return links
}

// Obsidian rule: a tag needs at least one non-digit character.
private val TAG_RE = Regex("""(?:^|\s)#([\p{L}\p{N}_\-/]+)""")

fun extractInlineTags(text: String): List<String> =
    TAG_RE.findAll(text).map { it.groupValues[1] }.filterNot { it.all(Char::isDigit) }.toList()

class PageRefs(val links: List<WikiLink>, val tags: List<String>, val embeds: List<String>)

fun pageReferences(page: Page): PageRefs {
    val links = mutableListOf<WikiLink>()
    val tags = LinkedHashSet(page.tags)
    for (b in page.blocks) {
        if (b.kind == "code" || b.kind == "math") continue
        links += extractWikilinks(b.text)
        tags += extractInlineTags(b.text)
    }
    for (i in page.canvas.items) if (i.type == "sticky" && i.text != null) links += extractWikilinks(i.text)
    val embeds = page.canvas.items.mapNotNull { if (it.type == "embed") it.pageId else null }
    return PageRefs(links, tags.toList(), embeds)
}

data class Backlink(val sourceId: String, val context: String)

class LinkIndex(
    val resolve: (String) -> Page?,
    val outgoing: Map<String, Pair<Set<String>, Set<String>>>,
    val backlinks: Map<String, List<Backlink>>,
    val tags: Map<String, Set<String>>,
)

private fun norm(s: String) = s.trim().lowercase()

fun buildIndex(all: Collection<Page>): LinkIndex {
    // Folders are sidebar structure, not notes: never link targets or graph nodes.
    val pages = all.filter { it.deletedAt == null && !it.isFolder }
    val titles = HashMap<String, Page>()
    val aliases = HashMap<String, Page>()
    for (p in pages) {
        titles.putIfAbsent(norm(p.title), p)
        for (a in p.aliases) aliases.putIfAbsent(norm(a), p)
    }
    val resolve = { name: String -> titles[norm(name)] ?: aliases[norm(name)] }
    val byId = pages.associateBy { it.id }
    val outgoing = HashMap<String, Pair<Set<String>, Set<String>>>()
    val backlinks = HashMap<String, MutableList<Backlink>>()
    val tags = HashMap<String, MutableSet<String>>()
    for (p in pages) {
        val resolved = LinkedHashSet<String>()
        val unresolved = LinkedHashSet<String>()
        val refs = pageReferences(p)
        for (t in refs.tags) tags.getOrPut(t) { LinkedHashSet() } += p.id
        val contexts = p.blocks.filter { it.kind != "code" && it.kind != "math" }.map { it.text } +
            p.canvas.items.filter { it.type == "sticky" }.map { it.text.orEmpty() }
        for (l in refs.links) {
            val target = resolve(l.target)
            if (target == null) {
                unresolved += l.target
                continue
            }
            if (target.id == p.id || target.id in resolved) continue
            resolved += target.id
            backlinks.getOrPut(target.id) { mutableListOf() } += Backlink(p.id, contexts.firstOrNull { "[[${l.target}" in it }.orEmpty())
        }
        for (e in refs.embeds) {
            if (e == p.id || e !in byId || e in resolved) continue
            resolved += e
            backlinks.getOrPut(e) { mutableListOf() } += Backlink(p.id, "Embedded on canvas")
        }
        outgoing[p.id] = resolved to unresolved
    }
    return LinkIndex(resolve, outgoing, backlinks, tags)
}

enum class NodeKind { Page, Journal, Unresolved, Tag }

data class GraphNode(val id: String, val label: String, val kind: NodeKind, var degree: Int = 0)
data class GraphEdge(val source: String, val target: String)

fun buildGraph(
    pages: Collection<Page>,
    index: LinkIndex,
    showUnresolved: Boolean = false,
    showTags: Boolean = false,
    showOrphans: Boolean = true,
    focusId: String? = null,
    depth: Int = 1,
): Pair<List<GraphNode>, List<GraphEdge>> {
    val live = pages.filter { it.deletedAt == null && !it.isFolder }
    val nodes = LinkedHashMap<String, GraphNode>()
    val edges = mutableListOf<GraphEdge>()
    for (p in live) nodes[p.id] = GraphNode(p.id, p.displayTitle, if (p.journal != null) NodeKind.Journal else NodeKind.Page)
    for (p in live) {
        val (resolved, unresolved) = index.outgoing[p.id] ?: continue
        for (t in resolved) if (t in nodes) edges += GraphEdge(p.id, t)
        if (showUnresolved) for (name in unresolved) {
            val id = "unresolved:${name.lowercase()}"
            nodes.getOrPut(id) { GraphNode(id, name, NodeKind.Unresolved) }
            edges += GraphEdge(p.id, id)
        }
    }
    if (showTags) for ((tag, ids) in index.tags) {
        val id = "tag:$tag"
        for (pid in ids) if (pid in nodes) {
            nodes.getOrPut(id) { GraphNode(id, "#$tag", NodeKind.Tag) }
            edges += GraphEdge(pid, id)
        }
    }
    for (e in edges) {
        nodes[e.source]?.let { it.degree++ }
        nodes[e.target]?.let { it.degree++ }
    }
    var keep: Set<String> = nodes.keys
    if (focusId != null && focusId in nodes) {
        val seen = mutableSetOf(focusId)
        var frontier = setOf(focusId)
        repeat(depth) {
            val next = edges.flatMap { e ->
                when {
                    e.source in frontier -> listOf(e.target)
                    e.target in frontier -> listOf(e.source)
                    else -> emptyList()
                }
            }.toSet() - seen
            seen += next
            frontier = next
        }
        keep = seen
    }
    if (!showOrphans) keep = keep.filter { (nodes[it]?.degree ?: 0) > 0 || it == focusId }.toSet()
    return nodes.values.filter { it.id in keep } to edges.filter { it.source in keep && it.target in keep }
}
