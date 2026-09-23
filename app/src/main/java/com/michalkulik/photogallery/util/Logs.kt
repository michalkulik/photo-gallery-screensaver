package com.michalkulik.photogallery.util

import android.util.Log

/** Thin logging facade so the log tag stays consistent across the app. */
object Logs {
    const val TAG = "PhotoScreensaver"

    fun d(message: String) = Log.d(TAG, message)

    fun w(message: String, error: Throwable? = null) = Log.w(TAG, message, error)

    fun e(message: String, error: Throwable? = null) = Log.e(TAG, message, error)
}
