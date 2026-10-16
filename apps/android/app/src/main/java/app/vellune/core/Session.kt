package app.vellune.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Same states as the web session (packages/ui/src/cloud/session.ts). */
enum class Status { Loading, SignedOut, Local, NeedsKeys, Locked, Ready }

data class User(val id: String, val email: String, val name: String, val isAdmin: Boolean) {
    fun toJson() = JSONObject().put("id", id).put("email", email).put("name", name).put("is_admin", isAdmin)

    companion object {
        fun fromJson(o: JSONObject) = User(o.getString("id"), o.getString("email"), o.optString("name"), o.optBoolean("is_admin"))
    }
}

data class SessionState(
    val status: Status = Status.Loading,
    val user: User? = null,
    val bundle: Crypto.KeyBundle? = null,
    val identity: Crypto.Identity? = null,
    val serverReachable: Boolean = true,
    val error: String? = null,
)

/**
 * Account session and E2E identity. The unlocked private key is stored sealed by an Android
 * Keystore key that never leaves secure hardware, so the app stays signed in across launches
 * without keeping the key in the clear.
 */
class Session(private val api: Api, private val db: LocalDb) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    /** Set by the workspace: wipe in-memory pages when a different account signs in. */
    var onAccountSwitch: (() -> Unit)? = null

    private fun set(f: (SessionState) -> SessionState) = _state.update(f)

    suspend fun init() {
        if (!api.signedIn) {
            set { it.copy(status = if (db.kv(LOCAL_FLAG) == "1") Status.Local else Status.SignedOut) }
            return
        }
        try {
            val me = api.get("/auth/me") as JSONObject
            signedIn(User.fromJson(me.getJSONObject("user")), me.optJSONObject("keys")?.let(Crypto.KeyBundle::fromJson), null)
        } catch (e: ApiError) {
            val stored = storedIdentity()
            val user = db.kv("user")?.let { User.fromJson(JSONObject(it)) }
            if ((e.status == 0 || e.status >= 500) && stored != null) {
                // Offline with an unlocked device: keep working, sync resumes when the server returns.
                set { it.copy(status = Status.Ready, identity = stored, user = user, serverReachable = false) }
            } else {
                set { it.copy(status = if (db.kv(LOCAL_FLAG) == "1") Status.Local else Status.SignedOut, serverReachable = e.status != 0) }
            }
        }
    }

    private fun signedIn(user: User, bundle: Crypto.KeyBundle?, identity: Crypto.Identity?) {
        db.setKv(LOCAL_FLAG, null)
        val prev = db.kv("user")?.let { User.fromJson(JSONObject(it)) }
        if (prev != null && prev.id != user.id) {
            val session = db.kv("session_cookie")
            val server = db.kv("server")
            db.wipeAccountData()
            db.setKv("session_cookie", session)
            db.setKv("server", server)
            onAccountSwitch?.invoke()
        }
        db.setKv("user", user.toJson().toString())
        if (bundle == null) return set { it.copy(status = Status.NeedsKeys, user = user, bundle = null, error = null) }
        val id = identity ?: storedIdentity()?.takeIf { db.kv("identity_user") == user.id }
            ?: return set { it.copy(status = Status.Locked, user = user, bundle = bundle, error = null) }
        storeIdentity(id, user.id)
        set { it.copy(status = Status.Ready, user = user, bundle = bundle, identity = id, error = null, serverReachable = true) }
    }

    private suspend fun guard(block: suspend () -> Unit) {
        set { it.copy(error = null) }
        try {
            block()
        } catch (e: Exception) {
            set { it.copy(error = e.message ?: "Something went wrong") }
        }
    }

    suspend fun signup(server: String, email: String, name: String, password: String) = guard {
        api.server = server
        val (bundle, authHash, identity) = withContext(Dispatchers.Default) { Crypto.createKeys(password) }
        val r = api.post(
            "/auth/signup",
            JSONObject().put("email", email.trim()).put("name", name.trim()).put("auth_hash", authHash).put("keys", bundle.toJson()),
        ) as JSONObject
        signedIn(User.fromJson(r.getJSONObject("user")), Crypto.KeyBundle.fromJson(r.getJSONObject("keys")), identity)
    }

    suspend fun login(server: String, email: String, password: String) = guard {
        api.server = server
        val pre = api.post("/auth/prelogin", JSONObject().put("email", email.trim())) as JSONObject
        val d = withContext(Dispatchers.Default) {
            Crypto.derive(password, Crypto.unb64(pre.getString("kdf_salt")), pre.getInt("kdf_iterations"))
        }
        val r = api.post("/auth/login", JSONObject().put("email", email.trim()).put("auth_hash", d.authHash)) as JSONObject
        val bundle = r.optJSONObject("keys")?.let(Crypto.KeyBundle::fromJson)
        signedIn(User.fromJson(r.getJSONObject("user")), bundle, bundle?.let { Crypto.unlockKeys(it, d.vaultKey) })
    }

    /** Unlock this device with the account password (or encryption passphrase). */
    suspend fun unlock(passphrase: String) = guard {
        val s = _state.value
        val user = s.user ?: error("Not signed in")
        val bundle = s.bundle ?: error("Not signed in")
        val d = withContext(Dispatchers.Default) { Crypto.derive(passphrase, Crypto.unb64(bundle.kdfSalt), bundle.kdfIterations) }
        val id = runCatching { Crypto.unlockKeys(bundle, d.vaultKey) }.getOrElse { error("That passphrase does not unlock this account.") }
        signedIn(user, bundle, id)
    }

    /** First-time encryption setup for accounts created with GitHub/Google on the web. */
    suspend fun setupKeys(passphrase: String) = guard {
        val user = _state.value.user ?: error("Not signed in")
        val (bundle, _, identity) = withContext(Dispatchers.Default) { Crypto.createKeys(passphrase) }
        val r = api.put("/auth/keys", bundle.toJson()) as JSONObject
        signedIn(user, Crypto.KeyBundle.fromJson(r.getJSONObject("keys")), identity)
    }

    fun continueLocal() {
        db.setKv(LOCAL_FLAG, "1")
        set { it.copy(status = Status.Local, error = null) }
    }

    fun showLogin() = set { it.copy(status = Status.SignedOut, error = null) }

    /** Signs out and wipes synced data from this device. */
    suspend fun logout() {
        runCatching { api.post("/auth/logout") }
        api.forgetSession()
        db.wipeAccountData()
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
        set { SessionState(status = Status.SignedOut) }
    }

    // ---------- identity at rest ----------

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun deviceKey(): SecretKey {
        (keystore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun storeIdentity(id: Crypto.Identity, userId: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        val sealed = c.iv + c.doFinal(id.pkcs8)
        db.setKv("identity", JSONObject().put("sealed", Crypto.b64(sealed)).put("public", id.publicJwk).toString())
        db.setKv("identity_user", userId)
    }

    private fun storedIdentity(): Crypto.Identity? = runCatching {
        val o = JSONObject(db.kv("identity") ?: return null)
        val sealed = Crypto.unb64(o.getString("sealed"))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, sealed, 0, 12))
        }
        val pkcs8 = c.doFinal(sealed, 12, sealed.size - 12)
        Crypto.Identity(Crypto.privateFromPkcs8(pkcs8), o.getJSONObject("public"), pkcs8)
    }.getOrNull()

    companion object {
        private const val LOCAL_FLAG = "local_only"
        private const val KEY_ALIAS = "vellune-identity"
    }
}
