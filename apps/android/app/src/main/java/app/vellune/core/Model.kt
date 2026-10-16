package app.vellune.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

// Workspace model, field for field the web `Page` (packages/ui/src/model.ts): both clients write
// the same Yjs document. Canvas items and strokes keep fields this client does not know, so an
// edit here never strips what a newer web build added.

data class Block(
    val id: String = newId(),
    val kind: String = "paragraph",
    val text: String = "",
    val level: Int? = null,
    val checked: Boolean? = null,
    val language: String? = null,
    val src: String? = null,
    val color: String? = null,
) {
    companion object {
        val KINDS = listOf("paragraph", "heading", "todo", "bullet", "numbered", "quote", "callout", "code", "divider", "image")
    }
}

data class CanvasItem(
    val id: String = newId(),
    val type: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val text: String? = null,
    val color: String? = null,
    val pageId: String? = null,
    val extra: JSONObject = JSONObject(),
)

/** `points` is flat `[x, y, pressure, ...]` in world coordinates, as on the web. */
data class Stroke(
    val id: String = newId(),
    val points: DoubleArray,
    val color: String,
    val width: Double,
    /** pen | pencil | highlighter; absent on web-drawn strokes. */
    val tool: String? = null,
    val extra: JSONObject = JSONObject(),
) {
    override fun equals(other: Any?) = other is Stroke && other.id == id && other.points.contentEquals(points) &&
        other.color == color && other.width == width && other.tool == tool
    override fun hashCode() = id.hashCode()
}

data class Canvas(val items: List<CanvasItem> = emptyList(), val strokes: List<Stroke> = emptyList())

data class Page(
    val id: String = newId(),
    val title: String = "",
    val blocks: List<Block> = listOf(Block()),
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    /** Per person, kept on this device only; never part of the shared document. */
    val favorite: Boolean = false,
    val journal: String? = null,
    val canvas: Canvas = Canvas(),
    val extraFrontmatter: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val deletedAt: Long? = null,
    val parentId: String? = null,
    val folder: Boolean? = null,
    val order: Double? = null,
    /** "edgeless" when the page opens on its whiteboard; remembered per page on every device. */
    val mode: String? = null,
) {
    val isFolder get() = folder == true
    val isEdgeless get() = mode == "edgeless"
    val displayTitle get() = title.ifBlank { "Untitled" }
}

fun newId(): String = UUID.randomUUID().toString()

fun todayKey(): String = LocalDate.now().toString()

// ---------- JSON (the web Page shape) ----------

private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun JSONObject.optDbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
private fun JSONObject.optLng(k: String): Long? = if (has(k) && !isNull(k)) getDouble(k).toLong() else null
private fun JSONObject.optBool(k: String): Boolean? = if (has(k) && !isNull(k)) getBoolean(k) else null
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

private fun JSONObject.without(vararg keys: String) = JSONObject(toString()).also { o -> keys.forEach(o::remove) }

private val ITEM_KEYS = arrayOf("id", "type", "x", "y", "w", "h", "text", "color", "pageId")
private val STROKE_KEYS = arrayOf("id", "points", "color", "width", "tool")

fun blockFromJson(o: JSONObject) = Block(
    id = o.optStr("id") ?: newId(),
    kind = o.optStr("kind") ?: "paragraph",
    text = o.optStr("text").orEmpty(),
    level = o.optDbl("level")?.toInt(),
    checked = o.optBool("checked"),
    language = o.optStr("language"),
    src = o.optStr("src"),
    color = o.optStr("color"),
)

fun Block.toJson() = JSONObject().apply {
    put("id", id); put("kind", kind); put("text", text)
    level?.let { put("level", it) }; checked?.let { put("checked", it) }; language?.let { put("language", it) }
    src?.let { put("src", it) }; color?.let { put("color", it) }
}

