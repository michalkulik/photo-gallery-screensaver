package com.michalkulik.photogallery.syno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Proves the password wrapping is cryptographically correct.
 *
 * This matters because a broken encryption step is indistinguishable from a wrong password on
 * the wire: DSM just answers "wrong account or password", which sends you hunting for a
 * credential problem that does not exist.
 */
class SynoCryptoTest {

    /** A 4096-bit key, matching what DiskStations publish. */
    private fun newKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()

    /** The base64 SPKI form the NAS serves from `SYNO.API.Encryption`. */
    private fun publishedKey(pair: java.security.KeyPair): String =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    @Test
    fun `round-trips the password through the NAS public key`() {
        val pair = newKeyPair()
        val publicKey = publishedKey(pair)

        val encrypted = SynoCrypto.encryptPassword(publicKey, "correct horse battery staple")

        // Decrypt the way the NAS does: PKCS#1 v1.5 with the private key.
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val decrypted = String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)

        assertEquals("correct horse battery staple", decrypted)
    }

    @Test
    fun `produces a ciphertext the size of the key`() {
        val pair = newKeyPair()
        val publicKey = publishedKey(pair)

        val encrypted = SynoCrypto.encryptPassword(publicKey, "x")

        // A 4096-bit key always yields exactly 512 bytes, whatever the password length.
        assertEquals(512, Base64.getDecoder().decode(encrypted).size)
    }

    @Test
    fun `does not leak the password in the ciphertext`() {
        val pair = newKeyPair()
        val publicKey = publishedKey(pair)

        val encrypted = SynoCrypto.encryptPassword(publicKey, "hunter2")

        assertNotEquals("hunter2", encrypted)
        assertTrue(!encrypted.contains("hunter2"))
    }

    @Test
    fun `accepts the base64 key exactly as the NAS publishes it`() {
        val pair = newKeyPair()
        val published = publishedKey(pair)

        // The NAS returns the key as one long line, sometimes with trailing whitespace.
        val parsed = SynoCrypto.parsePublicKey("  $published\n")

        assertEquals("RSA", parsed.algorithm)
        assertEquals(4096, (parsed as java.security.interfaces.RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun `uses PKCS1 v1_5 padding, not OAEP`() {
        // DSM expects PKCS#1 v1.5. Guarding this explicitly because swapping in OAEP is a
        // plausible "modernisation" that would silently break every sign-in.
        val pair = newKeyPair()
        val publicKey = publishedKey(pair)
        val encrypted = Base64.getDecoder().decode(SynoCrypto.encryptPassword(publicKey, "pw"))

        // PKCS#1 v1.5 decryption succeeds without any OAEP parameters...
        val pkcs1 = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        pkcs1.init(Cipher.DECRYPT_MODE, pair.private)
        assertEquals("pw", String(pkcs1.doFinal(encrypted), Charsets.UTF_8))

        // ...while an OAEP decryptor cannot read it at all.
        val oaep = Cipher.getInstance("RSA/ECB/OAEPPadding")
        oaep.init(
            Cipher.DECRYPT_MODE,
            pair.private,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val failed = runCatching { oaep.doFinal(encrypted) }.isFailure
        assertTrue("OAEP must not be able to read a PKCS#1 v1.5 ciphertext", failed)
    }

    @Test
    fun `handles passwords with spaces and unicode`() {
        val pair = newKeyPair()
        val publicKey = publishedKey(pair)
        val password = "  zażółć gęślą jaźń  "

        val encrypted = SynoCrypto.encryptPassword(publicKey, password)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)

        assertEquals(password, String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8))
    }
}
