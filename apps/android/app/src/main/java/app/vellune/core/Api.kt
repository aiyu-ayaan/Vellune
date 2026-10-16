package app.vellune.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, val code: String, message: String) : Exception(message)

/**
 * JSON client for `<server>/api/v1`: the session cookie the server set at login is sent as a
 * header, and writes carry the `X-Vellune` CSRF header, exactly like the web client.
 */
class Api(private val db: LocalDb) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    /** e.g. `https://notes.example.com`; no trailing slash. */
    var server: String
        get() = db.kv("server") ?: ""
        set(v) = db.setKv("server", v.trim().trimEnd('/'))

    private var session: String?
        get() = db.kv("session_cookie")
        set(v) = db.setKv("session_cookie", v)

    val signedIn get() = session != null

    fun forgetSession() {
        session = null
    }

    suspend fun get(path: String): Any? = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject? = null): Any? = call("POST", path, body)
    suspend fun put(path: String, body: JSONObject): Any? = call("PUT", path, body)
    suspend fun delete(path: String): Any? = call("DELETE", path, null)

    private suspend fun call(method: String, path: String, body: JSONObject?): Any? = withContext(Dispatchers.IO) {
        if (server.isBlank()) throw ApiError(0, "no_server", "Enter your Vellune server address.")
        val b = Request.Builder().url("$server/api/v1$path")
        session?.let { b.header("Cookie", "$SESSION_COOKIE=$it") }
        if (method != "GET") {
            b.header("X-Vellune", "1")
            b.method(method, (body?.toString() ?: if (method == "DELETE") null else "").let { it?.toRequestBody(json) })
        }
        val res = try {
            http.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw ApiError(0, "network", "Can't reach the server. Check the address and your connection.")
        } catch (e: IllegalArgumentException) {
            throw ApiError(0, "bad_url", "That server address isn't valid.")
        }
        res.use { r ->
            r.headers("Set-Cookie").firstOrNull { it.startsWith("$SESSION_COOKIE=") }?.let { c ->
                val v = c.substringAfter('=').substringBefore(';')
                session = v.ifEmpty { null }
            }
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
                if (r.code == 401) session = null
                throw ApiError(r.code, err?.optString("code") ?: "error", err?.optString("message") ?: r.message.ifBlank { "Request failed" })
            }
            when {
                r.code == 204 || text.isBlank() -> null
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        }
    }

    /** The sync socket at `/api/v1/sync`, authenticated with the same session cookie. */
    fun openSocket(listener: WebSocketListener): WebSocket {
        val url = server.replaceFirst(Regex("^http"), "ws") + "/api/v1/sync"
        val b = Request.Builder().url(url)
        session?.let { b.header("Cookie", "$SESSION_COOKIE=$it") }
        return http.newWebSocket(b.build(), listener)
    }

    /** Uploads an already-sealed blob for a page; returns its id. */
    suspend fun uploadBlob(pageId: String, sealed: ByteArray): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$server/api/v1/pages/$pageId/blobs")
            .header("X-Vellune", "1")
            .post(sealed.toRequestBody("application/octet-stream".toMediaType()))
        session?.let { b.header("Cookie", "$SESSION_COOKIE=$it") }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, "blob", "Upload failed")
            JSONObject(r.body!!.string()).getString("id")
        }
    }

    /** Downloads a blob (encrypted image) of a page. */
    suspend fun blob(pageId: String, blobId: String): ByteArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$server/api/v1/pages/$pageId/blobs/$blobId")
        session?.let { b.header("Cookie", "$SESSION_COOKIE=$it") }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, "blob", "Image unavailable")
            r.body!!.bytes()
        }
    }

    companion object {
        const val SESSION_COOKIE = "vellune_session"
    }
}
