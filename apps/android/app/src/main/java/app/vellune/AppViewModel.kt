package app.vellune

import android.app.Application
import android.content.Context
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.vellune.data.InkStroke
import app.vellune.data.Note
import app.vellune.data.NoteStore
import app.vellune.data.Paper
import app.vellune.ink.Brushes
import app.vellune.ink.InkEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = NoteStore(app)
    val prefs = app.getSharedPreferences("vellune", Context.MODE_PRIVATE)!!

    // One writer, so edits land in the order they were made.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val _notes = MutableStateFlow<List<Note>?>(null)
    val notes: StateFlow<List<Note>?> = _notes

    private val pendingText = HashMap<String, Job>()
    private val previews = HashMap<String, Pair<Long, List<Stroke>>>()

    init {
        reload()
    }

    private fun reload() = viewModelScope.launch(writer) { _notes.value = store.notes() }

    fun note(id: String): Note? = _notes.value?.firstOrNull { it.id == id }

    suspend fun create(paper: Paper, canvas: Boolean): Note = withContext(writer) {
        store.create(paper, canvas).also { n -> _notes.update { listOf(n) + it.orEmpty() } }
    }

    fun updateText(id: String, title: String, body: String) {
        val now = System.currentTimeMillis()
        _notes.update { list -> list?.map { if (it.id == id) it.copy(title = title, body = body, updatedAt = now) else it } }
        pendingText.remove(id)?.cancel()
        pendingText[id] = viewModelScope.launch(writer) {
            delay(300)
            note(id)?.let(store::save)
        }
    }

    fun update(id: String, change: (Note) -> Note) {
        _notes.update { list -> list?.map { if (it.id == id) change(it) else it } }
        viewModelScope.launch(writer) { note(id)?.let(store::save) }
    }

    fun applyInk(id: String, added: List<InkStroke>, removedIds: List<String>) {
        val now = System.currentTimeMillis()
        _notes.update { list -> list?.map { if (it.id == id) it.copy(updatedAt = now) else it } }
        viewModelScope.launch(writer) { store.applyInk(id, added, removedIds) }
    }

    /** Page strokes as renderable entries; mesh building runs off the main thread. */
    suspend fun loadInk(id: String, dark: Boolean): List<InkEntry> {
        val data = withContext(writer) { store.strokes(id) }
        return withContext(Dispatchers.Default) { data.map { InkEntry(it, Brushes.build(it, dark)) } }
    }

    /** Ink for a home card thumbnail, cached until the note changes. */
    suspend fun preview(note: Note, dark: Boolean): List<Stroke> {
        val key = note.id + dark
        previews[key]?.takeIf { it.first == note.updatedAt }?.let { return it.second }
        return loadInk(note.id, dark).map { it.stroke }.also { previews[key] = note.updatedAt to it }
    }

    /** Leaving the editor: write pending text now, and drop the note if nothing was ever put in it. */
    fun close(id: String) {
        pendingText.remove(id)?.cancel()
        viewModelScope.launch(writer) {
            val n = note(id) ?: return@launch
            store.save(n)
            if (n.title.isBlank() && n.body.isBlank() && store.strokes(id).isEmpty()) {
                store.delete(id)
                _notes.update { list -> list?.filterNot { it.id == id } }
            }
        }
    }

    fun deleteNow(id: String) {
        pendingText.remove(id)?.cancel()
        _notes.update { list -> list?.filterNot { it.id == id } }
        viewModelScope.launch(writer) { store.delete(id) }
    }

    /** Deletes a note and returns an undo action that restores it with its ink. */
    suspend fun delete(id: String): suspend () -> Unit {
        val note = note(id) ?: return {}
        val strokes = withContext(writer) { store.strokes(id).also { store.delete(id) } }
        _notes.update { list -> list?.filterNot { it.id == id } }
        return {
            withContext(writer) { store.restore(note, strokes) }
            reload()
        }
    }
}
