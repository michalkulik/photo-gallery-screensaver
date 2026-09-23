package com.michalkulik.photogallery.dream

/** How the next photo is chosen. */
enum class PlayOrder { SHUFFLE, SEQUENTIAL }

/** How one photo replaces the previous one. */
enum class Transition { FADE, SLIDE }

/** How a photo is scaled into the screen. */
enum class FitMode { COVER, CONTAIN }

/** Everything that controls how the screensaver plays photos. */
data class SlideshowSettings(
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val order: PlayOrder = PlayOrder.SHUFFLE,
    val transition: Transition = Transition.FADE,
    val kenBurns: Boolean = true,
    val fit: FitMode = FitMode.COVER,
    val showClock: Boolean = false,
    val dim: Float = DEFAULT_DIM,
) {
    companion object {
        const val MIN_INTERVAL_SECONDS = 3
        const val MAX_INTERVAL_SECONDS = 600
        const val DEFAULT_INTERVAL_SECONDS = 10
        const val DEFAULT_DIM = 0.25f

        /** Keeps a stored or user-provided interval inside sane bounds. */
        fun clampInterval(seconds: Int): Int =
            seconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

        /** Keeps a stored or user-provided dim factor inside `0f..1f`. */
        fun clampDim(value: Float): Float = value.coerceIn(0f, 1f)

        fun parseOrder(raw: String?): PlayOrder =
            if (raw == PlayOrder.SEQUENTIAL.name) PlayOrder.SEQUENTIAL else PlayOrder.SHUFFLE

        fun parseTransition(raw: String?): Transition =
            if (raw == Transition.SLIDE.name) Transition.SLIDE else Transition.FADE

        fun parseFit(raw: String?): FitMode =
            if (raw == FitMode.CONTAIN.name) FitMode.CONTAIN else FitMode.COVER
    }
}
