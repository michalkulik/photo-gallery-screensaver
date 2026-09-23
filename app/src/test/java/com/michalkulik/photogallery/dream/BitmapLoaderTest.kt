package com.michalkulik.photogallery.dream

import org.junit.Assert.assertEquals
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
    fun `backdrop width is capped so the copy stays tiny`() {
        // The backdrop is scaled up by the GPU, which is what makes it look blurred. Keeping it
        // a few dozen pixels wide is what keeps that free.
        assertEquals(64, BitmapLoader.backdropWidth(1920))
        assertEquals(64, BitmapLoader.backdropWidth(4000))
    }

    @Test
    fun `backdrop width never upscales a small source`() {
        // Scaling a 20 px image up to 64 would cost memory and blur it for nothing.
        assertEquals(20, BitmapLoader.backdropWidth(20))
        assertEquals(64, BitmapLoader.backdropWidth(64))
    }
}
