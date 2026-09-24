package com.michalkulik.photogallery.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.ui.TvUi
import com.michalkulik.photogallery.util.Logs

/**
 * The weather symbol shown beside the temperature.
 *
 * Uses the OpenWeather icon set: it has a separate drawing for each condition and a night
 * variant for the three that need one, which is the grouping [WeatherCondition] follows. Its
 * own drawings are clearer than anything assembled here from circles and lines.
 *
 * Drawn with a shadow, like the clock. A cloud in the set is white and would otherwise vanish
 * against a bright photo.
 */
class WeatherIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var condition = WeatherCondition.CLEAR
    private var isDay = true

    /** Pixel width to decode at, known only once the view is measured. */
    private var targetPixels = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        setShadowLayer(10f, 0f, 3f, Color.BLACK)
    }

    private val destination = RectF()

    init {
        // A shadow reaches the canvas only through a software layer below API 28.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun show(value: WeatherCondition, day: Boolean) {
        if (value == condition && day == isDay) return
        condition = value
        isDay = day
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square, so the icon fills its box whichever side is constrained.
        val wanted = TvUi.dp(context, DEFAULT_SIZE_DP)
        val width = resolveSize(wanted, widthMeasureSpec)
        val height = resolveSize(wanted, heightMeasureSpec)
        val side = minOf(width, height)
        // Decoded a little larger than it is drawn, because the shadow needs room inside the box.
        targetPixels = (side * 1.25f).toInt().coerceAtLeast(1)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return

        val bitmap = bitmapFor(condition.iconName(isDay), targetPixels) ?: return
        // Inset a little so the shadow is not clipped by the view bounds.
        val inset = side * 0.07f
        destination.set(inset, inset, side - inset, side - inset)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    /**
     * The drawing for an icon name such as `01d`.
     *
     * Decoded bitmaps are shared and kept for the life of the process. There are twelve of them
     * at about 100 px each, so the whole set costs a few hundred kilobytes, and decoding one
     * again on every photo change would be pointless work.
     */
    private fun bitmapFor(name: String, pixels: Int): Bitmap? {
        val resource = resourceFor(name)
        if (resource == 0 || pixels <= 0) return null
        val key = "$name@$pixels"
        cache.get(key)?.let { return it }

        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching { BitmapFactory.decodeResource(resources, resource, options) }
            .onFailure { Logs.w("Cannot decode the weather icon $name", it) }
            .getOrNull() ?: return null

        // Resized to the size actually drawn: without this the framework would scale the whole
        // 100 px image down on every frame instead of once.
        val scaled = if (decoded.width == pixels) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, pixels, pixels, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
        cache.put(key, scaled)
        return scaled
    }

    /** The drawable for an icon name, or 0 when the set does not cover it. */
    private fun resourceFor(name: String): Int = when (name) {
        "01d" -> R.drawable.ic_weather_01d
        "01n" -> R.drawable.ic_weather_01n
        "02d" -> R.drawable.ic_weather_02d
        "02n" -> R.drawable.ic_weather_02n
        "03d" -> R.drawable.ic_weather_03d
        "04d" -> R.drawable.ic_weather_04d
        "09d" -> R.drawable.ic_weather_09d
        "10d" -> R.drawable.ic_weather_10d
        "10n" -> R.drawable.ic_weather_10n
        "11d" -> R.drawable.ic_weather_11d
        "13d" -> R.drawable.ic_weather_13d
        "50d" -> R.drawable.ic_weather_50d
        else -> 0
    }

    private companion object {
        const val DEFAULT_SIZE_DP = 44

        val cache = LruCache<String, Bitmap>(16)
    }
}
