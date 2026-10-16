package app.vellune.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

enum class Connection { Off, Connecting, Online, Offline, Denied }

data class PresenceUser(val id: String, val name: String)

data class SyncState(
    val connection: Connection = Connection.Off,
    val pending: Int = 0,
    val roles: Map<String, String> = emptyMap(),
    val presence: Map<String, List<PresenceUser>> = emptyMap(),
)

/** Hooks into the workspace, which owns the page list. */
interface SyncBridge {
    fun pages(): Map<String, Page>
    fun applyRemote(page: Page)
    fun removeLocal(id: String)
}

/**
 * Local-first sync, protocol-identical to the web engine (packages/ui/src/cloud/sync.ts): every page
 * is a Yjs doc saved on the device; local edits are coalesced, sealed with the page key, queued
 * durably and pushed over `/api/v1/sync`; remote updates are opened and merged. Editing never waits
 * on the network. All engine state is touched only on [engine], one thread at a time.
 */
class SyncEngine(private val api: Api, private val db: LocalDb) {
    private val engine = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engine)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private var bridge: SyncBridge? = null
    private val docs = HashMap<String, YDoc>()
    private val recs = HashMap<String, LocalDb.DocRec>()
    private val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val pending = LinkedHashMap<String, LocalDb.PendingOp>()
    private val inflight = HashSet<String>()
    private val subscribed = HashSet<String>()
    private val localBuf = HashMap<String, MutableList<ByteArray>>()
    private val timers = HashMap<String, Job>()
    private var identity: Crypto.Identity? = null
    private var ws: WebSocket? = null
    private var retry = 0
    private var reconnect: Job? = null

    private fun set(f: (SyncState) -> SyncState) = _state.update(f)

    /** Loads every saved doc; returns the pages they describe. */
    suspend fun init(bridge: SyncBridge, favorites: Set<String>): List<Page> = withContext(engine) {
        this@SyncEngine.bridge = bridge
        db.docs().forEach { recs[it.id] = it }
        db.pending().forEach { pending[it.opId] = it }
        val pages = recs.keys.mapNotNull { id -> doc(id).toPage(id in favorites) }
        set { it.copy(pending = pending.size, roles = recs.values.filter { r -> r.role != null }.associate { r -> r.id to r.role!! }) }
        pages
    }

    private fun doc(id: String): YDoc = docs.getOrPut(id) {
        YDoc().also { d ->
            val rec = recs[id]
            if (rec != null && rec.state.isNotEmpty()) d.applyUpdate(rec.state)
            if (rec == null) recs[id] = LocalDb.DocRec(id, ByteArray(0))
        }
    }

    private fun debounce(key: String, ms: Long, fn: suspend () -> Unit) {
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(ms)
            timers.remove(key)
            fn()
        }
    }

    /** Called by the workspace after every local mutation of a page. */
    fun localChange(page: Page) = scope.launch {
        val update = doc(page.id).applyPage(page)
        if (update.isEmpty()) return@launch
        debounce("persist:${page.id}", PERSIST_MS) { persist(page.id) }
        val rec = recs[page.id] ?: return@launch
        if (rec.role == "viewer") return@launch
        if (identity != null && !rec.registered && ws != null && bridge?.pages()?.get(page.id) != null) {
            // A page made while online registers now; its full state becomes the first update.
            registerNow(page.id)
            return@launch
        }
        if (identity == null || !rec.registered) {
            // Unregistered pages upload full state on registration; registered ones catch up on next connect.
            if (rec.registered && !rec.unsynced) {
                rec.unsynced = true
                persist(page.id)
            }
            return@launch
        }
        localBuf.getOrPut(page.id) { mutableListOf() } += update
        debounce("push:${page.id}", COALESCE_MS) { flushLocal(page.id) }
    }

    private fun flushLocal(id: String) {
        val buf = localBuf.remove(id) ?: return
        val merged = YDoc.merge(buf)
        if (merged != null) enqueue(id, merged) else buf.forEach { enqueue(id, it) }
    }

    private fun enqueue(pageId: String, update: ByteArray) {
        val op = LocalDb.PendingOp(UUID.randomUUID().toString(), pageId, update)
        pending[op.opId] = op
        db.putPending(op)
        set { it.copy(pending = pending.size) }
        send(op)
    }

    /** Save every pending doc now (app going to background). */
    fun flush() = scope.launch {
        timers.keys.filter { it.startsWith("persist:") }.forEach { k ->
            timers.remove(k)?.cancel()
            persist(k.removePrefix("persist:"))
        }
        localBuf.keys.toList().forEach(::flushLocal)
    }

    private fun persist(id: String) {
        val d = docs[id] ?: return
        val rec = recs[id] ?: return
        rec.state = d.encodeState()
        db.putDoc(rec)
    }

    // ---------- connection ----------

    fun start(id: Crypto.Identity) = scope.launch {
        identity = id
        connect()
    }

    fun stop() = scope.launch {
        identity = null
        reconnect?.cancel()
        ws?.close(1000, null)
        ws = null
        keys.clear(); subscribed.clear(); inflight.clear()
        set { it.copy(connection = Connection.Off, presence = emptyMap()) }
    }

    /** Forget everything synced on this device (logout). */
    fun reset() = scope.launch {
        identity = null
        ws?.close(1000, null)
        ws = null
        docs.values.forEach(YDoc::close)
        docs.clear(); recs.clear(); pending.clear(); keys.clear(); subscribed.clear(); inflight.clear()
        set { SyncState() }
    }

    private fun connect() {
        if (identity == null || ws != null) return
        set { it.copy(connection = Connection.Connecting) }
        ws = api.openSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (webSocket != ws) return@launch
                    retry = 0
                    set { it.copy(connection = Connection.Online) }
                    runCatching { refreshPages() }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (webSocket == ws) runCatching { onMessage(JSONObject(text)) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket)
        })
    }

    private fun closed(socket: WebSocket) {
        scope.launch {
            if (socket != ws) return@launch
            ws = null
            subscribed.clear(); inflight.clear()
            set { it.copy(presence = emptyMap()) }
            if (identity == null || _state.value.connection == Connection.Denied) return@launch
            set { it.copy(connection = Connection.Offline) }
            // Exponential backoff with jitter so a server restart is not met by a reconnect storm.
            val wait = min(30_000.0, 500.0 * (1 shl min(retry++, 10))) * (0.5 + Random.nextDouble())
            reconnect = scope.launch {
                delay(wait.toLong())
                connect()
            }
        }
    }

    /** Wakes a sleeping reconnect, e.g. when the network comes back. */
    fun nudge() = scope.launch {
        if (identity != null && ws == null) {
            reconnect?.cancel()
            retry = 0
            connect()
        }
    }

    private fun wsSend(m: JSONObject): Boolean = ws?.send(m.toString()) ?: false

    /** Sync the page list: unwrap keys, register local-only pages, adopt pages shared with us. */
    suspend fun refreshPages() {
        val me = identity ?: return
        val b = bridge ?: return
        val rows = api.get("/pages") as JSONArray
        val seen = HashSet<String>()
        val roles = HashMap<String, String>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val id = r.getString("id")
            seen += id
            val key = runCatching { Crypto.unwrapPageKey(r.getString("wrapped_key"), me) }.getOrNull() ?: continue
            keys[id] = key
            roles[id] = r.getString("role")
            doc(id)
            recs.getValue(id).apply { registered = true; role = r.getString("role") }
            persist(id)
        }
        for (rec in recs.values.toList()) {
            if (rec.id in seen || !rec.registered) continue
            if (rec.role == "owner") {
                // Server lost a page we own: re-upload instead of deleting local data.
                rec.registered = false
                rec.cursor = 0
            } else {
                // Access to a shared page was revoked.
                forget(rec.id)
                withContext(Dispatchers.Main) { b.removeLocal(rec.id) }
            }
        }
        for (rec in recs.values.toList()) {
            if (!rec.registered && b.pages()[rec.id] != null) {
                runCatching { register(rec.id) }.onSuccess { roles[rec.id] = "owner" }
            }
        }
        set { it.copy(roles = roles) }
        // Edits made offline: one full-state update merges idempotently on every peer.
        for (rec in recs.values) {
            if (rec.unsynced && keys.containsKey(rec.id) && rec.role != "viewer") {
                rec.unsynced = false
                persist(rec.id)
                enqueue(rec.id, doc(rec.id).encodeState())
            }
        }
        keys.keys.toList().forEach(::subscribePage)
    }

    private val registering = HashSet<String>()

    private fun registerNow(id: String) {
        if (!registering.add(id)) return
        scope.launch {
            runCatching { register(id) }.onSuccess {
                set { it.copy(roles = it.roles + (id to "owner")) }
                subscribePage(id)
            }
            registering -= id
        }
    }

    private suspend fun register(id: String) {
        val me = identity ?: return
        val key = Crypto.newPageKey()
        api.post("/pages", JSONObject().put("id", id).put("wrapped_key", Crypto.wrapPageKey(key, me.publicJwk)))
        keys[id] = key
        recs.getValue(id).apply { registered = true; role = "owner" }
        persist(id)
        // Full state becomes the first update; anything queued before registration is included.
        pending.values.filter { it.pageId == id }.map { it.opId }.forEach(::dropPending)
        enqueue(id, doc(id).encodeState())
    }

    private fun forget(id: String) {
        docs.remove(id)?.close()
        recs.remove(id)
        keys.remove(id)
        db.deleteDoc(id)
        pending.values.filter { it.pageId == id }.map { it.opId }.forEach(::dropPending)
    }

    private fun subscribePage(id: String) {
        if (id in subscribed) return
        if (!wsSend(JSONObject().put("type", "subscribe").put("page_id", id).put("after", recs[id]?.cursor ?: 0))) return
        subscribed += id
        pending.values.filter { it.pageId == id }.forEach(::send)
    }

    private fun send(op: LocalDb.PendingOp) {
        val key = keys[op.pageId] ?: return
        if (op.pageId !in subscribed || op.opId in inflight) return
        inflight += op.opId
        val payload = Crypto.b64(Crypto.seal(key, op.update))
        val m = JSONObject().put("type", "push").put("page_id", op.pageId).put("op_id", op.opId).put("payload", payload)
        if (!wsSend(m)) inflight -= op.opId
    }

    private fun dropPending(opId: String) {
        pending.remove(opId)
        inflight -= opId
        db.deletePending(opId)
        set { it.copy(pending = pending.size) }
    }

    private suspend fun onMessage(m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> if (!m.optBoolean("authenticated")) {
                set { it.copy(connection = Connection.Denied) }
                ws?.close(1000, null)
            }
            "updates" -> {
                val id = m.getString("page_id")
                val key = keys[id] ?: return
                val d = docs[id] ?: return
                val rec = recs[id] ?: return
                val updates = m.getJSONArray("updates")
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    // A payload we can't open is skipped, never applied half-way.
                    val plain = runCatching { Crypto.open(key, Crypto.unb64(u.getString("payload"))) }.getOrNull() ?: continue
                    d.applyUpdate(plain)
                }
                rec.cursor = maxOf(rec.cursor, m.optLong("cursor"))
                persist(id)
                val fav = bridge?.pages()?.get(id)?.favorite ?: false
                d.toPage(fav)?.let { page -> withContext(Dispatchers.Main) { bridge?.applyRemote(page) } }
            }
            "ack" -> dropPending(m.getString("op_id"))
            "presence" -> {
                val users = m.optJSONArray("users") ?: JSONArray()
                val list = List(users.length()) { users.getJSONObject(it).let { u -> PresenceUser(u.getString("id"), u.optString("name")) } }
                set { it.copy(presence = it.presence + (m.getString("page_id") to list)) }
            }
            "error" -> {
                val op = m.optString("op_id").takeIf { it.isNotEmpty() && it != "null" }
                if (op != null && m.optString("code") == "invalid") dropPending(op) // can never succeed
                else if (op != null) inflight -= op // retried on the next reconnect
                if (m.optString("code") == "forbidden" && op == null) subscribed -= m.optString("page_id")
            }
        }
    }

    /** Owner deleted the page for everyone. */
    fun deleteRemote(id: String) = scope.launch {
        val rec = recs[id]
        if (identity != null && rec?.registered == true && rec.role == "owner") runCatching { api.delete("/pages/$id") }
        forget(id)
    }

    fun pageKey(id: String): ByteArray? = keys[id]

    companion object {
        private const val COALESCE_MS = 250L
        private const val PERSIST_MS = 400L
    }
}
