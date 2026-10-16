package app.vellune.core

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption, byte-compatible with packages/ui/src/cloud/crypto.ts (WebCrypto).
 * The server only ever sees auth_hash, public keys, wrapped keys and ciphertext.
 *
 *   master    = PBKDF2-SHA256(password NFKC, kdf_salt, iterations)
 *   auth_hash = HKDF(master, "vellune-auth"); vault key = HKDF(master, "vellune-vault")
 *   page key  = random AES-256-GCM; wrapped with ephemeral-static ECDH P-256 + HKDF("vellune-page-key")
 */
object Crypto {
    const val KDF_ITERATIONS = 600_000
    private val random = SecureRandom()

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    /** RFC 5869 with an empty salt (= HashLen zeros), as WebCrypto does with `salt: new Uint8Array(0)`. */
    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ikm) }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info.toByteArray())
            update(1)
            doFinal()
        }
    }

    /** iv[12] || AES-GCM ciphertext+tag */
    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
        return iv + c.doFinal(plain)
    }

    /** Throws when the key is wrong or the data was tampered with. */
    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= 28) { "ciphertext too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        }
        return c.doFinal(sealed, 12, sealed.size - 12)
    }

    class Derived(val authHash: String, val vaultKey: ByteArray)

    /** Slow on purpose (~1 s): stretches the password before anything leaves the device. */
    fun derive(password: String, salt: ByteArray, iterations: Int): Derived {
        // KDF params come from the server; a malicious one could otherwise ask for 1 round and brute-force the password.
        require(iterations in KDF_ITERATIONS..10_000_000 && salt.size == 16) {
            "The server sent unsafe key-derivation parameters. Refusing to continue."
        }
        // PBKDF2WithHmacSHA256 takes the password as UTF-16 chars and encodes them as UTF-8, matching WebCrypto.
        val spec = PBEKeySpec(Normalizer.normalize(password, Normalizer.Form.NFKC).toCharArray(), salt, iterations, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Derived(b64(hkdf(master, "vellune-auth")), hkdf(master, "vellune-vault")).also { master.fill(0) }
    }

    // ---------- identity (ECDH P-256) ----------

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    class Identity(val privateKey: PrivateKey, val publicJwk: JSONObject, val pkcs8: ByteArray)

    class KeyBundle(val kdfSalt: String, val kdfIterations: Int, val publicKey: JSONObject, val wrappedPrivateKey: String) {
        fun toJson() = JSONObject().put("kdf_salt", kdfSalt).put("kdf_iterations", kdfIterations)
            .put("public_key", publicKey).put("wrapped_private_key", wrappedPrivateKey)

        companion object {
            fun fromJson(o: JSONObject) = KeyBundle(
                o.getString("kdf_salt"), o.getInt("kdf_iterations"), o.getJSONObject("public_key"), o.getString("wrapped_private_key"),
            )
        }
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun jwkOf(pub: PublicKey): JSONObject {
        val w = (pub as ECPublicKey).w
        return JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", b64url(fixed32(w.affineX))).put("y", b64url(fixed32(w.affineY)))
    }

    private fun publicFromJwk(jwk: JSONObject): PublicKey {
        val point = ECPoint(BigInteger(1, unb64url(jwk.getString("x"))), BigInteger(1, unb64url(jwk.getString("y"))))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256))
    }

    fun privateFromPkcs8(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** New account keys, wrapped under the password. */
    fun createKeys(password: String): Triple<KeyBundle, String, Identity> {
        val salt = randomBytes(16)
        val d = derive(password, salt, KDF_ITERATIONS)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val pkcs8 = pair.private.encoded
        val jwk = jwkOf(pair.public)
        val bundle = KeyBundle(b64(salt), KDF_ITERATIONS, jwk, b64(seal(d.vaultKey, pkcs8)))
        return Triple(bundle, d.authHash, Identity(pair.private, jwk, pkcs8))
    }

    /** Throws when the vault key is wrong (AES-GCM authentication fails). */
    fun unlockKeys(bundle: KeyBundle, vaultKey: ByteArray): Identity {
        val pkcs8 = open(vaultKey, unb64(bundle.wrappedPrivateKey))
        return Identity(privateFromPkcs8(pkcs8), bundle.publicKey, pkcs8)
    }

    // ---------- page keys ----------

    fun newPageKey(): ByteArray = randomBytes(32)

    private fun kek(priv: PrivateKey, pub: JSONObject): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(priv)
            doPhase(publicFromJwk(pub), true)
            generateSecret()
        }
        return hkdf(shared, "vellune-page-key")
    }

    fun wrapPageKey(pageKey: ByteArray, recipient: JSONObject): String {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val ct = seal(kek(eph.private, recipient), pageKey)
        val body = JSONObject().put("epk", jwkOf(eph.public)).put("ct", b64(ct))
        return b64(body.toString().toByteArray())
    }

    fun unwrapPageKey(wrapped: String, me: Identity): ByteArray {
        val o = JSONObject(String(unb64(wrapped)))
        return open(kek(me.privateKey, o.getJSONObject("epk")), unb64(o.getString("ct")))
    }

    /** Short human-checkable fingerprint of a public key. */
    fun fingerprint(pub: JSONObject): String {
        val h = MessageDigest.getInstance("SHA-256").digest("${pub.getString("x")}.${pub.getString("y")}".toByteArray())
        return h.take(8).joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")
    }
}
