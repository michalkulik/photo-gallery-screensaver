package com.michalkulik.photogallery.syno

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Password handling for the Synology WebAPI.
 *
 * Kept free of Android APIs (plain `java.util.Base64`, available since API 26) so the encryption
 * can be unit tested on the JVM: getting this wrong is indistinguishable from a wrong password
 * on the wire, which makes it very hard to diagnose from the device.
 */
object SynoCrypto {

    /**
     * Wraps [password] with the NAS public key.
     *
     * DSM only needs this on plain HTTP, where nothing else protects the password. The key it
     * publishes through `SYNO.API.Encryption` is a base64 SPKI document, and the padding is
     * PKCS#1 v1.5.
     */
    fun encryptPassword(publicKeyBase64: String, password: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, parsePublicKey(publicKeyBase64))
        val cipherText = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipherText)
    }

    /** Parses the base64 SPKI key published by `SYNO.API.Encryption`. */
    fun parsePublicKey(publicKeyBase64: String): PublicKey {
        val der = Base64.getDecoder().decode(publicKeyBase64.trim())
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }
}
