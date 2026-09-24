package com.michalkulik.photogallery.dream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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

    private val backdrop = ImageView(context)
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

    /** A decoded photo together with the blurred copy used for the backdrop. */
    private class Frame(val photo: Bitmap, val backdrop: Bitmap?)

    /**
     * The animation currently driving the visible photo.
     *
     * Tracked explicitly because `view.animate()` hands out one animator per view: starting a
     * second animation on the same view silently cancels the first, which is what broke every
     * other transition.
     */
    private var activeAnimator: Animator? = null

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
        // Fills the screen behind a fitted photo, so a portrait picture is not flanked by two
        // black bars. It holds a tiny blurred copy that the GPU scales up.
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.alpha = BACKDROP_ALPHA
        addView(
            backdrop,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
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
        recycleBackdrop()
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
            var staged: Frame? = null
            var firstShown = false
            var failed = 0
            try {
                while (isActive) {
                    val photo = playlist[index % playlist.size]
                    val frame = staged ?: decode(photo)
                    staged = null
                    if (frame == null) {
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
                    show(frame, animate = firstShown)
                    firstShown = true

                    val nextIndex = index + 1
                    val prefetch = async(Dispatchers.IO) {
                        loadFrame(playlist[nextIndex % playlist.size])
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
        val running = activeAnimator
        activeAnimator = null
        running?.removeAllListeners()
        running?.cancel()
        layerA.animate().cancel()
        layerB.animate().cancel()
        recycleLayer(layerA)
        recycleLayer(layerB)
        recycleBackdrop()
        front = layerA
        back = layerB
        layerA.visibility = View.INVISIBLE
        layerB.visibility = View.INVISIBLE
    }

    /** Frees the blurred backdrop bitmap. */
    private fun recycleBackdrop() {
        val bitmap = backdrop.getTag(R.id.slideshow_backdrop_tag) as? Bitmap
        backdrop.setTag(R.id.slideshow_backdrop_tag, null)
        backdrop.setImageDrawable(null)
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    /** Releases everything; call from `onDetachedFromWindow`. */
    fun release() {
        stop()
        scope.cancel()
    }

    private suspend fun decode(photo: Photo): Frame? = withContext(Dispatchers.IO) { loadFrame(photo) }

    /**
     * Decodes a photo and its backdrop.
     *
     * The backdrop is built here rather than while showing, because scaling a full-size bitmap
     * down to a few dozen pixels would otherwise drop a frame during the transition.
     */
    private fun loadFrame(photo: Photo): Frame? {
        val bitmap = BitmapLoader.load(context, photo, targetWidth(), targetHeight()) ?: return null
        return Frame(bitmap, BitmapLoader.backdrop(bitmap, targetWidth()))
    }

    private fun targetWidth(): Int = if (width > 0) width else FALLBACK_WIDTH

    private fun targetHeight(): Int = if (height > 0) height else FALLBACK_HEIGHT

    private fun show(frame: Frame, animate: Boolean) {
        val bitmap = frame.photo
        // Stop whatever is still running before touching the layers. `view.animate()` returns a
        // single animator per view, so starting a second animation on the same view cancels the
        // first - which is exactly what used to break every other transition.
        val previous = activeAnimator
        activeAnimator = null
        previous?.removeAllListeners()
        previous?.cancel()

        val incoming = back
        val outgoing = front
        Logs.d("Showing ${bitmap.width}x${bitmap.height} (animate=$animate)")

        incoming.animate().cancel()
        incoming.setTag(R.id.slideshow_bitmap_tag, bitmap)
        incoming.setImageBitmap(bitmap)
        incoming.alpha = if (animate) 0f else 1f
        incoming.translationX = if (animate && settings.transition == Transition.SLIDE) width.toFloat() else 0f
        incoming.visibility = View.VISIBLE
        // Reset the transform the previous use of this layer left behind. A stale scale is what
        // made photos look zoomed the moment they appeared.
        incoming.scaleX = 1f
        incoming.scaleY = 1f

        // The backdrop changes with the photo, and is swapped immediately rather than faded:
        // it is the same picture, so a fade would only smear the edges of the real one.
        val previousBackdrop = backdrop.getTag(R.id.slideshow_backdrop_tag) as? Bitmap
        backdrop.setTag(R.id.slideshow_backdrop_tag, frame.backdrop)
        backdrop.setImageBitmap(frame.backdrop)
        if (previousBackdrop != null && previousBackdrop !== frame.backdrop &&
            !previousBackdrop.isRecycled
        ) {
            previousBackdrop.recycle()
        }

        // The incoming layer must be drawn above the outgoing one. Child order is fixed, so
        // without this the old photo hides the new one until it is recycled: the new photo's
        // animation runs unseen and it only appears, already zoomed, once the old one is cleared.
        incoming.bringToFront()
        // The overlays belong above the photos, so raising a layer has to be followed by raising
        // them again.
        dimView.bringToFront()
        clockView.bringToFront()
        messageView.bringToFront()

        // The incoming layer becomes the front immediately; the outgoing one is cleared once
        // the transition is over so it never gets recycled mid-animation.
        front = incoming
        back = outgoing

        if (!animate) {
            recycleLayer(outgoing)
            startKenBurns(incoming)
            return
        }

        val duration = settings.transitionMillis()
        val fade = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(duration)
        val slide = ObjectAnimator
            .ofFloat(incoming, View.TRANSLATION_X, incoming.translationX, 0f)
            .setDuration(duration)

        val set = AnimatorSet()
        set.playTogether(fade, slide)

        if (settings.kenBurns) {
            // Played together with the fade rather than started afterwards: two separate
            // animations on one view would cancel each other, leaving the photo invisible and
            // the previous one still on screen.
            val grow = if (settings.fit == FitMode.COVER) KEN_BURNS_SCALE else KEN_BURNS_SCALE_CONTAIN
            val zoom = ObjectAnimator.ofPropertyValuesHolder(
                incoming,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, grow),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, grow),
            ).setDuration(settings.intervalSeconds * 1000L + duration)
            set.playTogether(zoom)
        }

        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // A cancellation means the next photo is already taking over, so the layers
                // must be left alone.
                if (animation !== activeAnimator) return
                recycleLayer(outgoing)
            }
        })

        activeAnimator = set
        set.start()
    }

    /** Slow zoom that runs while a photo is on screen. */
    private fun startKenBurns(layer: ImageView) {
        if (!settings.kenBurns) return
        val grow = if (settings.fit == FitMode.COVER) KEN_BURNS_SCALE else KEN_BURNS_SCALE_CONTAIN
        val zoom = ObjectAnimator.ofPropertyValuesHolder(
            layer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, grow),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, grow),
        ).setDuration(settings.intervalSeconds * 1000L + settings.transitionMillis())
        activeAnimator = zoom
        zoom.start()
    }

    /**
     * Frees a layer and returns it to a neutral state.
     *
     * Every property is reset, not just the drawable: the layer is reused for a later photo, and
     * a leftover alpha, translation or scale would show up as a photo appearing faded, offset or
     * already zoomed.
     */
    private fun recycleLayer(layer: ImageView) {
        layer.animate().cancel()
        val bitmap = layer.getTag(R.id.slideshow_bitmap_tag) as? Bitmap
        layer.setTag(R.id.slideshow_bitmap_tag, null)
        layer.setImageDrawable(null)
        layer.alpha = 1f
        layer.translationX = 0f
        layer.scaleX = 1f
        layer.scaleY = 1f
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

        /**
         * Zoom for the fitted mode.
         *
         * Much gentler than the fill mode: the photo is shown whole, so a large zoom would crop
         * exactly what fitting it was meant to preserve.
         */
        const val KEN_BURNS_SCALE_CONTAIN = 1.04f

        /** Dims the blurred backdrop so the photo itself stays the subject. */
        const val BACKDROP_ALPHA = 0.45f
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
