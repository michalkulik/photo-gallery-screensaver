package com.michalkulik.photogallery.dream

import android.service.dreams.DreamService
import com.michalkulik.photogallery.App
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android TV screensaver. Android lists it under Settings ▸ Display & Sound ▸ Screen saver
 * once the app is installed; the system starts it automatically or on demand.
 */
class PhotoDreamService : DreamService() {

    private var slideshow: SlideshowView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Non-interactive: any D-pad/touch input dismisses the screensaver, as users expect.
        isInteractive = false
        isFullscreen = true
        val view = SlideshowView(this)
        slideshow = view
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val view = slideshow ?: return
        val graph = App.graph
        view.setWeatherSource(graph.weather)
        view.setPlaybackHistory(graph.playbackHistory)
        view.applySettings(graph.settings.slideshowSettings())
        scope.launch {
            val photos = withContext(Dispatchers.IO) {
                runCatching { graph.repository.activePhotos() }
                    .onFailure { Logs.e("Cannot load photos for the screensaver", it) }
                    .getOrDefault(emptyList())
            }
            if (photos.isEmpty()) {
                view.showEmptyState(this@PhotoDreamService)
            } else {
                view.setPhotos(photos, graph.settings.order)
                view.start()
            }
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        slideshow?.stop()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        slideshow = null
        super.onDetachedFromWindow()
    }
}
