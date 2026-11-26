package com.nexus.visionos.xr

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import com.nexus.visionos.R
import kotlin.math.min

/** Shared motion level from the performance governor: 0 full, 1 reduced, 2 no animation. */
object Motion {
    @Volatile var level = 0
}

internal fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

/**
 * Liquid glass surface for controls inside windows: a translucent body, a light rim that is
 * brightest at the top, and a soft reflection. Draws without blur, so it stays cheap.
 */
class LiquidGlass(
    private val tint: Int = 0xFFFFFF,
    private val bodyAlpha: Int = 0x2E,
    private val rimAlpha: Int = 0x8C,
    /** Negative means a full capsule or circle. */
    private val radius: Float = -1f
) : Drawable() {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var opacity = 255

    private fun colour(alpha: Int, rgb: Int = tint) = (alpha * opacity / 255 shl 24) or (rgb and 0xFFFFFF)

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(1f, 1f)
        if (rect.width() <= 0 || rect.height() <= 0) return
        val r = if (radius < 0) min(rect.width(), rect.height()) / 2 else min(radius, rect.height() / 2)
        body.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(colour(bodyAlpha + 0x16), colour(bodyAlpha), colour(bodyAlpha + 0x08)), null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, body)
        shine.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.5f,
            colour(0x2C, 0xFFFFFF), colour(0, 0xFFFFFF), Shader.TileMode.CLAMP
        )
        val inner = RectF(rect.left + 3, rect.top + 2, rect.right - 3, rect.top + rect.height() * 0.5f)
        canvas.drawRoundRect(inner, r, r, shine)
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(colour(rimAlpha, 0xFFFFFF), colour(0x14, 0xFFFFFF), colour(rimAlpha / 2, 0xFFFFFF)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Resting, aimed-at and pressed looks. */
fun glassStates(tint: Int = 0xFFFFFF, body: Int = 0x26, radius: Float = -1f): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), LiquidGlass(tint, body + 0x4C, 0xF0, radius))
    addState(intArrayOf(android.R.attr.state_hovered), LiquidGlass(tint, body + 0x2C, 0xE0, radius))
    addState(intArrayOf(), LiquidGlass(tint, body, 0x86, radius))
}

/** The gap kept between any two buttons. */
internal const val GAP_DP = 5

internal fun Context.vertical(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(px(10), px(10), px(10), px(10))
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    clipChildren = false
    clipToPadding = false
    build()
}

internal fun LinearLayout.row(weight: Float = 0f, height: Int = -2, build: LinearLayout.() -> Unit) = LinearLayout(context).also {
    it.orientation = LinearLayout.HORIZONTAL
    it.clipChildren = false
    it.clipToPadding = false
    it.build()
    addView(it, LinearLayout.LayoutParams(-1, if (weight > 0) 0 else height, weight))
}

private fun LinearLayout.place(view: View, weight: Float, height: Int) {
    val horizontal = orientation == LinearLayout.HORIZONTAL
    val params = if (horizontal) LinearLayout.LayoutParams(0, -1, weight) else LinearLayout.LayoutParams(-1, height)
    val gap = context.px(GAP_DP)
    params.setMargins(gap, gap, gap, gap)
    addView(view, params)
}

/** A glass text button with space around it. */
internal fun LinearLayout.key(text: String, weight: Float = 1f, tint: Int = 0xFFFFFF, click: () -> Unit) = Button(context).also {
    it.text = text
    it.isAllCaps = false
    it.textSize = 15f
    it.setTextColor(-1)
    it.background = glassStates(tint, radius = context.px(18).toFloat())
    it.stateListAnimator = null
    it.isFocusable = false
    it.minWidth = 0
    it.minimumWidth = 0
    it.minHeight = 0
    it.minimumHeight = 0
    it.maxLines = 2
    it.ellipsize = TextUtils.TruncateAt.END
    it.setPadding(context.px(2), 0, context.px(2), 0)
    it.setOnClickListener { _ -> click() }
    place(it, weight, context.px(46))
}

/** A glass button showing an icon, optionally with a label under or beside it. */
internal fun LinearLayout.iconKey(
    icon: Int, label: String? = null, weight: Float = 1f, active: Boolean = false, click: () -> Unit
): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.VERTICAL
    it.gravity = Gravity.CENTER
    it.background = glassStates(if (active) 0x7FC4FF else 0xFFFFFF, if (active) 0x5A else 0x26, context.px(18).toFloat())
    it.isClickable = true
    it.contentDescription = label
    it.setOnClickListener { _ -> click() }
    it.addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(context.px(20), context.px(20)))
    if (label != null) it.addView(TextView(context).apply {
        text = label
        textSize = 11f
        setTextColor(-1)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(-1, -2))
    place(it, weight, context.px(if (label == null) 42 else 56))
}

/** A round coloured app icon with its name under it, as used on Home. */
internal fun Context.appIcon(icon: Int, label: String, colours: IntArray, size: Int, click: () -> Unit): View {
    val disc = FrameLayout(this).apply {
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colours
                ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL },
                LiquidGlass(bodyAlpha = 0x08, rimAlpha = 0xC0)
            )
        )
        val inset = (size * 0.23f).toInt()
        addView(ImageView(context).apply { setImageResource(icon) }, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        clipChildren = false
        contentDescription = label
        setOnClickListener { click() }
        // The disc follows the item's pressed and hovered state.
        disc.isDuplicateParentStateEnabled = true
        disc.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), LiquidGlass(bodyAlpha = 0x50, rimAlpha = 0xFF))
            addState(intArrayOf(android.R.attr.state_hovered), LiquidGlass(bodyAlpha = 0x24, rimAlpha = 0xFF))
        }
        addView(disc, LinearLayout.LayoutParams(size, size))
        addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(-1)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

internal fun LinearLayout.text(value: String = "", size: Float = 15f) = TextView(context).also {
    it.text = value
    it.textSize = size
    it.setTextColor(-1)
    it.setPadding(context.px(6), context.px(3), context.px(6), context.px(3))
    addView(it, LinearLayout.LayoutParams(-1, -2))
}

internal fun editor(context: Context) = EditText(context).apply {
    showSoftInputOnFocus = false // typing comes from the spatial keyboard
    setTextColor(-1)
    setHintTextColor(0x99FFFFFF.toInt())
    background = LiquidGlass(0x000000, 0x4A, 0x66, context.px(16).toFloat())
    setPadding(context.px(12), context.px(8), context.px(12), context.px(8))
}

/** A scrolling column, for lists. */
internal fun Context.scrolling(build: LinearLayout.() -> Unit): ScrollView {
    val column = vertical(build)
    return ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(column, FrameLayout.LayoutParams(-1, -2))
    }
}

/** Re-runs [update] every [periodMs] until the returned stop function is called. */
internal fun ticker(periodMs: Long, update: () -> Unit): () -> Unit {
    val handler = Handler(Looper.getMainLooper())
    val task = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, periodMs) }
    }
    handler.post(task)
    return { handler.removeCallbacks(task) }
}

internal val closeIcon get() = R.drawable.ic_ui_close