fun itemFromJson(o: JSONObject) = CanvasItem(
    id = o.optStr("id") ?: newId(), type = o.optStr("type") ?: "sticky",
    x = o.optDbl("x") ?: 0.0, y = o.optDbl("y") ?: 0.0, w = o.optDbl("w") ?: 200.0, h = o.optDbl("h") ?: 160.0,
    text = o.optStr("text"), color = o.optStr("color"), pageId = o.optStr("pageId"), extra = o.without(*ITEM_KEYS),
)

fun CanvasItem.toJson() = JSONObject(extra.toString()).apply {
    put("id", id); put("type", type); put("x", x); put("y", y); put("w", w); put("h", h)
    text?.let { put("text", it) }; color?.let { put("color", it) }; pageId?.let { put("pageId", it) }
}

fun strokeFromJson(o: JSONObject): Stroke {
    val pts = o.optJSONArray("points")
    return Stroke(
        id = o.optStr("id") ?: newId(),
        points = if (pts == null) DoubleArray(0) else DoubleArray(pts.length()) { pts.optDouble(it, 0.0) },
        color = o.optStr("color") ?: "#6366f1",
        width = o.optDbl("width") ?: 3.0,
        tool = o.optStr("tool"),
        extra = o.without(*STROKE_KEYS),
    )
}

fun Stroke.toJson() = JSONObject(extra.toString()).apply {
    put("id", id)
    put("points", JSONArray().also { a -> points.forEach { a.put(it) } })
    put("color", color); put("width", width)
    tool?.let { put("tool", it) }
}

fun pageFromJson(o: JSONObject, favorite: Boolean = false): Page {
    val canvas = o.optJSONObject("canvas")
    val blocks = o.optJSONArray("blocks")
    val items = canvas?.optJSONArray("items")
    val strokes = canvas?.optJSONArray("strokes")
    return Page(
        id = o.getString("id"),
        title = o.optStr("title").orEmpty(),
        blocks = if (blocks == null) emptyList() else List(blocks.length()) { blockFromJson(blocks.getJSONObject(it)) },
        tags = o.optJSONArray("tags").strings(),
        aliases = o.optJSONArray("aliases").strings(),
        favorite = favorite,
        journal = o.optStr("journal"),
        canvas = Canvas(
            items = if (items == null) emptyList() else List(items.length()) { itemFromJson(items.getJSONObject(it)) },
            strokes = if (strokes == null) emptyList() else List(strokes.length()) { strokeFromJson(strokes.getJSONObject(it)) },
        ),
        extraFrontmatter = o.optStr("extraFrontmatter"),
        createdAt = o.optLng("createdAt") ?: System.currentTimeMillis(),
        updatedAt = o.optLng("updatedAt") ?: System.currentTimeMillis(),
        deletedAt = o.optLng("deletedAt"),
        parentId = o.optStr("parentId"),
        folder = o.optBool("folder"),
        order = o.optDbl("order"),
        mode = o.optStr("mode"),
    )
}

/** The shared document's view of a page: `favorite` stays on this device. */
fun Page.toJson() = JSONObject().apply {
    put("id", id); put("title", title)
    put("blocks", JSONArray().also { a -> blocks.forEach { a.put(it.toJson()) } })
    put("tags", JSONArray(tags)); put("aliases", JSONArray(aliases))
    journal?.let { put("journal", it) }
    put("canvas", JSONObject().apply {
        put("items", JSONArray().also { a -> canvas.items.forEach { a.put(it.toJson()) } })
        put("strokes", JSONArray().also { a -> canvas.strokes.forEach { a.put(it.toJson()) } })
    })
    extraFrontmatter?.let { put("extraFrontmatter", it) }
    put("createdAt", createdAt); put("updatedAt", updatedAt)
    deletedAt?.let { put("deletedAt", it) }; parentId?.let { put("parentId", it) }
    folder?.let { put("folder", it) }; order?.let { put("order", if (it % 1.0 == 0.0) it.toLong() else it) }
    mode?.let { put("mode", it) }
}
