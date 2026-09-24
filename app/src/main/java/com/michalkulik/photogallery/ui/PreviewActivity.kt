package com.michalkulik.photogallery.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.michalkulik.photogallery.App
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.dream.SlideshowView
import com.michalkulik.photogallery.dream.showEmptyState
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays the screensaver full screen inside the app so settings can be checked without waiting. */
class PreviewActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = App.graph
        val slideshow = SlideshowView(this)
        val hint = TextView(this).apply {
            text = getString(R.string.preview_hint)
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            alpha = 0.9f
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(slideshow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = TvUi.dp(this@PreviewActivity, 28)
                },
            )
        }
        setContentView(root)

        slideshow.setWeatherSource(graph.weather)
        slideshow.applySettings(graph.settings.slideshowSettings())
        scope.launch {
            val photos = withContext(Dispatchers.IO) {
                runCatching { graph.repository.activePhotos() }
                    .onFailure { Logs.e("Cannot load photos for the preview", it) }
                    .getOrDefault(emptyList())
            }
            Logs.d("Preview loaded ${photos.size} photos for source ${graph.repository.activeSource()?.id}")
            if (photos.isEmpty()) {
                slideshow.showEmptyState(this@PreviewActivity)
            } else {
                slideshow.setPhotos(photos, graph.settings.order)
                slideshow.start()
            }
        }
        hint.postDelayed({ hint.animate().alpha(0f).setDuration(1500L).start() }, HINT_VISIBLE_MS)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val HINT_VISIBLE_MS = 6000L
    }
}
