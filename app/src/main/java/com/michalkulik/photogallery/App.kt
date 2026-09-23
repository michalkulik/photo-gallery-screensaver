package com.michalkulik.photogallery

import android.app.Application
import com.michalkulik.photogallery.core.AppGraph
import com.michalkulik.photogallery.util.Logs

/** Application entry point; keeps a single long-lived [AppGraph]. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Stated at every start so it is always answerable which build is actually running.
        // Chasing a bug in a build that was never installed wastes a lot of time.
        Logs.d(
            "Photo Gallery Screensaver ${BuildConfig.VERSION_NAME} " +
                "(versionCode ${BuildConfig.VERSION_CODE}) started",
        )
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
