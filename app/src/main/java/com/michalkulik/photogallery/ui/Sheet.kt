package com.michalkulik.photogallery.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * A modal dialog with an optional big code, QR image and progress bar.
 * Used for the Google device-code sign-in, the photo picker link and import progress.
 */
class Sheet(context: Context, title: String) {

    private val context = context
    private val messageView = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 15f
    }
    private val codeView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 34f
        letterSpacing = 0.2f
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, TvUi.dp(context, 12), 0, TvUi.dp(context, 12))
    }
    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        visibility = android.view.View.GONE
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        visibility = android.view.View.GONE
        max = 100
    }
    private val bar = ProgressBar(context).apply { visibility = android.view.View.GONE }

    private val dialog: AlertDialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    TvUi.dp(context, 32),
                    TvUi.dp(context, 16),
                    TvUi.dp(context, 32),
                    TvUi.dp(context, 16),
                )
                addView(
                    messageView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(codeView)
                addView(
                    imageView,
                    LinearLayout.LayoutParams(
                        TvUi.dp(context, 260),
                        TvUi.dp(context, 260),
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL },
                )
                addView(
                    progress,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(bar)
            },
        )
        .setCancelable(false)
        .create()

    val isShowing: Boolean get() = dialog.isShowing

    fun setMessage(text: String) {
        messageView.text = text
    }

    fun setCode(text: String?) {
        codeView.text = text.orEmpty()
        codeView.visibility = if (text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    fun setImage(bitmap: Bitmap?) {
        if (bitmap == null) {
            imageView.visibility = android.view.View.GONE
        } else {
            imageView.setImageBitmap(bitmap)
            imageView.visibility = android.view.View.VISIBLE
        }
    }

    fun showIndeterminate() {
        bar.visibility = android.view.View.VISIBLE
        progress.visibility = android.view.View.GONE
    }

    fun showProgress(done: Int, total: Int) {
        bar.visibility = android.view.View.GONE
        progress.visibility = android.view.View.VISIBLE
        progress.max = total.coerceAtLeast(1)
        progress.progress = done
    }

    fun show() {
        if (!dialog.isShowing) dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}

/** Simple text prompt that works with the D-pad and the on-screen keyboard. */
object Dialogs {

    fun input(
        context: Context,
        title: String,
        initial: String,
        secret: Boolean = false,
        onResult: (String) -> Unit,
    ) {
        val input = EditText(context).apply {
            setText(initial)
            setSingleLine()
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
        val container = LinearLayout(context).apply {
            setPadding(
                TvUi.dp(context, 32),
                TvUi.dp(context, 8),
                TvUi.dp(context, 32),
                TvUi.dp(context, 8),
            )
            addView(input)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onResult(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    fun message(context: Context, title: String, message: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
