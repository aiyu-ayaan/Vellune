package app.vellune.core

import org.json.JSONObject

/**
 * One page's Yjs document, held by the Rust core (crates/mobile). Calls are serialized per doc;
 * [close] frees the native memory exactly once.
 */
class YDoc : AutoCloseable {
    private var handle = nativeNew()

    @Synchronized
    fun applyUpdate(update: ByteArray): Boolean = nativeApplyUpdate(ptr(), update)

    @Synchronized
    fun encodeState(): ByteArray = nativeEncodeState(ptr())

    /** Diffs [page] into the document; returns the update to sync (empty when nothing changed). */
    @Synchronized
    fun applyPage(page: Page): ByteArray = nativeApplyPage(ptr(), page.toJson().toString()) ?: ByteArray(0)

    /** The page this document describes; null until its metadata arrived. */
    @Synchronized
    fun toPage(favorite: Boolean = false): Page? =
        nativeToPage(ptr())?.let { runCatching { pageFromJson(JSONObject(it), favorite) }.getOrNull() }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    private fun ptr(): Long = handle.also { check(it != 0L) { "YDoc used after close" } }

    companion object {
        init {
            System.loadLibrary("vellune_mobile")
        }

        /** One update equivalent to all of [updates]; null if any is malformed. */
        fun merge(updates: List<ByteArray>): ByteArray? =
            if (updates.size == 1) updates[0] else nativeMerge(updates.toTypedArray())

        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeApplyUpdate(h: Long, update: ByteArray): Boolean
        @JvmStatic private external fun nativeEncodeState(h: Long): ByteArray
        @JvmStatic private external fun nativeApplyPage(h: Long, json: String): ByteArray?
        @JvmStatic private external fun nativeToPage(h: Long): String?
        @JvmStatic private external fun nativeMerge(updates: Array<ByteArray>): ByteArray?
    }
}
