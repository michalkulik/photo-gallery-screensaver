package com.michalkulik.photogallery.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.michalkulik.photogallery.data.Photo
import com.michalkulik.photogallery.util.Http
import com.michalkulik.photogallery.util.Logs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Decodes photos downsampled to roughly the screen size, which keeps memory use flat no matter
 * how large the originals are. Also applies the EXIF orientation reported by MediaStore.
 */
object BitmapLoader {

    fun load(context: Context, photo: Photo, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = openStream(context, photo)
        if (opened == null) {
            Logs.w("Cannot open stream for ${photo.uri}")
            return null
        }
        opened.use { decodeStream(it, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Logs.w("No image bounds for ${photo.uri} (${bounds.outWidth}x${bounds.outHeight}, mime=${bounds.outMimeType})")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = openStream(context, photo)?.use { decodeStream(it, options) }
        if (decoded == null) {
            Logs.w("Second pass decode failed for ${photo.uri}")
            return null
        }

        val rotation = ((photo.orientation % 360) + 360) % 360
        if (rotation == 0) return decoded
        return rotate(decoded, rotation) ?: decoded
    }

    /** Largest power-of-two sample size that still keeps the image at or above the target. */
    internal fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= targetWidth && halfHeight / sample >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun openStream(context: Context, photo: Photo): InputStream? = try {
        when {
            photo.uri.startsWith("content:") ->
                context.contentResolver.openInputStream(Uri.parse(photo.uri))

            photo.uri.startsWith("http") ->
                remoteFile(context, photo)?.inputStream()

            else -> java.io.File(photo.uri).inputStream()
        }
    } catch (error: Exception) {
        Logs.w("Cannot open ${photo.uri}", error)
        null
    }

    /**
     * Materialises a remote photo on disk, downloading it only once.
     *
     * The screensaver decodes each photo twice (once for the bounds, once for the pixels), and
     * cycles back through the same list, so without this every appearance would re-download the
     * image. The file name comes from [Photo.cacheKey] rather than the URL, because a Synology
     * URL carries a session id that changes on every login.
     */
    private fun remoteFile(context: Context, photo: Photo): File? {
        val name = photo.cacheKey ?: photo.uri.hashCode().toUInt().toString(16)
        val directory = File(context.cacheDir, REMOTE_DIR)
        val target = File(directory, name)
        if (target.length() > 0) {
            // Touch it so the eviction below keeps what is actually being played.
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        val download = File(directory, "$name.download")
        val ok = Http.download(photo.uri, bearer = null, destination = download, insecure = photo.allowInsecureTls)
        val downloadedSize = download.length()
        if (!ok || downloadedSize == 0L) {
            Logs.w("Remote download failed for $name ($downloadedSize bytes)")
            download.delete()
            return null
        }

        // Synology answers SYNO.Foto.Download with a ZIP archive holding the image, not with the
        // image itself, so the bytes have to be unpacked before they can be decoded.
        val zipped = isZip(download)
        val extracted = if (zipped) unpackSingleEntry(download, target) else move(download, target)
        download.delete()

        if (!extracted) {
            Logs.w("Remote photo $name could not be extracted (zip=$zipped, $downloadedSize bytes)")
            return null
        }
        Logs.d("Remote photo $name: $downloadedSize bytes downloaded, ${target.length()} bytes ready")
        trimCache(directory)
        return target
    }

    /**
     * Keeps the download cache inside a size budget.
     *
     * The NAS serves originals, so a large library would otherwise fill the device: a few
     * thousand photos at a couple of megabytes each is tens of gigabytes. Least recently used
     * files are dropped first, and playing a photo counts as a use.
     */
    private fun trimCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return

        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= CACHE_LIMIT_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
        Logs.d("Trimmed the remote photo cache to $total bytes")
    }

    /** A ZIP local file header, which is how Synology wraps downloaded media. */
    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val signature = ByteArray(4)
            stream.read(signature) == 4 &&
                signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte() &&
                signature[2] == 0x03.toByte() && signature[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    /**
     * Writes the image entry of [archive] to [target].
     *
     * Uses [ZipFile] rather than [ZipInputStream] on purpose: Synology writes the entries with
     * unknown sizes in the local header (a data descriptor) and method STORED. Reading local
     * headers therefore yields an entry of size -1 whose data cannot be streamed, while the
     * central directory that [ZipFile] consults carries the real sizes.
     */
    private fun unpackSingleEntry(archive: File, target: File): Boolean = runCatching {
        ZipFile(archive).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory }
            if (entry == null) {
                Logs.w("Archive ${archive.name} holds no file entries")
                return@use false
            }
            val copied = zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Logs.d("Unpacked ${entry.name}: copied=$copied of ${entry.size} bytes")
            copied > 0
        }
    }.onFailure { Logs.w("Cannot unpack ${archive.name}", it) }.getOrDefault(false)

    private fun move(source: File, target: File): Boolean = runCatching {
        if (source.renameTo(target)) {
            true
        } else {
            source.copyTo(target, overwrite = true)
            true
        }
    }.getOrDefault(false)

    private fun decodeStream(stream: InputStream, options: BitmapFactory.Options): Bitmap? =
        try {
            BitmapFactory.decodeStream(stream, null, options)
        } catch (error: IOException) {
            Logs.w("Decode failed", error)
            null
        }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap? {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (rotated != source) source.recycle()
            rotated
        } catch (error: OutOfMemoryError) {
            Logs.e("Rotate out of memory", error)
            null
        }
    }

    /** Sub-directory of the app cache holding downloaded NAS photos. */
    private const val REMOTE_DIR = "remote"

    /**
     * Budget for the download cache. Synology serves originals, so without a cap a few thousand
     * photos would consume tens of gigabytes on the device.
     */
    private const val CACHE_LIMIT_BYTES = 512L * 1024 * 1024
}
