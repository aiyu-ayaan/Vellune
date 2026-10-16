package app.vellune.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vellune.core.LocalDb
import app.vellune.core.Page
import app.vellune.core.Workspace

/** Where the app is; the same places as the web routes (/doc, /graph, /databases, /trash). */
sealed interface Dest {
    data object Home : Dest
    data class Doc(val id: String) : Dest
    data object Graph : Dest
    data class Database(val tag: String? = null) : Dest
    data object Trash : Dest
}

/** Navigation with a back stack, like browser history on the web. */
@Stable
class AppState(private val ws: Workspace, private val db: LocalDb) {
    var stack by mutableStateOf(listOf(restore()))
        private set
    val current: Dest get() = stack.last()
    var searchOpen by mutableStateOf(false)
    /** Bumped by the page top bar's "Embed page" action; the canvas opens its picker. */
    var embedRequest by mutableIntStateOf(0)

    fun navigate(d: Dest) {
        if (d == current) return
        stack = stack + d
        save(d)
    }

    fun open(id: String) = navigate(Dest.Doc(id))

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack = stack.dropLast(1)
        save(current)
        return true
    }

    /** A page disappeared (trashed, purged, revoked): drop it from history. */
    fun prune(pages: Map<String, Page>) {
        val kept = stack.filter { it !is Dest.Doc || pages[it.id]?.let { p -> p.deletedAt == null && !p.isFolder } == true }
        if (kept.size != stack.size) stack = kept.ifEmpty { listOf(Dest.Home) }
    }

    fun newDoc(parentId: String? = null, edgeless: Boolean = false): Page {
        val p = ws.createPage { it.copy(parentId = parentId, mode = if (edgeless) "edgeless" else null) }
        open(p.id)
        return p
    }

    private fun save(d: Dest) = db.setKv(
        "route",
        when (d) {
            is Dest.Doc -> "doc:${d.id}"
            Dest.Graph -> "graph"
            is Dest.Database -> "db:${d.tag.orEmpty()}"
            Dest.Trash, Dest.Home -> "home"
        },
    )

    private fun restore(): Dest = when (val r = db.kv("route").orEmpty()) {
        "graph" -> Dest.Graph
        else -> when {
            r.startsWith("doc:") -> Dest.Doc(r.removePrefix("doc:"))
            r.startsWith("db:") -> Dest.Database(r.removePrefix("db:").ifEmpty { null })
            else -> Dest.Home
        }
    }
}
