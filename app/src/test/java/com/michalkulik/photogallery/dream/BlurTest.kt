package com.michalkulik.photogallery.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the hand-written blur.
 *
 * Verified on pixels rather than on a screenshot: a blur that is subtly wrong - one that darkens
 * the border, or that shifts the image - still looks like a blur when the result is scaled up,
 * and this code decides how the backdrop behind every fitted photo looks.
 */
class BlurTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(pixel: Int): Int = pixel shr 16 and 0xFF

    @Test
    fun `a flat image is left untouched`() {
        // Averaging equal values must give the same value back, at any radius. Anything else
        // would darken or lighten the whole backdrop.
        val pixels = IntArray(20 * 10) { argb(255, 120, 60, 30) }
        val result = Blur.apply(pixels, width = 20, height = 10, radius = 5)

        assertTrue(result.all { it == argb(255, 120, 60, 30) })
    }

    @Test
    fun `zero radius is a no-op`() {
        val pixels = IntArray(4) { it * 10 }
        val result = Blur.apply(pixels.copyOf(), width = 4, height = 1, radius = 0)

        assertEquals(pixels.toList(), result.toList())
    }

    @Test
    fun `a bright patch spreads outwards`() {
        // A block rather than a single pixel: one pixel of 255 spread over a few hundred output
        // pixels leaves less than one level each, so rounding would have to lose it. A patch of
        // a realistic size is what the backdrop actually contains.
        val width = 41
        val height = 41
        val pixels = IntArray(width * height) { argb(255, 0, 0, 0) }
        for (y in 18..22) {
            for (x in 18..22) {
                pixels[y * width + x] = argb(255, 255, 255, 255)
            }
        }

        val result = Blur.apply(pixels, width, height, radius = 4)

        val centre = red(result[20 * width + 20])
        val near = red(result[20 * width + 26])
        // Three passes of radius 4 reach 12 px past the patch edge, which is at x = 22.
        val far = red(result[20 * width + 38])
        assertTrue("the patch should dim as it spreads, not stay a hard edge", centre in 1..254)
        assertTrue("brightness must reach outside the patch", near > 0)
        assertTrue("and no further than the passes cover", far == 0)
        assertTrue("the blur must fade away rather than stop", near < centre)
    }

    @Test
    fun `the blur does not destroy brightness`() {
        // Integer division in the passes rounds down. Without carrying the remainder, the picture
        // darkens with every pass, and this sum comes out low - which is what a first version of
        // this code did.
        val width = 41
        val height = 41
        val pixels = IntArray(width * height) { argb(255, 0, 0, 0) }
        for (y in 18..22) {
            for (x in 18..22) {
                pixels[y * width + x] = argb(255, 255, 255, 255)
            }
        }
        val before = pixels.sumOf { red(it) }

        val result = Blur.apply(pixels, width, height, radius = 5, passes = 1)

        val after = result.sumOf { red(it) }
        assertEquals("the blur must not create or destroy brightness", before, after)
    }

    @Test
    fun `edges are handled without darkening`() {
        // Clamping rather than treating outside pixels as transparent; otherwise the borders go
        // dark and the backdrop shows a vignette that is not in the photo.
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { argb(255, 200, 100, 50) }

        val result = Blur.apply(pixels, width, height, radius = 3)

        assertEquals(argb(255, 200, 100, 50), result[0])
        assertEquals(argb(255, 200, 100, 50), result[width - 1])
        assertEquals(argb(255, 200, 100, 50), result[(height - 1) * width])
    }

    @Test
    fun `alpha survives the blur`() {
        // Dropping alpha would make the backdrop transparent and reveal the black view behind it.
        val pixels = IntArray(9 * 9) { argb(200, 10, 20, 30) }
        val result = Blur.apply(pixels, width = 9, height = 9, radius = 2)

        assertTrue(result.all { (it ushr 24 and 0xFF) == 200 })
    }

    @Test
    fun `more passes spread the pixel further`() {
        // Three passes approximate a Gaussian; a single pass is still a box. This guards the
        // loop that runs them.
        val width = 41
        val height = 1
        val single = IntArray(width) { if (it == 20) argb(255, 255, 0, 0) else argb(255, 0, 0, 0) }
        val triple = single.copyOf()

        val once = Blur.apply(single, width, height, radius = 3, passes = 1)
        val thrice = Blur.apply(triple, width, height, radius = 3, passes = 3)

        val edge = 20 + 3
        assertTrue("a repeated blur fades out rather than cutting off",
            red(thrice[edge]) < red(once[edge]))
    }

    @Test
    fun `a buffer that is too small is returned unchanged`() {
        // Rather than throwing, so a surprising bitmap configuration cannot crash the slideshow.
        val pixels = IntArray(3)
        val result = Blur.apply(pixels, width = 10, height = 10, radius = 2)

        assertEquals(pixels.toList(), result.toList())
    }
}
