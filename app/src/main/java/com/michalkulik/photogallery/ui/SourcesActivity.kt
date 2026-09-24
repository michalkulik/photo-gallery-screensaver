package com.michalkulik.photogallery.ui

import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.PhotoSource
import com.michalkulik.photogallery.data.SourceKind
import com.michalkulik.photogallery.util.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            refreshLiveCounts(sources)
        }

        TvUi.section(container, getString(R.string.sources_add_local))
        TvUi.row(container, getString(R.string.sources_add_local)) {
            startActivity(Intent(this, LocalFolderActivity::class.java))
        }
        TvUi.row(container, getString(R.string.sources_add_syno)) {
            startActivity(Intent(this, SynoPhotosActivity::class.java))
        }
        TvUi.row(container, getString(R.string.sources_add_google)) {
            startActivity(Intent(this, GooglePhotosActivity::class.java))
        }
    }

    /**
     * Updates the counts of sources whose photos live elsewhere.
     *
     * A Synology source is a live view of an album, so its stored count goes stale as pictures
     * are added on the NAS. Redrawing only when a count actually changed keeps this from
     * looping.
     */
    private fun refreshLiveCounts(sources: List<PhotoSource>) {
        val live = sources.filter { it.kind == SourceKind.SYNO }
        if (live.isEmpty()) return
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                live.fold(false) { acc, source ->
                    runCatching { graph.repository.refreshCount(source.id) }
                        .onFailure { Logs.w("Cannot refresh the count of ${source.id}", it) }
                        .getOrDefault(false) || acc
                }
            }
            if (changed) rebuild()
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
