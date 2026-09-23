package com.michalkulik.photogallery.syno

/**
 * Decisions about the Synology sign-in that are easy to get subtly wrong.
 *
 * Both rules here come from bugs that were invisible on screen: they produced a plain
 * "wrong account or password" even though the credentials were correct, so they are worth
 * pinning down with tests rather than leaving as inline conditionals.
 */
object SynoLoginPlan {

    /**
     * Order in which to try the password forms.
     *
     * DSM compares `passwd` literally when the connection is already HTTPS, so the RSA-wrapped
     * blob is rejected as a wrong password there; the wrapping only exists to protect plain HTTP.
     * The second entry is a fallback for DSM builds that behave the other way round.
     */
    fun passwordForms(secure: Boolean): List<PasswordForm> =
        if (secure) listOf(PasswordForm.PLAIN, PasswordForm.WRAPPED)
        else listOf(PasswordForm.WRAPPED, PasswordForm.PLAIN)

    /** Whether a rejected sign-in is worth retrying with the other password form. */
    fun shouldRetryWithOtherForm(errorCode: Int?): Boolean = errorCode == WRONG_CREDENTIALS

    /**
     * Whether an already established session may be reused.
     *
     * It must be reused even when the caller still holds a one-time password: a code is consumed
     * by the sign-in that uses it, so a second sign-in with the same code is rejected. Treating
     * that rejection as a bad credential is exactly what made a correct password look wrong.
     */
    fun canReuseSession(
        cachedKey: String?,
        requestedKey: String,
        forceLogin: Boolean,
    ): Boolean = !forceLogin && cachedKey != null && cachedKey == requestedKey

    /** Identifies a session; changing any of these invalidates a cached one. */
    fun sessionKey(baseUrl: String, account: String, password: String): String =
        "$baseUrl|$account|${password.hashCode()}"

    enum class PasswordForm { PLAIN, WRAPPED }

    private const val WRONG_CREDENTIALS = 400
}
