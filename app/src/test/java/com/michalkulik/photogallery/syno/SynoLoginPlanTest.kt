package com.michalkulik.photogallery.syno

import com.michalkulik.photogallery.syno.SynoLoginPlan.PasswordForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two sign-in decisions that produced a misleading "wrong account or password"
 * while the credentials were in fact correct.
 */
class SynoLoginPlanTest {

    @Test
    fun `sends the password in the clear over https`() {
        // DSM compares passwd literally when TLS already protects it, so the wrapped form must
        // not be tried first there.
        assertEquals(listOf(PasswordForm.PLAIN, PasswordForm.WRAPPED), SynoLoginPlan.passwordForms(secure = true))
    }

    @Test
    fun `wraps the password when the connection is plain http`() {
        // Without TLS the wrapped form is the only thing protecting the password.
        assertEquals(listOf(PasswordForm.WRAPPED, PasswordForm.PLAIN), SynoLoginPlan.passwordForms(secure = false))
    }

    @Test
    fun `retries the other password form only when the password was rejected`() {
        assertTrue(SynoLoginPlan.shouldRetryWithOtherForm(400))
        // A two-factor challenge, an Auto Block and an expired session must not trigger a retry.
        assertFalse(SynoLoginPlan.shouldRetryWithOtherForm(403))
        assertFalse(SynoLoginPlan.shouldRetryWithOtherForm(407))
        assertFalse(SynoLoginPlan.shouldRetryWithOtherForm(119))
        assertFalse(SynoLoginPlan.shouldRetryWithOtherForm(null))
    }

    @Test
    fun `reuses a session even when a one-time password is still in hand`() {
        // The regression: refusing to reuse the session because a code was supplied made the app
        // sign in twice, and the second attempt with an already-consumed code looked like a bad
        // password.
        val key = SynoLoginPlan.sessionKey("https://nas:5001", "michal", "secret")

        assertTrue(SynoLoginPlan.canReuseSession(key, key, forceLogin = false))
    }

    @Test
    fun `does not reuse a session for a different address, account or password`() {
        val key = SynoLoginPlan.sessionKey("https://nas:5001", "michal", "secret")

        assertFalse(SynoLoginPlan.canReuseSession(null, key, forceLogin = false))
        assertFalse(
            SynoLoginPlan.canReuseSession(
                SynoLoginPlan.sessionKey("https://other:5001", "michal", "secret"),
                key,
                forceLogin = false,
            ),
        )
        assertFalse(
            SynoLoginPlan.canReuseSession(
                SynoLoginPlan.sessionKey("https://nas:5001", "someone", "secret"),
                key,
                forceLogin = false,
            ),
        )
        assertFalse(
            SynoLoginPlan.canReuseSession(
                SynoLoginPlan.sessionKey("https://nas:5001", "michal", "different"),
                key,
                forceLogin = false,
            ),
        )
    }

    @Test
    fun `never reuses a session when a fresh sign-in was forced`() {
        val key = SynoLoginPlan.sessionKey("https://nas:5001", "michal", "secret")

        assertFalse(SynoLoginPlan.canReuseSession(key, key, forceLogin = true))
    }

    @Test
    fun `session key does not contain the password itself`() {
        val key = SynoLoginPlan.sessionKey("https://nas:5001", "michal", "hunter2")

        assertFalse(key.contains("hunter2"))
        assertTrue(key.contains("michal"))
    }
}
