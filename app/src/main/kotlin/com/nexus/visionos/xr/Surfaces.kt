package com.nexus.visionos.xr

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.ScrollView
import com.nexus.xr.Act
import com.nexus.xr.VoiceCommands
import org.json.JSONObject
import com.nexus.xr.PressAction
import com.nexus.xr.PressEvent
import com.nexus.xr.PressGate
import com.nexus.xr.TouchAction

/** Content of a spatial window. */
class SpatialApp(val view: View, val dispose: () -> Unit = {}) {
    /** Carries out a spoken action the app understands. Returns false when it does not apply. */
    var command: ((Act) -> Boolean)? = null
    /** Saves anything that must survive the app being closed by the system. */
    var flush: () -> Unit = {}
    /** Handles Android Back inside the app, such as leaving full screen. */
    var back: () -> Boolean = { false }
    /** Runs before the views move to a window of a new shape. */
    var beforeReshape: () -> Unit = {}
}

/**
 * A real Android view hierarchy rendered into a GPU texture: a private virtual display
 * hosts a Presentation. Used only for this app's own screens, never other apps.
 */
class SpatialSurface(
    private val activity: Activity,
    private val name: String,
    width: Int,
    height: Int,
    private val textScale: Float,
    private val longPressMs: () -> Long,
    private val onHoverTarget: () -> Unit,
    private val create: (android.content.Context) -> SpatialApp
) {
    var width = width
        private set
    var height = height
        private set
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var app: SpatialApp? = null
    private val gate = PressGate(SLOP)
    private var downTime = 0L
    /** True while the app has a DOWN without a matching UP or CANCEL. */
    private var delivering = false
    private var pressed: View? = null
    private var hovered: View? = null
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()

    /** Progress of a still hold towards a long press. */
    val holdProgress get() = gate.progress

    fun attach(surface: Surface) {
        if (!surface.isValid) return
        display?.let { it.surface = surface; return }
        val manager = activity.getSystemService(DisplayManager::class.java)
        val virtual = manager.createVirtualDisplay(
            name, width, height, DPI, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        ) ?: return
        display = virtual
        try {
            present(virtual) { context ->
                val content = create(scaled(context))
                app = content
                content.view
            }
            Log.i("NexusXR", "Spatial surface $name ready")
        } catch (e: RuntimeException) {
            Log.e("NexusXR", "Spatial surface $name failed", e)
        }
    }

    private fun present(on: VirtualDisplay, content: (android.content.Context) -> View) {
        val window = Presentation(activity, on.display, android.R.style.Theme_DeviceDefault_NoActionBar)
        window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.setCancelable(false)
        window.setContentView(content(window.context))
        window.show()
        presentation = window
    }

    private fun scaled(context: android.content.Context): android.content.Context {
        if (textScale == 1f) return context
        return ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            applyOverrideConfiguration(Configuration().also { it.fontScale = textScale })
        }
    }

    /**
     * Gives the window a new shape. Android closes a Presentation whose display changes size, so the
     * same views move into a new one and keep their state. [resizeTexture] runs before the display.
     */
    fun resize(newWidth: Int, newHeight: Int, resizeTexture: () -> Unit) {
        val virtual = display ?: return
        val view = app?.view ?: return
        app?.beforeReshape?.invoke()
        show(null)
        gate.cancel()
        delivering = false
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { presentation?.dismiss() }
        presentation = null
        width = newWidth
        height = newHeight
        resizeTexture()
        virtual.resize(newWidth, newHeight, DPI)
        runCatching { present(virtual) { view } }.onFailure { Log.e("NexusXR", "Resizing $name failed", it) }
    }

    private val root: View? get() = presentation?.window?.decorView

    /** Forwards a pointer as touch input, through the gate that separates taps from long presses. */
    fun touch(action: TouchAction, u: Float, v: Float) {
        val x = u * width
        val y = v * height
        val now = SystemClock.uptimeMillis()
        val events = when (action) {
            TouchAction.DOWN -> {
                gate.longPressMs = longPressMs()
                trail.clear()
                show(clickableAt(x, y))
                gate.down(x, y, now)
            }
            TouchAction.MOVE -> {
                trail.addLast(Triple(now, x, y))
                while (trail.size > 12) trail.removeFirst()
                gate.move(x, y, now)
            }
            TouchAction.UP -> gate.up(x, y)
            TouchAction.CANCEL -> gate.cancel()
        }
        events.forEach { deliver(it, now) }
        if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            pressed = null
            show(null)
        }
    }

    private fun deliver(event: PressEvent, now: Long) {
        when (event.action) {
            PressAction.DOWN -> {
                downTime = now
                delivering = true
                send(MotionEvent.ACTION_DOWN, event.x, event.y, now)
                // Dragging must not leave a button looking held.
                show(null)
            }
            PressAction.MOVE -> if (delivering) send(MotionEvent.ACTION_MOVE, event.x, event.y, now)
            PressAction.UP -> if (delivering) {
                delivering = false
                if (!fling(event.x, event.y, now)) send(MotionEvent.ACTION_UP, event.x, event.y, now)
            }
            PressAction.CANCEL -> if (delivering) {
                delivering = false
                send(MotionEvent.ACTION_CANCEL, event.x, event.y, now)
            }
            PressAction.LONG_PRESS -> {
                val target = deepestAt(event.x, event.y)
                // Web pages time their own long press from the DOWN that is now being held.
                if (target !is WebView) {
                    var view: View? = target
                    while (view != null && !view.isLongClickable) view = view.parent as? View
                    view?.performLongClick()
                    delivering = false
                    send(MotionEvent.ACTION_CANCEL, event.x, event.y, now)
                }
                Log.i("NexusXR", "long press on $name")
            }
        }
    }

    private fun send(code: Int, x: Float, y: Float, now: Long) {
        val target = root ?: return
        val event = MotionEvent.obtain(downTime, now, code, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val consumed = target.dispatchTouchEvent(event)
        if (code != MotionEvent.ACTION_MOVE) {
            Log.i("NexusXR", "touch $name ${MotionEvent.actionToString(code)} at %.0f,%.0f consumed=$consumed".format(x, y))
        }
        event.recycle()
    }

    /**
     * Hand tracking delivers positions too slowly for Android's fling detection, so a quick
     * drag is turned into momentum here. Screen touches are fast enough and are left alone.
     */
    private fun fling(x: Float, y: Float, now: Long): Boolean {
        val recent = trail.filter { now - it.first <= 350 }
        if (recent.size < 2) return false
        val first = recent.first()
        val last = recent.last()
        val span = last.first - first.first
        if (span <= 0 || span / (recent.size - 1) < 40) return false
        val vx = (last.second - first.second) * 1000f / span
        val vy = (last.third - first.third) * 1000f / span
        if (kotlin.math.abs(vy) < 350 && kotlin.math.abs(vx) < 350) return false
        var view: View? = deepestAt(x, y)
        while (view != null) {
            if (view is WebView || view is ScrollView || view is AbsListView) {
                send(MotionEvent.ACTION_CANCEL, x, y, now)
                when (view) {
                    is WebView -> view.flingScroll(-vx.toInt(), -vy.toInt())
                    is ScrollView -> view.fling(-vy.toInt())
                    is AbsListView -> view.fling(-vy.toInt())
                }
                return true
            }
            view = view.parent as? View
        }
        return false
    }

    private fun deepestAt(x: Float, y: Float): View? {
        fun search(view: View): View? {
            val bounds = Rect()
            if (view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x.toInt(), y.toInt())) return null
            if (view is ViewGroup && view !is WebView) {
                for (i in view.childCount - 1 downTo 0) search(view.getChildAt(i))?.let { return it }
            }
            return view
        }
        return root?.let(::search)
    }

    private fun clickableAt(x: Float, y: Float): View? {
        var view = deepestAt(x, y)
        while (view != null && !view.isClickable) view = view.parent as? View
        return view?.takeIf { it.isEnabled && it !is WebView && it !is ScrollView }
    }

    /** Lights up and slightly lifts the one control being aimed at. */
    private fun show(next: View?) {
        if (next === hovered) return
        hovered?.let { old ->
            old.isHovered = false
            old.animate().cancel()
            if (Motion.level == 0) old.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
            else { old.scaleX = 1f; old.scaleY = 1f }
        }
        hovered = next
        if (next != null) {
            next.isHovered = true
            if (Motion.level == 0) next.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
        }
    }

    fun hover(u: Float, v: Float) {
        val next = clickableAt(u * width, v * height)
        if (next != null && next !== hovered) onHoverTarget()
        show(next)
    }

    fun hoverEnd() { if (!delivering) show(null) }

    private fun input(): Pair<View, InputConnection>? {
        val focused = root?.findFocus() ?: return null
        val connection = focused.onCreateInputConnection(EditorInfo()) ?: return null
        return focused to connection
    }

    /** Virtual keyboard entry. Returns false when nothing editable is focused. */
    fun type(text: String): Boolean = input()?.second?.commitText(text, 1) == true

    /** Replaces the word being typed with a suggestion. */
    fun replaceBeforeCursor(count: Int, text: String) {
        val connection = input()?.second ?: return
        connection.deleteSurroundingText(count, 0)
        connection.commitText(text, 1)
    }

    /** Only this app's own ordinary text fields; never passwords or web content. */
    fun canSuggest(): Boolean {
        val focused = root?.findFocus() as? android.widget.EditText ?: return false
        val variation = focused.inputType and android.text.InputType.TYPE_MASK_VARIATION
        return variation != android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            variation != android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    fun textBeforeCursor(): String = input()?.second?.getTextBeforeCursor(40, 0)?.toString().orEmpty()

    fun key(code: Int) {
        val focused = root?.findFocus() ?: return
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Suppress("UNCHECKED_CAST")
    fun navigate(url: String) { (app?.view?.tag as? (String) -> Unit)?.invoke(url) }

    fun menu(action: Int): Boolean = input()?.second?.performContextMenuAction(action) == true

    fun command(act: Act): Boolean = app?.command?.invoke(act) == true

    fun flush() { app?.flush?.invoke() }

    fun back(): Boolean = app?.back?.invoke() == true

    /** True when something that accepts text has the focus. */
    val hasTextFocus get() = root?.findFocus()?.let { it is android.widget.EditText || it is WebView } == true

    fun deleteWord(): Boolean {
        val connection = input()?.second ?: return false
        val before = connection.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        if (before.isEmpty()) return false
        val trimmed = before.trimEnd()
        val keep = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
        return connection.deleteSurroundingText(before.length - keep, 0)
    }

    fun clearText(): Boolean {
        val focused = root?.findFocus()
        if (focused is android.widget.EditText) { focused.text.clear(); return true }
        return menu(android.R.id.selectAll) && type("")
    }

    private fun scroller(view: View? = root): View? {
        if (view == null || view.visibility != View.VISIBLE) return null
        if (view is WebView || view is ScrollView || view is AbsListView) return view
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) scroller(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Scrolls by a share of the window height; [toEnd] jumps to the top or bottom instead. */
    fun scroll(direction: Int, toEnd: Boolean = false): Boolean {
        val view = scroller() ?: return false
        val amount = direction * (view.height * 0.7f).toInt()
        when (view) {
            is WebView -> if (toEnd) { if (direction < 0) view.pageUp(true) else view.pageDown(true) }
            else view.flingScroll(0, amount * 3)
            is ScrollView -> if (toEnd) view.fullScroll(if (direction < 0) View.FOCUS_UP else View.FOCUS_DOWN)
            else view.smoothScrollBy(0, amount)
            is AbsListView -> view.smoothScrollBy(amount, 300)
        }
        return true
    }

    /** Presses the visible control whose text or description is [label]. Reports through [done]. */
    fun clickLabel(label: String, done: (Boolean) -> Unit) {
        val wanted = VoiceCommands.normalize(label)
        if (wanted.isEmpty()) return done(false)
        val found = mutableListOf<Pair<Int, View>>()
        var web: WebView? = null
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || !view.isShown) return
            if (view is WebView) { web = view; return }
            val text = VoiceCommands.normalize(
                (view as? android.widget.TextView)?.text?.toString() ?: view.contentDescription?.toString().orEmpty()
            )
            if (text.isNotEmpty() && (text == wanted || wanted.length > 2 && text.startsWith(wanted))) {
                var target: View? = view
                while (target != null && !target.isClickable) target = target.parent as? View
                if (target != null) found += (if (text == wanted) 0 else 1) to target
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        root?.let(::visit)
        found.minByOrNull { it.first }?.let { it.second.performClick(); return done(true) }
        val page = web ?: return done(false)
        // The label is passed as a quoted string literal; the script only looks for it and clicks.
        val script = "(function(w){var l=document.querySelectorAll('a,button,input,[role=button],summary,label');" +
            "var best=null;for(var i=0;i<l.length;i++){var e=l[i];var r=e.getBoundingClientRect();if(!r.width||!r.height)continue;" +
            "var t=(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().toLowerCase();" +
            "if(t===w){best=e;break}if(!best&&w.length>2&&t.indexOf(w)===0)best=e}" +
            "if(!best)return false;best.focus();best.click();return true})(" + JSONObject.quote(label.trim().lowercase()) + ")"
        page.evaluateJavascript(script) { done(it == "true") }
    }

    /** A long press at a point, for the spoken "long press". */
    fun longPressAt(u: Float, v: Float): Boolean {
        var view: View? = deepestAt(u * width, v * height)
        while (view != null && !view.isLongClickable) view = view.parent as? View
        return view?.performLongClick() == true
    }

    fun release() {
        show(null)
        app?.dispose?.invoke()
        app = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
    }

    private companion object {
        const val DPI = 200
        /** Movement, in window pixels, before a held press becomes a drag. */
        const val SLOP = 32f
    }
}
