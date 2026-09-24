package com.michalkulik.photogallery.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
 * how large the originals are. Also applies the EXIF orientation, which BitmapFactory ignores.
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

        val rotation = rotationFor(context, photo)
        if (rotation == 0) return decoded
        return rotate(decoded, rotation) ?: decoded
    }

    /**
     * Clockwise rotation that must be applied for the photo to appear upright.
     *
     * MediaStore reports this for photos already on the device. Anything else - a file fetched
     * from the NAS, for instance - has to be read from the image itself, because BitmapFactory
     * ignores the EXIF orientation tag and would otherwise draw every portrait photo on its
     * side.
     */
    private fun rotationFor(context: Context, photo: Photo): Int {
        val reported = ((photo.orientation % 360) + 360) % 360
        if (reported != 0) return reported
        return runCatching {
            openStream(context, photo)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.onFailure {
            Logs.w("Cannot read the EXIF orientation of ${photo.cacheKey ?: photo.uri}", it)
        }.getOrDefault(0)
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

    /**
     * A blurred, screen-filling copy of [source], for the backdrop behind a fitted photo.
     *
     * The photo is scaled down first and the small copy is blurred. Blurring at full resolution
     * would cost a visible pause, and because the result is scaled back up the difference is not
     * visible - what matters is that the blur is real rather than implied by magnification, and
     * that the copy stays large enough to keep the colour structure of the photo.
     */
    fun backdrop(source: Bitmap, targetWidth: Int): Bitmap? = runCatching {
        val width = backdropWidth(source.width, targetWidth)
        val height = (source.height.toFloat() * width / source.width).toInt().coerceAtLeast(1)

        val scaled = if (width >= source.width) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        if (scaled !== source && scaled.isRecycled) return@runCatching null

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = Blur.apply(pixels, width, height, radius = blurRadius(width))
        val result = Bitmap.createBitmap(blurred, width, height, Bitmap.Config.ARGB_8888)
        if (scaled !== source) scaled.recycle()
        result
    }.onFailure { Logs.w("Cannot build the backdrop", it) }.getOrNull()

    /**
     * Width of the blurred copy: a quarter of the screen, never wider than the source.
     *
     * A pure function so the sizing can be tested without decoding an image.
     */
    internal fun backdropWidth(sourceWidth: Int, targetWidth: Int): Int {
        val wanted = (targetWidth / BACKDROP_DIVISOR).coerceAtLeast(MIN_BACKDROP_WIDTH)
        return minOf(sourceWidth, wanted).coerceAtLeast(1)
    }

    /** Blur radius in the copy's own pixels, scaled with its width so the softness is constant. */
    internal fun blurRadius(backdropWidth: Int): Int =
        (backdropWidth / BACKDROP_RADIUS_DIVISOR).coerceIn(2, Blur.MAX_RADIUS)

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

        // The primary URL first, then the fallbacks. Synology serves thumbnails and originals
        // through different APIs, and a rejected request answers with a small JSON error rather
        // than an image, so a wrong guess has to be recoverable rather than fatal.
        val urls = listOf(photo.uri) + photo.fallbackUris
        urls.forEachIndexed { index, url ->
            val attempt = File(directory, "$name.${index}.download")
            val ok = Http.download(url, bearer = null, destination = attempt,
                                   insecure = photo.allowInsecureTls)
            val size = attempt.length()
            if (!ok || size == 0L) {
                Logs.w("Remote download failed for $name from ${url.substringBefore('?')} ($size bytes)")
                attempt.delete()
                return@forEachIndexed
            }
            if (index > 0) {
                Logs.d("Remote photo $name needed the fallback URL")
            }
            // A rejected request still answers 200 with a small JSON error, so the bytes have to
            // be recognised before being trusted: accepting them produced a 38-byte "image" that
            // only failed much later, as an undecodable photo.
            if (!isZip(attempt) && !hasImageSignature(attempt)) {
                Logs.w(
                    "Remote photo $name is not an image: ${size} bytes " +
                        "(${attempt.readBytes().take(80).toByteArray().decodeToString()})",
                )
                attempt.delete()
                return@forEachIndexed
            }
            val zipped = isZip(attempt)
            val extracted = if (zipped) unpackSingleEntry(attempt, target) else move(attempt, target)
            attempt.delete()
            if (extracted) {
                Logs.d("Remote photo $name: $size bytes downloaded, ${target.length()} bytes ready")
                trimCache(directory)
                return target
            }
            Logs.w("Remote photo $name could not be extracted (zip=$zipped, $size bytes)")
        }
        return null
    }

    /**
     * True when the file starts with a known image signature.
     *
     * Synology wraps downloads in a ZIP, so that is checked separately; this covers the case
     * where it hands back the image directly.
     */
    private fun hasImageSignature(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val head = ByteArray(12)
            val read = stream.read(head)
            if (read < 4) return@use false
            val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
            val png = head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
            val gif = head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte()
            // WebP: "RIFF....WEBP", HEIC/AVIF: an ISO base media box with ftyp at offset 4.
            val riff = read >= 12 && String(head, 0, 4) == "RIFF" &&
                String(head, 8, 4) == "WEBP"
            val isoMedia = read >= 12 && String(head, 4, 4) == "ftyp"
            jpeg || png || gif || riff || isoMedia
        }
    }.getOrDefault(false)

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

    /**
     * Width of the backdrop copy, as a fraction of the screen.
     *
     * A quarter of the screen: small enough that the blur is cheap, large enough that the photo's
     * shapes survive. A much smaller copy reads as a mosaic once magnified, which looks like a
     * fault rather than a backdrop.
     */
    private const val BACKDROP_DIVISOR = 4

    /** Never go below this, so a small screen still gets a usable backdrop. */
    private const val MIN_BACKDROP_WIDTH = 64

    /**
     * Blur radius relative to the copy's width.
     *
     * The visible spread is roughly `radius * passes * upscale`, so this divisor has to be read
     * against the copy being a quarter of the screen and the blur running three passes. At 1/14
     * the spread came to about 400 px on a 1920 screen - a fifth of the width - which left the
     * backdrop a flat vertical gradient with no trace of the photo. This keeps the photo's
     * shapes while still reading as a deliberate blur.
     */
    private const val BACKDROP_RADIUS_DIVISOR = 80
}
