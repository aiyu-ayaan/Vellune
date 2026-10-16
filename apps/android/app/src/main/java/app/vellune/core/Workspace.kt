package app.vellune.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

/**
 * The page store, a port of packages/ui/src/store.ts. Every edit applies here first and is
 * diffed into the page's Yjs doc; nothing waits on the network. Call from the main thread.
 */
class Workspace(private val db: LocalDb, private val sync: SyncEngine) : SyncBridge {
    private val _pages = MutableStateFlow<Map<String, Page>>(emptyMap())
    val pages: StateFlow<Map<String, Page>> = _pages
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private var favorites: Set<String>
        get() = db.kv("favorites")?.let { j -> JSONArray(j).let { a -> List(a.length()) { a.getString(it) }.toSet() } } ?: emptySet()
        set(v) = db.setKv("favorites", JSONArray(v.toList()).toString())

    suspend fun load() {
        val list = sync.init(this, favorites)
        _pages.value = list.associateBy { it.id }
        _loaded.value = true
    }

    override fun pages(): Map<String, Page> = _pages.value

    override fun applyRemote(page: Page) {
        val p = page.copy(favorite = _pages.value[page.id]?.favorite ?: (page.id in favorites))
        _pages.update { it + (p.id to p) }
    }

    override fun removeLocal(id: String) = _pages.update { it - id }

    /** Logout / account switch. */
    fun reset() {
        _pages.value = emptyMap()
    }

    operator fun get(id: String?): Page? = id?.let { _pages.value[it] }

    // ---------- actions ----------

    fun createPage(title: String = "", init: (Page) -> Page = { it }): Page {
        val page = init(Page(title = title))
        _pages.update { it + (page.id to page) }
        sync.localChange(page)
        return page
    }

    fun updatePage(id: String, fn: (Page) -> Page) {
        val old = _pages.value[id] ?: return
        var next = fn(old).copy(updatedAt = System.currentTimeMillis())
        if (next.blocks.isEmpty() && !next.isFolder) next = next.copy(blocks = listOf(Block()))
        if (next.favorite != old.favorite) favorites = if (next.favorite) favorites + id else favorites - id
        _pages.update { it + (id to next) }
        // Favorites are per device; only shared fields reach the document.
        if (next.copy(favorite = old.favorite, updatedAt = old.updatedAt) != old) sync.localChange(next)
    }

    fun setFavorite(id: String, on: Boolean) = updatePage(id) { it.copy(favorite = on) }

    fun openToday(): Page {
        val key = todayKey()
        return _pages.value.values.firstOrNull { it.journal == key && it.deletedAt == null }
            ?: createPage(key) { it.copy(journal = key, tags = listOf("journal")) }
    }

    /** Follow a wikilink: the page it resolves to, or a new one with that title. */
    fun openOrCreate(title: String, index: LinkIndex): Page {
        index.resolve(title)?.let { return it }
        val t = title.trim()
        // A date link (from @today, @friday, …) becomes that day's journal page, as on the web.
        val day = runCatching { java.time.LocalDate.parse(t) }.getOrNull()
        return if (day != null) createPage(t) { it.copy(journal = t, tags = listOf("journal")) } else createPage(t)
    }

    fun descendants(id: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(pid: String) {
            for (p in _pages.value.values) if (p.parentId == pid && p.id !in out) {
                out += p.id
                walk(p.id)
            }
        }
        walk(id)
        return out
    }

    /** Children of [parentId] (null = root) in display order: manual order, then folders first, then title. */
    fun children(parentId: String?, includeDeleted: Boolean = false): List<Page> {
        val pages = _pages.value
        fun atRoot(p: Page) = p.parentId == null || pages[p.parentId] == null || (!includeDeleted && pages[p.parentId]!!.deletedAt != null)
        return pages.values
            .filter { (includeDeleted || it.deletedAt == null) && (if (parentId == null) atRoot(it) else it.parentId == parentId) }
            .sortedWith(
                compareBy<Page> { it.order ?: Double.MAX_VALUE }
                    .thenByDescending { it.isFolder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle },
            )
    }

    fun createFolder(title: String = "New folder", parentId: String? = null): Page =
        createPage(title) { it.copy(folder = true, parentId = parentId, blocks = emptyList()) }

    /** Move [id] into [parentId] (null = root), before sibling [beforeId] (or last). */
    fun move(id: String, parentId: String?, beforeId: String? = null): Boolean {
        if (id == parentId || (parentId != null && parentId in descendants(id))) return false
        val siblings = children(parentId).filter { it.id != id }
        val at = beforeId?.let { b -> siblings.indexOfFirst { it.id == b } } ?: -1
        val idx = if (at < 0) siblings.size else at
        // Give siblings explicit positions once, so a manual move is stable on every device.
        siblings.forEachIndexed { i, p ->
            val want = (if (i < idx) i else i + 1).toDouble()
            if (p.order != want) updatePage(p.id) { it.copy(order = want) }
        }
        updatePage(id) { it.copy(parentId = parentId, order = idx.toDouble()) }
        return true
    }

    fun trash(id: String) {
        val now = System.currentTimeMillis()
        for (pid in listOf(id) + descendants(id)) {
            if (_pages.value[pid]?.deletedAt == null) updatePage(pid) { it.copy(deletedAt = now, favorite = false) }
        }
    }

    /** Restores the page, everything trashed with it below it, and any trashed ancestors it needs. */
    fun restore(id: String) {
        val whenDeleted = _pages.value[id]?.deletedAt
        for (pid in descendants(id)) if (whenDeleted != null && _pages.value[pid]?.deletedAt == whenDeleted) {
            updatePage(pid) { it.copy(deletedAt = null) }
        }
        var p = _pages.value[id]
        while (p != null) {
            if (p.deletedAt != null) updatePage(p.id) { it.copy(deletedAt = null) }
            p = p.parentId?.let { _pages.value[it] }
        }
    }

    fun purge(id: String) {
        _pages.update { it - id }
        favorites = favorites - id
        sync.deleteRemote(id)
    }

    /** Copy a doc (fresh page and block ids) right after the original, same parent. */
    fun duplicate(id: String): Page? {
        val src = _pages.value[id]?.takeIf { !it.isFolder } ?: return null
        val parent = src.parentId?.takeIf { _pages.value[it] != null }
        val sibs = children(parent)
        val next = sibs.getOrNull(sibs.indexOfFirst { it.id == id } + 1)
        val copy = createPage("Copy of ${src.displayTitle}") {
            it.copy(
                blocks = src.blocks.map { b -> b.copy(id = newId()) },
                tags = src.tags, canvas = src.canvas, extraFrontmatter = src.extraFrontmatter, parentId = src.parentId, mode = src.mode,
            )
        }
        move(copy.id, parent, next?.id)
        return _pages.value[copy.id]
    }
}
