package com.michalkulik.photogallery.ui

import android.content.pm.PackageManager
import android.widget.LinearLayout
import com.michalkulik.photogallery.R
import com.michalkulik.photogallery.data.LocalMedia
import com.michalkulik.photogallery.data.PhotoRepository
import com.michalkulik.photogallery.data.PhotoSource
import com.michalkulik.photogallery.data.SourceKind

/** Lets the user add a photo folder that already exists on the TV. */
class LocalFolderActivity : TvActivity() {

    override val screenTitle: String get() = getString(R.string.sources_add_local)

    override fun buildContent(container: LinearLayout) {
        if (!LocalMedia.hasPermission(this)) {
            TvUi.body(container, getString(R.string.perm_needed))
            TvUi.row(container, getString(R.string.sources_pick_folder)) {
                requestPermissions(LocalMedia.requiredPermissions(), REQUEST_CODE)
            }
            return
        }

        val buckets = LocalMedia.buckets(this)
        if (buckets.isEmpty()) {
            TvUi.body(container, getString(R.string.sources_folder_empty))
            return
        }

        TvUi.section(container, getString(R.string.sources_pick_folder))
        buckets.forEach { bucket ->
            val title = bucket.name.ifBlank { getString(R.string.sources_all_photos) }
            TvUi.row(
                container,
                title,
                subtitle = photoCountText(this, bucket.photoCount),
                onClick = { addSource(bucket.id, bucket.name) },
            )
        }
    }

    private fun addSource(bucketId: String, bucketName: String) {
        if (bucketId != LocalMedia.ALL_BUCKETS && bucketName.isBlank()) return
        val source = PhotoSource(
            id = PhotoRepository.newSourceId(SourceKind.LOCAL, bucketId),
            kind = SourceKind.LOCAL,
            name = bucketName,
            ref = bucketId,
            photoCount = LocalMedia.photos(this, bucketId).size,
        )
        graph.repository.addOrUpdate(source)
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            Dialogs.message(this, getString(R.string.sources_add_local), getString(R.string.perm_denied))
        }
        rebuild()
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
