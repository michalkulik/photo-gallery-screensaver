package com.michalkulik.photogallery.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.michalkulik.photogallery.util.Logs

/** Renders a QR code, used so the Google sign-in and photo picker links can be opened on a phone. */
object QrCode {

    fun bitmap(text: String, sizePx: Int): Bitmap? = try {
        val matrix = MultiFormatWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val offset = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    } catch (error: Exception) {
        Logs.w("QR generation failed", error)
        null
    }
}
