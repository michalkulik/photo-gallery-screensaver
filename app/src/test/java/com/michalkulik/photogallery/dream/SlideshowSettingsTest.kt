package com.michalkulik.photogallery.dream

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers validation of the persisted slideshow settings. */
class SlideshowSettingsTest {

    @Test
    fun `interval is clamped to the supported range`() {
        assertEquals(SlideshowSettings.MIN_INTERVAL_SECONDS, SlideshowSettings.clampInterval(1))
        assertEquals(SlideshowSettings.MIN_INTERVAL_SECONDS, SlideshowSettings.clampInterval(3))
        assertEquals(30, SlideshowSettings.clampInterval(30))
        assertEquals(SlideshowSettings.MAX_INTERVAL_SECONDS, SlideshowSettings.clampInterval(10_000))
    }

    @Test
    fun `dim factor is clamped to zero one`() {
        assertEquals(0f, SlideshowSettings.clampDim(-0.5f))
        assertEquals(0.25f, SlideshowSettings.clampDim(0.25f))
        assertEquals(1f, SlideshowSettings.clampDim(3f))
    }

    @Test
    fun `enum parsing is tolerant and falls back to defaults`() {
        assertEquals(PlayOrder.SEQUENTIAL, SlideshowSettings.parseOrder("SEQUENTIAL"))
        assertEquals(PlayOrder.SHUFFLE, SlideshowSettings.parseOrder(null))
        assertEquals(PlayOrder.SHUFFLE, SlideshowSettings.parseOrder("nonsense"))

        assertEquals(Transition.SLIDE, SlideshowSettings.parseTransition("SLIDE"))
        assertEquals(Transition.FADE, SlideshowSettings.parseTransition(null))

        assertEquals(FitMode.COVER, SlideshowSettings.parseFit("COVER"))
        assertEquals(FitMode.CONTAIN, SlideshowSettings.parseFit("CONTAIN"))
    }

    @Test
    fun `an unset scaling mode fits the whole photo`() {
        // This has to agree with the data class default. When it did not, a fresh install read
        // back COVER and cropped a portrait photo to about 40% of its height, cutting off heads
        // and feet while the setting on screen claimed otherwise.
        assertEquals(FitMode.CONTAIN, SlideshowSettings.parseFit(null))
        assertEquals(FitMode.CONTAIN, SlideshowSettings().fit)
    }

    @Test
    fun `an unknown scaling mode does not silently crop`() {
        // Fitting the whole photo is the safe fallback: cropping is the surprising behaviour.
        assertEquals(FitMode.CONTAIN, SlideshowSettings.parseFit("something-else"))
    }

    @Test
    fun `defaults are sensible`() {
        val defaults = SlideshowSettings()

        assertEquals(10, defaults.intervalSeconds)
        assertEquals(PlayOrder.SHUFFLE, defaults.order)
        assertEquals(Transition.FADE, defaults.transition)
        assertEquals(FitMode.CONTAIN, defaults.fit)
        assertEquals(true, defaults.kenBurns)
        assertEquals(false, defaults.showClock)
    }

    @Test
    fun `transition duration has a positive default`() {
        assertEquals(true, SlideshowSettings().transitionMillis() > 0L)
    }
}
