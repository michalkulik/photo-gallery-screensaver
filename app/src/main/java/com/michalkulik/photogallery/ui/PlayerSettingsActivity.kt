package com.michalkulik.photogallery.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.dream.FitMode
import com.michalkulik.photogallery.dream.PlayOrder
import com.michalkulik.photogallery.dream.Transition
import com.michalkulik.photogallery.weather.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Slideshow tuning: interval, order, transition, scaling, clock, weather and dimming. */
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

        TvUi.toggle(container, getString(R.string.player_weather), settings.showWeather) {
            settings.showWeather = it
            rebuild()
        }

        // Only shown once the weather is on: the key and the location mean nothing without it,
        // and the rows would sit there inviting pointless choices.
        if (settings.showWeather) {
            TvUi.row(
                container,
                getString(R.string.player_weather_key),
                subtitle = getString(R.string.player_weather_key_hint),
                trailing = if (settings.hasOwnWeatherKey()) {
                    getString(R.string.player_weather_key_set)
                } else {
                    getString(R.string.player_weather_key_builtin)
                },
                onClick = { askForApiKey() },
            )
            TvUi.toggle(
                container,
                getString(R.string.player_weather_auto),
                settings.weatherAutoLocation,
            ) {
                settings.weatherAutoLocation = it
                // Switching back to automatic discards a hand-picked place, otherwise the
                // stored coordinates would keep overriding the detected ones.
                if (it) graph.settings.weatherLocationTime = 0L
                rebuild()
            }
            if (!settings.weatherAutoLocation) {
                TvUi.row(
                    container,
                    getString(R.string.player_weather_place),
                    trailing = settings.weatherPlaceName ?: getString(R.string.player_weather_set),
                    onClick = { askForPlace() },
                )
            }
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

    /**
     * Asks for the OpenWeather key.
     *
     * The key cannot be shipped inside the app without publishing it, so it is entered here once
     * and kept in the app's own preferences, the way the NAS password is.
     */
    private fun askForApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.player_weather_key)
            setSingleLine()
            // The built-in key is not shown: it is the same for everyone and printing it here
            // would only invite copying it off the screen.
            setText(if (graph.settings.hasOwnWeatherKey()) graph.settings.weatherApiKey.orEmpty() else "")
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.player_weather_key)
            .setMessage(R.string.player_weather_key_hint)
            .setView(framed(input))
            .setNegativeButton(R.string.common_back, null)
            .setPositiveButton(R.string.common_saved) { _, _ ->
                graph.settings.weatherApiKey = input.text.toString()
                // Fetch at once, so a wrong key is visible here rather than on the screensaver.
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { graph.weather.refresh() } }
                    rebuild()
                }
            }
            .show()
    }

    /**
     * The location the temperature is taken at.
     *
     * A television has no GPS and the network provider offers nothing, so the only automatic
     * source is the public address, which places the device at its connection's exit point. The
     * manual entry is the way to correct that, and to pin the reading for someone who would
     * rather not be placed by an outside service at all.
     */
    private fun askForPlace() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.player_weather_set)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.player_weather_place)
            .setView(framed(input))
            .setNegativeButton(R.string.common_back, null)
            .setPositiveButton(R.string.player_weather_set) { _, _ -> search(input.text.toString()) }
            .show()
    }

    /** Wraps a text field with the explanatory line under it. */
    private fun framed(input: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val side = TvUi.dp(this@PlayerSettingsActivity, 24)
        setPadding(side, TvUi.dp(this@PlayerSettingsActivity, 8), side, 0)
        addView(input)
        addView(
            TextView(this@PlayerSettingsActivity).apply {
                text = getString(R.string.player_weather_auto_hint)
                textSize = 13f
                setPadding(0, TvUi.dp(this@PlayerSettingsActivity, 8), 0, 0)
            },
        )
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.player_weather_searching)
            .setCancelable(false)
            .show()

        val language = resources.configuration.locales[0].language
        scope.launch {
            val places = withContext(Dispatchers.IO) {
                runCatching { graph.weather.search(query, language) }.getOrDefault(emptyList())
            }
            progress.dismiss()
            when {
                places.isEmpty() -> AlertDialog.Builder(this@PlayerSettingsActivity)
                    .setMessage(R.string.player_weather_not_found)
                    .setPositiveButton(R.string.common_back, null)
                    .show()

                places.size == 1 -> applyPlace(places.first())

                else -> AlertDialog.Builder(this@PlayerSettingsActivity)
                    .setTitle(R.string.player_weather_place)
                    .setItems(places.map { it.label }.toTypedArray()) { _, index ->
                        applyPlace(places[index])
                    }
                    .setNegativeButton(R.string.common_back, null)
                    .show()
            }
        }
    }

    private fun applyPlace(place: Place) {
        graph.settings.setManualLocation(place)
        // Fetch straight away, so the reading shown belongs to the place just chosen rather than
        // to the previous one, which would look like the setting had not taken effect.
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.weather.refresh() } }
            rebuild()
        }
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
