package com.michalkulik.photogallery.ui

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import com.michalkulik.photogallery.R

/** Landing screen: shows the active source and links to every sub-screen. */
class MainActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.app_name)

    override fun buildContent(container: LinearLayout) {
        TvUi.body(container, getString(R.string.main_subtitle))

        TvUi.section(container, getString(R.string.main_active_source))
        val active = graph.repository.activeSource()
        if (active == null) {
            TvUi.row(container, getString(R.string.main_none_selected))
        } else {
            // countForDisplay avoids a network round trip for NAS sources on every redraw.
            val count = graph.repository.countForDisplay(active)
            TvUi.row(
                container,
                active.displayName(this),
                subtitle = photoCountText(this, count),
            )
        }

        TvUi.section(container, getString(R.string.main_sources))
        TvUi.row(container, getString(R.string.main_sources)) { open(SourcesActivity::class.java) }
        TvUi.row(container, getString(R.string.main_player)) { open(PlayerSettingsActivity::class.java) }
        TvUi.row(container, getString(R.string.main_syno)) { open(SynoPhotosActivity::class.java) }
        TvUi.row(container, getString(R.string.main_google)) { open(GooglePhotosActivity::class.java) }
        TvUi.row(container, getString(R.string.main_preview)) { open(PreviewActivity::class.java) }

        TvUi.section(container, getString(R.string.main_set_screensaver))
        TvUi.row(
            container,
            getString(R.string.main_set_screensaver),
            subtitle = getString(R.string.main_screensaver_hint, getString(R.string.app_name)),
            onClick = { openDreamSettings() },
        )
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    /** Opens the system screensaver picker; the intent is missing on a few TV firmwares. */
    private fun openDreamSettings() {
        val attempts = listOf(
            Intent(Settings.ACTION_DREAM_SETTINGS),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Dialogs.message(this, getString(R.string.main_set_screensaver), getString(R.string.main_screensaver_hint, getString(R.string.app_name)))
    }
}
