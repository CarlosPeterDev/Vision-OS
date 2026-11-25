package com.nexus.visionos.xr

import android.app.Activity
import android.app.Presentation
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.nexus.xr.TouchAction
import kotlin.math.hypot

/** Content of a spatial window. */
class SpatialApp(val view: View, val dispose: () -> Unit = {})

/**
 * A real Android view hierarchy rendered into a GPU texture: a private virtual display
 * hosts a Presentation. Used only for this app's own screens, never other apps.
 */
class SpatialSurface(
    private val activity: Activity,
    private val name: String,
    val width: Int,
    val height: Int,
    private val create: (android.content.Context) -> SpatialApp
) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var app: SpatialApp? = null
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

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
            val window = Presentation(activity, virtual.display, android.R.style.Theme_DeviceDefault_NoActionBar)
            window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            window.setCancelable(false)
            val content = create(window.context)
            window.setContentView(content.view)
            window.show()
            app = content
            presentation = window
            Log.i("NexusXR", "Spatial surface $name ready")
        } catch (e: RuntimeException) {
            Log.e("NexusXR", "Spatial surface $name failed", e)
        }
    }

    private val root: View? get() = presentation?.window?.decorView

    /** Forwards a pointer as a real touch. Small hand jitter is held still so taps register. */
    fun touch(action: TouchAction, u: Float, v: Float) {
        val target = root ?: return
        val now = SystemClock.uptimeMillis()
        var x = u * width
        var y = v * height
        when (action) {
            TouchAction.DOWN -> { downTime = now; downX = x; downY = y; dragging = false }
            else -> {
                if (!dragging && hypot(x - downX, y - downY) > SLOP) dragging = true
                if (!dragging) { x = downX; y = downY }
            }
        }
        val code = when (action) {
            TouchAction.DOWN -> MotionEvent.ACTION_DOWN
            TouchAction.MOVE -> MotionEvent.ACTION_MOVE
            TouchAction.UP -> MotionEvent.ACTION_UP
            TouchAction.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val event = MotionEvent.obtain(downTime, now, code, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun input(): Pair<View, InputConnection>? {
        val focused = root?.findFocus() ?: return null
        val connection = focused.onCreateInputConnection(EditorInfo()) ?: return null
        return focused to connection
    }

    /** Virtual keyboard entry. Returns false when nothing editable is focused. */
    fun type(text: String): Boolean = input()?.second?.commitText(text, 1) == true

    fun key(code: Int) {
        val focused = root?.findFocus() ?: return
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        focused.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Suppress("UNCHECKED_CAST")
    fun navigate(url: String) { (app?.view?.tag as? (String) -> Unit)?.invoke(url) }

    fun menu(action: Int) { input()?.second?.performContextMenuAction(action) }

    fun release() {
        app?.dispose?.invoke()
        app = null
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
    }

    private companion object {
        const val DPI = 200
        const val SLOP = 28f
    }
}
