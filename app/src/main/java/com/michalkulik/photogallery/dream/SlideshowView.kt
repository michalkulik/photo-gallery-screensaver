package com.michalkulik.photogallery.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.Photo
import com.michalkulik.photogallery.util.Logs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The actual slideshow renderer, shared by the screensaver ([PhotoDreamService]) and the in-app
 * preview screen. It keeps at most two decoded bitmaps alive and pre-decodes the next photo while
 * the current one is on screen, so advancing never shows a black frame.
 */
class SlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val layerA = ImageView(context)
    private val layerB = ImageView(context)
    private val dimView = View(context)
    private val clockView = TextView(context)
    private val messageView = TextView(context)

    private var settings = SlideshowSettings()
    private var playlist: List<Photo> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var slideshowJob: Job? = null

    private var front: ImageView = layerA
    private var back: ImageView = layerB

    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = timeFormat.format(Date())
            clockHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        listOf(layerA, layerB).forEach { layer ->
            layer.scaleType = ImageView.ScaleType.CENTER_CROP
            layer.visibility = View.INVISIBLE
            addView(
                layer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        dimView.setBackgroundColor(Color.BLACK)
        dimView.alpha = 0f
        addView(dimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        clockView.setTextColor(Color.WHITE)
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
        clockView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        addView(
            clockView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(40)
                marginEnd = dp(48)
            },
        )

        messageView.setTextColor(Color.WHITE)
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        messageView.gravity = Gravity.CENTER
        messageView.visibility = View.GONE
        addView(
            messageView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            },
        )
    }

    /** Applies play settings; takes effect on the next photo. */
    fun applySettings(value: SlideshowSettings) {
        settings = value
        dimView.alpha = value.dim
        val scaleType = when (value.fit) {
            FitMode.COVER -> ImageView.ScaleType.CENTER_CROP
            FitMode.CONTAIN -> ImageView.ScaleType.FIT_CENTER
        }
        layerA.scaleType = scaleType
        layerB.scaleType = scaleType
        clockView.visibility = if (value.showClock) View.VISIBLE else View.GONE
        if (value.showClock) startClock() else stopClock()
    }

    /** Replaces the playlist and restarts playback from the beginning. */
    fun setPhotos(photos: List<Photo>, order: PlayOrder) {
        playlist = PhotoOrder.arrange(photos, order, Random(System.nanoTime()))
    }

    /** Shows [message] instead of photos (used for the empty state). */
    fun showMessage(message: String) {
        messageView.text = message
        messageView.visibility = View.VISIBLE
        layerA.visibility = View.INVISIBLE
        layerB.visibility = View.INVISIBLE
    }

    fun start() {
        stop()
        if (playlist.isEmpty()) {
            Logs.w("Slideshow start requested with an empty playlist")
            return
        }
        Logs.d("Slideshow starting: ${playlist.size} photos, interval=${settings.intervalSeconds}s")
        messageView.visibility = View.GONE
        layerA.visibility = View.INVISIBLE
        layerB.visibility = View.INVISIBLE
        if (settings.showClock) startClock()
        slideshowJob = scope.launch {
            var index = 0
            var staged: Bitmap? = null
            var firstShown = false
            var failed = 0
            try {
                while (isActive) {
                    val photo = playlist[index % playlist.size]
                    val bitmap = staged ?: decode(photo)
                    staged = null
                    if (bitmap == null) {
                        Logs.w("Cannot decode ${photo.uri}, skipping")
                        failed++
                        index++
                        if (failed >= playlist.size) {
                            Logs.w("No decodable photo in ${playlist.size} entries")
                            break
                        }
                        continue
                    }
                    failed = 0
                    show(bitmap, animate = firstShown)
                    firstShown = true

                    val nextIndex = index + 1
                    val prefetch = async(Dispatchers.IO) {
                        BitmapLoader.load(context, playlist[nextIndex % playlist.size], targetWidth(), targetHeight())
                    }
                    delay(settings.intervalSeconds * 1000L)
                    staged = withContext(Dispatchers.IO) { prefetch.await() }
                    index = nextIndex
                }
                // Every entry failed to decode; a black screen would look like a freeze.
                if (!firstShown && isActive) {
                    showUnreadableState(context)
                }
            } catch (error: Exception) {
                Logs.e("Slideshow stopped", error)
            }
        }
    }

    fun stop() {
        slideshowJob?.cancel()
        slideshowJob = null
        stopClock()
        layerA.animate().cancel()
        layerB.animate().cancel()
        recycleLayer(layerA)
        recycleLayer(layerB)
        front = layerA
        back = layerB
        layerA.visibility = View.INVISIBLE
        layerB.visibility = View.INVISIBLE
    }

    /** Releases everything; call from `onDetachedFromWindow`. */
    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun decode(photo: Photo): Bitmap? = withContext(Dispatchers.IO) {
        BitmapLoader.load(context, photo, targetWidth(), targetHeight())
    }

    private fun targetWidth(): Int = if (width > 0) width else FALLBACK_WIDTH

    private fun targetHeight(): Int = if (height > 0) height else FALLBACK_HEIGHT

    private fun show(bitmap: Bitmap, animate: Boolean) {
        val incoming = back
        val outgoing = front
        incoming.animate().cancel()
        Logs.d("Showing ${bitmap.width}x${bitmap.height} (animate=$animate)")
        incoming.setTag(R.id.slideshow_bitmap_tag, bitmap)
        incoming.setImageBitmap(bitmap)
        incoming.alpha = if (animate) 0f else 1f
        incoming.translationX = if (animate && settings.transition == Transition.SLIDE) width.toFloat() else 0f
        incoming.visibility = View.VISIBLE
        incoming.scaleX = 1f
        incoming.scaleY = 1f

        // The incoming layer becomes the front immediately; the outgoing one is cleared once
        // the transition is over so it never gets recycled mid-animation.
        front = incoming
        back = outgoing

        if (!animate) {
            recycleLayer(outgoing)
        } else {
            val duration = settings.transitionMillis()
            incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(duration)
                .withEndAction {
                    outgoing.alpha = 0f
                    recycleLayer(outgoing)
                }
                .start()
        }

        if (settings.kenBurns) {
            val grow = if (settings.fit == FitMode.COVER) KEN_BURNS_SCALE else KEN_BURNS_SCALE_CONTAIN
            val motion = settings.intervalSeconds * 1000L + settings.transitionMillis()
            incoming.animate().scaleX(grow).scaleY(grow).setStartDelay(0L).setDuration(motion).start()
        }
    }

    private fun recycleLayer(layer: ImageView) {
        layer.animate().cancel()
        val bitmap = layer.getTag(R.id.slideshow_bitmap_tag) as? Bitmap
        layer.setTag(R.id.slideshow_bitmap_tag, null)
        layer.setImageDrawable(null)
        layer.visibility = View.INVISIBLE
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun startClock() {
        clockHandler.removeCallbacks(clockTick)
        clockView.visibility = View.VISIBLE
        clockHandler.post(clockTick)
    }

    private fun stopClock() {
        clockHandler.removeCallbacks(clockTick)
        clockView.visibility = View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CLOCK_INTERVAL_MS = 20_000L
        const val FALLBACK_WIDTH = 1920
        const val FALLBACK_HEIGHT = 1080
        const val KEN_BURNS_SCALE = 1.08f
        const val KEN_BURNS_SCALE_CONTAIN = 1.12f
    }
}

/** Transition duration; kept next to the settings it belongs to. */
fun SlideshowSettings.transitionMillis(): Long = 1200L

/** Convenience for showing an empty-state message that names the app. */
fun SlideshowView.showEmptyState(context: Context) {
    showMessage(
        context.getString(R.string.dream_empty_title) + "\n" +
            context.getString(R.string.dream_empty_body, context.getString(R.string.app_name)),
    )
}

/** Shown when the source exists but none of its photos could be decoded. */
fun SlideshowView.showUnreadableState(context: Context) {
    showMessage(
        context.getString(R.string.dream_empty_title) + "\n" +
            context.getString(R.string.dream_unreadable, context.getString(R.string.app_name)),
    )
}
