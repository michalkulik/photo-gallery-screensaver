package com.michalkulik.photogallery.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import com.michalkulik.photogallery.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Base class for every TV screen: builds the scrollable shell, exposes [rebuild] so list screens
 * refresh in `onResume`, and focuses the first row for D-pad users.
 */
abstract class TvActivity : Activity() {

    protected lateinit var screen: TvUi.Screen

    protected val graph get() = App.graph

    /**
     * For work that outlives a single click, such as a network request.
     *
     * Cancelled with the activity, so a reply that arrives after the screen is gone is dropped
     * rather than touching views that no longer exist.
     */
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected abstract val screenTitle: String

    protected abstract fun buildContent(container: LinearLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = TvUi.screen(this, screenTitle)
        setContentView(screen.root)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Clears and rebuilds the content area. */
    protected fun rebuild() {
        screen.content.removeAllViews()
        buildContent(screen.content)
        TvUi.focusFirst(screen.content)
    }
}
