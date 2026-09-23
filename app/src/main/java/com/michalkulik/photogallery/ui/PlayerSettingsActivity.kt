package com.michalkulik.photogallery.ui

import android.app.AlertDialog
import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.dream.FitMode
import com.michalkulik.photogallery.dream.PlayOrder
import com.michalkulik.photogallery.dream.Transition

/** Slideshow tuning: interval, order, transition, scaling, clock and dimming. */
class PlayerSettingsActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.player_title)

    override fun buildContent(container: LinearLayout) {
        val settings = graph.settings

        val intervals = listOf(5, 10, 15, 20, 30, 60, 120)
        TvUi.row(
            container,
            getString(R.string.player_interval),
            trailing = getString(R.string.player_seconds, settings.intervalSeconds),
            onClick = {
                choose(intervals.map { getString(R.string.player_seconds, it) to it }) {
                    settings.intervalSeconds = it
                    rebuild()
                }
            },
        )

        TvUi.row(
            container,
            getString(R.string.player_order),
            trailing = orderLabel(settings.order),
            onClick = {
                choose(listOf(orderLabel(PlayOrder.SHUFFLE) to PlayOrder.SHUFFLE, orderLabel(PlayOrder.SEQUENTIAL) to PlayOrder.SEQUENTIAL)) {
                    settings.order = it
                    rebuild()
                }
            },
        )

        TvUi.row(
            container,
            getString(R.string.player_transition),
            trailing = transitionLabel(settings.transition),
            onClick = {
                choose(
                    listOf(
                        getString(R.string.player_transition_fade) to Transition.FADE,
                        getString(R.string.player_transition_slide) to Transition.SLIDE,
                    ),
                ) {
                    settings.transition = it
                    rebuild()
                }
            },
        )

        TvUi.row(
            container,
            getString(R.string.player_fit),
            trailing = fitLabel(settings.fit),
            onClick = {
                choose(
                    listOf(
                        getString(R.string.player_fit_cover) to FitMode.COVER,
                        getString(R.string.player_fit_contain) to FitMode.CONTAIN,
                    ),
                ) {
                    settings.fit = it
                    rebuild()
                }
            },
        )

        TvUi.toggle(container, getString(R.string.player_ken_burns), settings.kenBurns) {
            settings.kenBurns = it
            rebuild()
        }

        TvUi.toggle(container, getString(R.string.player_clock), settings.showClock) {
            settings.showClock = it
            rebuild()
        }

        val dims = listOf(0f, 0.15f, 0.3f, 0.5f, 0.7f)
        TvUi.row(
            container,
            getString(R.string.player_dim),
            trailing = "${(settings.dim * 100).toInt()}%",
            onClick = {
                choose(dims.map { "${(it * 100).toInt()}%" to it }) {
                    settings.dim = it
                    rebuild()
                }
            },
        )
    }

    private fun <T> choose(options: List<Pair<String, T>>, onPick: (T) -> Unit) {
        AlertDialog.Builder(this)
            .setItems(options.map { it.first }.toTypedArray()) { _, index -> onPick(options[index].second) }
            .show()
    }

    private fun orderLabel(order: PlayOrder): String = getString(
        if (order == PlayOrder.SEQUENTIAL) R.string.player_order_sequential else R.string.player_order_shuffle,
    )

    private fun transitionLabel(transition: Transition): String = getString(
        if (transition == Transition.SLIDE) R.string.player_transition_slide else R.string.player_transition_fade,
    )

    private fun fitLabel(fit: FitMode): String = getString(
        if (fit == FitMode.CONTAIN) R.string.player_fit_contain else R.string.player_fit_cover,
    )
}
