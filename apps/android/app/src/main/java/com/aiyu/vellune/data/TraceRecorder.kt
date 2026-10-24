package com.aiyu.vellune.data

import android.os.Build
import com.aiyu.vellune.core.input.InputEvent
import com.aiyu.vellune.core.input.TraceCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class TraceFile(val file: File, val sizeBytes: Long, val modified: Long)

/**
 * Writes the flattened input stream of a canvas session to `<dir>/trace-<time>.jsonl`, in the
 * format the JVM tests replay. Writes run on one background thread to keep the input path free.
 */
class TraceRecorder(val dir: File) {
    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null
    private var t0 = -1L
    private val _files = MutableStateFlow(list())
    val files: StateFlow<List<TraceFile>> = _files.asStateFlow()

    val isRecording: Boolean get() = writer != null

    fun start(header: String) {
        if (writer != null) return
        dir.mkdirs()
        val name = "trace-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        val w = File(dir, name).bufferedWriter()
        writer = w
        t0 = -1L
        io.execute { w.write("# $header device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}\n") }
    }

    fun record(event: InputEvent) {
        val w = writer ?: return
        if (t0 < 0) t0 = event.sample.tMs
        val line = TraceCodec.encodeLine(event.copy(sample = event.sample.copy(tMs = event.sample.tMs - t0)))
        io.execute {
            w.write(line)
            w.newLine()
        }
    }

    fun stop() {
        val w = writer ?: return
        writer = null
        io.execute {
            w.close()
            _files.value = list()
        }
    }

    fun delete(file: TraceFile) {
        io.execute {
            file.file.delete()
            _files.value = list()
        }
    }

    private fun list(): List<TraceFile> = dir.listFiles { f -> f.extension == "jsonl" }.orEmpty()
        .map { TraceFile(it, it.length(), it.lastModified()) }
        .sortedByDescending { it.modified }
}
