package com.michalkulik.photogallery.dream

/**
 * A separable box blur, run three times to approximate a Gaussian.
 *
 * Written by hand rather than using a platform blur because the smallest blur available in the
 * framework, `RenderEffect`, needs API 31 and the target televisions run Android 11.
 *
 * The pixel arithmetic works on plain `IntArray`s so it can be unit tested on the JVM: a blur
 * that is subtly wrong is hard to spot on screen, and this code decides how the backdrop behind
 * every fitted photo looks.
 */
object Blur {

    /** ARGB channels packed the same way Android packs them. */
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /**
     * Blurs [pixels] in place and returns them.
     *
     * [radius] is in pixels of the image being blurred, so a smaller image needs a smaller
     * radius for the same visual softness.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        passes: Int = DEFAULT_PASSES,
    ): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return pixels
        val effective = radius.coerceIn(0, MAX_RADIUS)
        if (effective == 0) return pixels

        var current = pixels.copyOf()
        var scratch = IntArray(current.size)
        repeat(passes.coerceAtLeast(1)) {
            blurRows(current, scratch, width, height, effective)
            blurColumns(scratch, current, width, height, effective)
        }
        return current
    }

    /**
     * Blurs along each row.
     *
     * A sliding window keeps this O(width) per row instead of O(width * radius): the sum is
     * updated by subtracting the pixel leaving the window and adding the one entering it.
     *
     * The division remainder is carried into the next pixel. Integer division rounds down, so
     * without that every pass would darken the result a little and, after three passes, would
     * flatten the darkest parts of a photo to black.
     */
    private fun blurRows(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val last = width - 1
        for (y in 0 until height) {
            val row = y * width
            // Prime the window with the left edge repeated, so the border does not darken.
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val colour = src[row + offset.coerceIn(0, last)]
                alpha += colour ushr ALPHA_SHIFT and 0xFF
                red += colour shr RED_SHIFT and 0xFF
                green += colour shr GREEN_SHIFT and 0xFF
                blue += colour and 0xFF
            }
            var carryAlpha = 0
            var carryRed = 0
            var carryGreen = 0
            var carryBlue = 0
            for (x in 0 until width) {
                val totalAlpha = alpha + carryAlpha
                val totalRed = red + carryRed
                val totalGreen = green + carryGreen
                val totalBlue = blue + carryBlue
                val outAlpha = totalAlpha / window
                val outRed = totalRed / window
                val outGreen = totalGreen / window
                val outBlue = totalBlue / window
                carryAlpha = totalAlpha - outAlpha * window
                carryRed = totalRed - outRed * window
                carryGreen = totalGreen - outGreen * window
                carryBlue = totalBlue - outBlue * window
                dst[row + x] = (outAlpha shl ALPHA_SHIFT) or
                    (outRed shl RED_SHIFT) or
                    (outGreen shl GREEN_SHIFT) or
                    outBlue

                val leaving = src[row + (x - radius).coerceIn(0, last)]
                val entering = src[row + (x + radius + 1).coerceIn(0, last)]
                alpha += (entering ushr ALPHA_SHIFT and 0xFF) - (leaving ushr ALPHA_SHIFT and 0xFF)
                red += (entering shr RED_SHIFT and 0xFF) - (leaving shr RED_SHIFT and 0xFF)
                green += (entering shr GREEN_SHIFT and 0xFF) - (leaving shr GREEN_SHIFT and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** The same along each column. */
    private fun blurColumns(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        val last = height - 1
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val colour = src[offset.coerceIn(0, last) * width + x]
                alpha += colour ushr ALPHA_SHIFT and 0xFF
                red += colour shr RED_SHIFT and 0xFF
                green += colour shr GREEN_SHIFT and 0xFF
                blue += colour and 0xFF
            }
            var carryAlpha = 0
            var carryRed = 0
            var carryGreen = 0
            var carryBlue = 0
            for (y in 0 until height) {
                val totalAlpha = alpha + carryAlpha
                val totalRed = red + carryRed
                val totalGreen = green + carryGreen
                val totalBlue = blue + carryBlue
                val outAlpha = totalAlpha / window
                val outRed = totalRed / window
                val outGreen = totalGreen / window
                val outBlue = totalBlue / window
                carryAlpha = totalAlpha - outAlpha * window
                carryRed = totalRed - outRed * window
                carryGreen = totalGreen - outGreen * window
                carryBlue = totalBlue - outBlue * window
                dst[y * width + x] = (outAlpha shl ALPHA_SHIFT) or
                    (outRed shl RED_SHIFT) or
                    (outGreen shl GREEN_SHIFT) or
                    outBlue

                val leaving = src[(y - radius).coerceIn(0, last) * width + x]
                val entering = src[(y + radius + 1).coerceIn(0, last) * width + x]
                alpha += (entering ushr ALPHA_SHIFT and 0xFF) - (leaving ushr ALPHA_SHIFT and 0xFF)
                red += (entering shr RED_SHIFT and 0xFF) - (leaving shr RED_SHIFT and 0xFF)
                green += (entering shr GREEN_SHIFT and 0xFF) - (leaving shr GREEN_SHIFT and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Three passes of a box blur are a good approximation of a Gaussian. */
    const val DEFAULT_PASSES = 3

    /** Enough for any backdrop; beyond this the blur becomes a flat wash. */
    const val MAX_RADIUS = 100
}
