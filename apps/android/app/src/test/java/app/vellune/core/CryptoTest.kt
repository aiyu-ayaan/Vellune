package app.vellune.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Opens what the web client (WebCrypto) sealed: fixture from packages/ui/src/cloud/native.test.ts. */
class CryptoTest {
    private val web = JSONObject(javaClass.getResource("/web-crypto.json")!!.readText())
    private val bundle = Crypto.KeyBundle.fromJson(web.getJSONObject("bundle"))

    @Test
    fun derivesTheWebAuthHashAndUnlocksItsKeys() {
        val d = Crypto.derive(web.getString("password"), Crypto.unb64(bundle.kdfSalt), bundle.kdfIterations)
        assertEquals(web.getString("authHash"), d.authHash)
        val me = Crypto.unlockKeys(bundle, d.vaultKey)
        val pageKey = Crypto.unwrapPageKey(web.getString("wrappedPageKey"), me)
        assertArrayEquals(Crypto.unb64url(web.getString("pageKey")), pageKey)
        assertEquals(web.getString("plain"), String(Crypto.open(pageKey, Crypto.unb64(web.getString("sealed")))))
    }

    @Test
    fun wrongPasswordFails() {
        val d = Crypto.derive("not the password", Crypto.unb64(bundle.kdfSalt), bundle.kdfIterations)
        assertThrows(Exception::class.java) { Crypto.unlockKeys(bundle, d.vaultKey) }
    }

    @Test
    fun refusesWeakKdf() {
        assertThrows(IllegalArgumentException::class.java) { Crypto.derive("pw", ByteArray(16), 1) }
    }

    @Test
    fun wrapRoundTripAndKeysAreWebShaped() {
        val (b, _, me) = Crypto.createKeys("alice-password-1")
        val key = Crypto.newPageKey()
        assertArrayEquals(key, Crypto.unwrapPageKey(Crypto.wrapPageKey(key, b.publicKey), me))
        assertEquals("EC", b.publicKey.getString("kty"))
        assertEquals(43, b.publicKey.getString("x").length) // 32 bytes, base64url, no padding
        assert(!b.publicKey.has("d"))
    }
}
