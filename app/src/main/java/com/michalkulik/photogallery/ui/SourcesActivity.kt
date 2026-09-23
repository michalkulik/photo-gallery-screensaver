package com.michalkulik.photogallery.ui

import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.PhotoSource

/** Lists every configured photo source and lets the user activate or remove one. */
class SourcesActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.sources_title)

    override fun buildContent(container: LinearLayout) {
        val sources = graph.repository.sources()
        val activeId = graph.repository.activeSource()?.id

        if (sources.isEmpty()) {
            TvUi.body(container, getString(R.string.sources_empty))
        } else {
            TvUi.section(container, getString(R.string.main_sources))
            sources.forEach { source ->
                val count = source.photoCount
                TvUi.row(
                    container,
                    source.displayName(this),
                    subtitle = photoCountText(this, count),
                    trailing = if (source.id == activeId) getString(R.string.sources_active) else null,
                    onClick = { showActions(source, source.id == activeId) },
                )
            }
        }

        TvUi.section(container, getString(R.string.sources_add_local))
        TvUi.row(container, getString(R.string.sources_add_local)) {
            startActivity(Intent(this, LocalFolderActivity::class.java))
        }
        TvUi.row(container, getString(R.string.sources_add_google)) {
            startActivity(Intent(this, GooglePhotosActivity::class.java))
        }
    }

    private fun showActions(source: PhotoSource, isActive: Boolean) {
        val labels = ArrayList<String>()
        if (!isActive) labels += getString(R.string.sources_activate)
        labels += getString(R.string.sources_remove)

        AlertDialog.Builder(this)
            .setTitle(source.displayName(this))
            .setItems(labels.toTypedArray()) { _, index ->
                val label = labels[index]
                when (label) {
                    getString(R.string.sources_activate) -> {
                        graph.repository.setActive(source.id)
                        rebuild()
                    }

                    else -> {
                        graph.repository.remove(source.id)
                        graph.repository.setActive(graph.repository.sources().firstOrNull()?.id)
                        rebuild()
                    }
                }
            }
            .show()
    }
}
