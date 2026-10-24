package com.aiyu.vellune.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class BrushKind { Pen, Marker, Highlighter }

/**
 * One committed stroke in world units. Spike-only JSON; Phase 2 replaces it with the binary
 * stroke blob from ADR 0004 inside the CRDT doc.
 */
@Serializable
class StoredStroke(
    val id: String,
    val brush: BrushKind,
    val color: Int,
    val size: Float,
    /** `androidx.ink.brush.InputToolType` value. */
    val toolType: Int,
    /** x,y pairs. */
    val xy: FloatArray,
    /** Milliseconds since the stroke started. */
    val t: LongArray,
    val pressure: FloatArray? = null,
    val tilt: FloatArray? = null,
    val orientation: FloatArray? = null,
)

@Serializable
data class Viewport(val tx: Float = 0f, val ty: Float = 0f, val scale: Float = 1f)

@Serializable
data class CanvasDoc(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val strokes: List<StoredStroke> = emptyList(),
    val viewport: Viewport = Viewport(),
)

/** Canvases as one JSON file each under [dir]. Writes go through a temp file so a crash never truncates a canvas. */
class CanvasRepository(private val dir: File, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _canvases = MutableStateFlow<List<CanvasDoc>?>(null)

    /** All canvases, most recently edited first; null until the first load finishes. */
    val canvases: StateFlow<List<CanvasDoc>?> = _canvases.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            val docs = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
                runCatching { json.decodeFromString(CanvasDoc.serializer(), f.readText()) }.getOrNull()
            }
            _canvases.value = docs.sortedByDescending { it.updatedAt }
        }
    }

    fun get(id: String): CanvasDoc? = _canvases.value?.firstOrNull { it.id == id }

    fun create(title: String): CanvasDoc {
        val now = System.currentTimeMillis()
        val doc = CanvasDoc(id = UUID.randomUUID().toString(), title = title, createdAt = now, updatedAt = now)
        save(doc)
        return doc
    }

    fun save(doc: CanvasDoc) {
        _canvases.update { list -> (listOf(doc) + list.orEmpty().filter { it.id != doc.id }).sortedByDescending { it.updatedAt } }
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                dir.mkdirs()
                val tmp = File(dir, "${doc.id}.json.tmp")
                tmp.writeText(json.encodeToString(CanvasDoc.serializer(), doc))
                if (!tmp.renameTo(File(dir, "${doc.id}.json"))) tmp.delete()
            }
        }
    }

    fun rename(id: String, title: String) {
        get(id)?.let { save(it.copy(title = title, updatedAt = System.currentTimeMillis())) }
    }

    fun delete(id: String) {
        _canvases.update { list -> list.orEmpty().filter { it.id != id } }
        scope.launch(Dispatchers.IO) { mutex.withLock { File(dir, "$id.json").delete() } }
    }
}
