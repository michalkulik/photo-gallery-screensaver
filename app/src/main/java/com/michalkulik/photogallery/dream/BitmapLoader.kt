package com.michalkulik.photogallery.dream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.michalkulik.photogallery.data.Photo
import com.michalkulik.photogallery.util.Logs
import java.io.IOException
import java.io.InputStream

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
        if (photo.uri.startsWith("content:")) {
            context.contentResolver.openInputStream(Uri.parse(photo.uri))
        } else {
            java.io.File(photo.uri).inputStream()
        }
    } catch (error: Exception) {
        Logs.w("Cannot open ${photo.uri}", error)
        null
    }

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
}
