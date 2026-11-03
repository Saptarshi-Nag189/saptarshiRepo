package com.apkguard.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.apkguard.core.Severity

/** Design tokens taken from the approved mockup. */
object Colors {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF1D1E22.toInt()
    const val RAISED = 0xFF2A2B30.toInt()
    const val LINE = 0xFF2E3035.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val TEXT2 = 0xFFA3AAB4.toInt()
    const val TEXT3 = 0xFF8F96A0.toInt()
    const val ACCENT = 0xFFEEF224.toInt()
    const val ON_ACCENT = 0xFF000000.toInt()
    const val NAV = 0xFF333333.toInt()
    const val TILE_TOP = 0xFFB8C16E.toInt()
    const val TILE_BOTTOM = 0xFFA5AA54.toInt()
    const val CRITICAL = 0xFFFF8A80.toInt()
    const val HIGH = 0xFFFFB070.toInt()
    const val MEDIUM = 0xFFF2C94C.toInt()
    const val LOW = 0xFF7FB2FF.toInt()
    const val OK = 0xFF7DD3A8.toInt()
    const val DANGER_BG = 0xFF3A1A1C.toInt()
    const val WARN_BG = 0xFF3A2A14.toInt()
    const val OK_BG = 0xFF16301F.toInt()

    fun severity(s: Severity) = when (s) {
        Severity.CRITICAL -> CRITICAL
        Severity.HIGH -> HIGH
        Severity.MEDIUM -> MEDIUM
        Severity.LOW -> LOW
        Severity.INFO -> TEXT2
    }
}

/** Small view factory so screens read like the mockup markup. */
class Ui(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    private val fonts = HashMap<Int, Typeface>()

    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    /** Manrope variable font at a given weight; falls back to the system font. */
    fun font(weight: Int): Typeface = fonts.getOrPut(weight) {
        runCatching {
            Typeface.Builder(ctx.assets, "fonts/Manrope.ttf").setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.create(Typeface.DEFAULT, weight, false)
    }

    val mono: Typeface = Typeface.MONOSPACE

    fun text(s: CharSequence, size: Float = 15f, weight: Int = 500, color: Int = Colors.TEXT, mono: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = size
            typeface = if (mono) this@Ui.mono else font(weight)
            setTextColor(color)
            setLineSpacing(0f, 1.15f)
            includeFontPadding = false
        }

    fun ellipsize(t: TextView) = t.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            strokeColor?.let { setStroke(dp(1), it) }
        }

    fun ripple(bg: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, rounded(0xFFFFFFFF.toInt(), radiusDp))

    fun column(gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(gapDp)); setColor(0) }
        }
    }

    fun row(gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(gapDp), 1); setColor(0) }
        }
    }

    fun card(color: Int = Colors.SURFACE, radiusDp: Int = 24, padH: Int = 16, padV: Int = 16, gap: Int = 0) =
        column(gap).apply {
            background = rounded(color, radiusDp)
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        }

    fun icon(res: Int, tint: Int, sizeDp: Int = 20) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Filled pill button (primary). */
    fun pill(label: String, bg: Int = Colors.ACCENT, fg: Int = Colors.ON_ACCENT, heightDp: Int = 52, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            textSize = 16f
            typeface = font(700)
            setTextColor(fg)
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            background = ripple(rounded(bg, 999), 999)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            contentDescription = label
        }

    /** Outline pill button (secondary). */
    fun outlinePill(label: String, fg: Int = Colors.TEXT, stroke: Int = 0xFF38393E.toInt(), heightDp: Int = 48, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            textSize = 15f
            typeface = font(700)
            setTextColor(fg)
            gravity = Gravity.CENTER
            minHeight = dp(heightDp)
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(rounded(0, 999, stroke), 999)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Round 44dp icon button with a label for screen readers. */
    fun roundIcon(res: Int, label: String, onClick: () -> Unit) = FrameLayout(ctx).apply {
        background = ripple(rounded(Colors.SURFACE, 999), 999)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(icon(res, Colors.TEXT, 22).apply {
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        })
    }

    fun chip(label: String, mono: Boolean = true) = text(label, 12f, 500, Colors.TEXT, mono).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(Colors.RAISED, 999)
    }

    fun sectionLabel(s: String) = text(s, 13f, 700, Colors.TEXT2).apply { setPadding(dp(4), dp(8), 0, 0) }

    fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    /** Simple flow layout for chips. */
    fun flow(gapDp: Int = 6): ViewGroupFlow = ViewGroupFlow(ctx, dp(gapDp))
}

/** Wraps children onto new lines. Avoids pulling in a library for one layout. */
class ViewGroupFlow(ctx: Context, private val gap: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
