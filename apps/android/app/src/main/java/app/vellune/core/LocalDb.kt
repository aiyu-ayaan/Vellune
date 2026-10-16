package app.vellune.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Device storage, the native twin of the web's IndexedDB: one Yjs state per page (`local_docs`),
 * the durable outbox of encrypted-at-send updates (`local_pending_operations`), and small
 * key/value settings. Pages are always read back from their Yjs state, so there is one source
 * of truth on the device.
 */
class LocalDb(context: Context) : SQLiteOpenHelper(context, "vellune-sync.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) = db.enableWriteAheadLogging().let { }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE local_docs (
                id TEXT PRIMARY KEY,
                state BLOB NOT NULL,
                cursor INTEGER NOT NULL DEFAULT 0,
                registered INTEGER NOT NULL DEFAULT 0,
                role TEXT,
                unsynced INTEGER NOT NULL DEFAULT 0)""",
        )
        db.execSQL(
            """CREATE TABLE local_pending_operations (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                op_id TEXT NOT NULL UNIQUE,
                page_id TEXT NOT NULL,
                update_bytes BLOB NOT NULL)""",
        )
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    class DocRec(
        val id: String,
        var state: ByteArray,
        var cursor: Long = 0,
        var registered: Boolean = false,
        var role: String? = null,
        var unsynced: Boolean = false,
    )

    class PendingOp(val opId: String, val pageId: String, val update: ByteArray)

    fun docs(): List<DocRec> = readableDatabase.rawQuery(
        "SELECT id, state, cursor, registered, role, unsynced FROM local_docs", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(DocRec(c.getString(0), c.getBlob(1), c.getLong(2), c.getInt(3) != 0, c.getString(4), c.getInt(5) != 0))
            }
        }
    }

    fun putDoc(r: DocRec) {
        writableDatabase.insertWithOnConflict(
            "local_docs", null,
            ContentValues().apply {
                put("id", r.id); put("state", r.state); put("cursor", r.cursor)
                put("registered", if (r.registered) 1 else 0); put("role", r.role); put("unsynced", if (r.unsynced) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteDoc(id: String) {
        writableDatabase.delete("local_docs", "id = ?", arrayOf(id))
    }

    fun pending(): List<PendingOp> = readableDatabase.rawQuery(
        "SELECT op_id, page_id, update_bytes FROM local_pending_operations ORDER BY seq", null,
    ).use { c -> buildList { while (c.moveToNext()) add(PendingOp(c.getString(0), c.getString(1), c.getBlob(2))) } }

    fun putPending(op: PendingOp) {
        writableDatabase.insertWithOnConflict(
            "local_pending_operations", null,
            ContentValues().apply { put("op_id", op.opId); put("page_id", op.pageId); put("update_bytes", op.update) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun deletePending(opId: String) {
        writableDatabase.delete("local_pending_operations", "op_id = ?", arrayOf(opId))
    }

    fun kv(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setKv(k: String, v: String?) {
        if (v == null) writableDatabase.delete("kv", "k = ?", arrayOf(k))
        else writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Logout or account switch: synced docs, outbox and keys go; the server address stays. */
    fun wipeAccountData() {
        val server = kv("server")
        writableDatabase.run {
            beginTransaction()
            try {
                delete("local_docs", null, null)
                delete("local_pending_operations", null, null)
                delete("kv", null, null)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        setKv("server", server)
    }
}
