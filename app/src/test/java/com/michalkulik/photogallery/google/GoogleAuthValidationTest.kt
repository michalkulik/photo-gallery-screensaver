package com.michalkulik.photogallery.google

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client-id check only guards against obvious typos, so it must stay deliberately lenient. */
class GoogleAuthValidationTest {

    @Test
    fun `accepts a real looking client id`() {
        assertTrue(looksLikeGoogleClientId("123456789012-abcdefghijklmnop.apps.googleusercontent.com"))
    }

    @Test
    fun `rejects an empty value`() {
        assertFalse(looksLikeGoogleClientId(""))
    }

    @Test
    fun `rejects the bare suffix`() {
        assertFalse(looksLikeGoogleClientId(".apps.googleusercontent.com"))
    }

    @Test
    fun `rejects a client secret pasted by mistake`() {
        assertFalse(looksLikeGoogleClientId("GOCSPX-somethingRandom"))
    }

    @Test
    fun `rejects a client id with trailing whitespace`() {
        // The UI trims before calling, but the check itself must not silently pass.
        assertFalse(looksLikeGoogleClientId("abc.apps.googleusercontent.com "))
    }
}
