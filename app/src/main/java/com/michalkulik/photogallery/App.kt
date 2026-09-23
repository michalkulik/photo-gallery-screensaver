package com.michalkulik.photogallery

import android.app.Application
import com.michalkulik.photogallery.core.AppGraph

/** Application entry point; keeps a single long-lived [AppGraph]. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
