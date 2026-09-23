package com.michalkulik.photogallery.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.michalkulik.photogallery.R

/**
 * Small helper set that builds the D-pad friendly TV UI programmatically. Keeping it in code
 * avoids a pile of layout XML and guarantees every row is focusable and visually consistent.
 */
object TvUi {

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(context: Context, colorRes: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(ContextCompat.getColor(context, colorRes))
        }

    private fun rowBackground(context: Context): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(context, R.color.surface_focus, 10))
        addState(intArrayOf(), rounded(context, R.color.surface, 10))
    }

    class Screen(val root: LinearLayout, val content: LinearLayout)

    /** Creates a titled, scrollable screen with a footer hint. */
    fun screen(context: Context, title: String, footer: String? = null): Screen {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }

        val header = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text))
            textSize = 30f
            setPadding(dp(context, 48), dp(context, 36), dp(context, 48), dp(context, 8))
        }
        root.addView(header)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The top padding doubles as headroom: when the first row gains focus the
            // ScrollView scrolls a little, and without it the intro text gets clipped.
            setPadding(dp(context, 40), dp(context, 28), dp(context, 40), dp(context, 40))
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        if (footer != null) {
            val footerText = TextView(context).apply {
                text = footer
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 14f
                setPadding(dp(context, 48), dp(context, 12), dp(context, 48), dp(context, 24))
            }
            root.addView(footerText)
        }
        return Screen(root, content)
    }

    fun section(container: LinearLayout, title: String) {
        val context = container.context
        container.addView(
            TextView(context).apply {
                text = title.uppercase(context.resources.configuration.locales[0])
                setTextColor(ContextCompat.getColor(context, R.color.accent))
                textSize = 13f
                setPadding(dp(context, 12), dp(context, 24), dp(context, 12), dp(context, 8))
            },
        )
    }

    fun body(container: LinearLayout, text: String) {
        val context = container.context
        container.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 15f
                setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 12))
            },
        )
    }

    /**
     * Adds a focusable row.
     *
     * @param trailing optional right-aligned value such as the current setting or photo count.
     */
    fun row(
        container: LinearLayout,
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        onClick: (() -> Unit)? = null,
    ): View {
        val context = container.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rowBackground(context)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            TextView(context).apply {
                text = title
                setTextColor(ContextCompat.getColor(context, R.color.text))
                textSize = 20f
            },
        )
        if (subtitle != null) {
            column.addView(
                TextView(context).apply {
                    text = subtitle
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    textSize = 14f
                },
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (trailing != null) {
            row.addView(
                TextView(context).apply {
                    text = trailing
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                    textSize = 18f
                    setPadding(dp(context, 16), 0, 0, 0)
                },
            )
        }

        if (onClick != null) {
            row.setOnClickListener { onClick() }
        } else {
            row.isFocusable = false
        }

        row.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(120L)
                .start()
        }

        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 10) },
        )
        return row
    }

    fun toggle(
        container: LinearLayout,
        title: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ) {
        val context = container.context
        val on = context.getString(R.string.common_on)
        val off = context.getString(R.string.common_off)
        row(container, title, trailing = if (checked) on else off) { onToggle(!checked) }
    }

    /** Focuses the first focusable child, so D-pad navigation starts somewhere sensible. */
    fun focusFirst(root: View) {
        root.post {
            val target = findFirstFocusable(root)
            target?.requestFocus()
        }
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findFirstFocusable(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
