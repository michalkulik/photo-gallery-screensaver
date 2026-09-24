package com.michalkulik.photogallery.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the downsampling maths that keeps decoded photos within the screen budget. */
class BitmapLoaderTest {

    @Test
    fun `halves the image until it would fall below the target`() {
        assertEquals(2, BitmapLoader.sampleSize(width = 4000, height = 3000, targetWidth = 1920, targetHeight = 1080))
        assertEquals(2, BitmapLoader.sampleSize(width = 3840, height = 2160, targetWidth = 1920, targetHeight = 1080))
        assertEquals(4, BitmapLoader.sampleSize(width = 8000, height = 6000, targetWidth = 1920, targetHeight = 1080))
    }

    @Test
    fun `never upscales small images`() {
        assertEquals(1, BitmapLoader.sampleSize(width = 800, height = 600, targetWidth = 1920, targetHeight = 1080))
    }

    @Test
    fun `returns one for invalid input`() {
        assertEquals(1, BitmapLoader.sampleSize(width = 0, height = 0, targetWidth = 1920, targetHeight = 1080))
        assertEquals(1, BitmapLoader.sampleSize(width = 4000, height = 3000, targetWidth = 0, targetHeight = 0))
    }

    @Test
    fun `backdrop is a quarter of the screen wide`() {
        // Large enough that the photo's shapes survive the blur; a tiny copy reads as a mosaic
        // once magnified.
        assertEquals(480, BitmapLoader.backdropWidth(sourceWidth = 4000, targetWidth = 1920))
        assertEquals(960, BitmapLoader.backdropWidth(sourceWidth = 4000, targetWidth = 3840))
    }

    @Test
    fun `backdrop width never upscales a small source`() {
        // Scaling a 20 px image up would cost memory and blur it for nothing.
        assertEquals(20, BitmapLoader.backdropWidth(sourceWidth = 20, targetWidth = 1920))
        assertEquals(300, BitmapLoader.backdropWidth(sourceWidth = 300, targetWidth = 1920))
    }

    @Test
    fun `blur radius grows with the copy but stays bounded`() {
        // A constant radius would leave a wide copy barely blurred and a narrow one washed out.
        assertTrue(BitmapLoader.blurRadius(480) > BitmapLoader.blurRadius(120))
        assertEquals(2, BitmapLoader.blurRadius(1))
        assertEquals(Blur.MAX_RADIUS, BitmapLoader.blurRadius(10_000))
    }
}
