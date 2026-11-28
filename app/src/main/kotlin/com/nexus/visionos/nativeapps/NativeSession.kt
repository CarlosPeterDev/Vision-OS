package com.nexus.visionos.nativeapps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.nexus.xr.NativeApps
import com.nexus.xr.PressAction
import com.nexus.xr.PressGate
import com.nexus.xr.TouchAction
import java.util.concurrent.Executors

enum class NativeProblem { NOT_READY, NO_DISPLAY, LAUNCH_REFUSED, INPUT_BLOCKED, CONNECTION_LOST }

/**
 * One Android app on one private display, drawn into a Nexus window's texture. Public functions
 * are for the main thread; calls to the service run in order on their own thread.
 */
class NativeSession(
    private val context: Context,
    val packageName: String,
    width: Int,
    height: Int,
    private val longPressMs: () -> Long,
    private val onProblem: (NativeProblem) -> Unit
) {
    var width = width
        private set
    var height = height
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var service: INativeDisplayService? = null
    @Volatile private var displayId = -1
    private var surface: Surface? = null
    private var closed = false
    private var connecting = false
    private val gate = PressGate(SLOP)
    private var downTime = 0L
    private var blockedToldAt = 0L

    private val lost: () -> Unit = {
        if (!closed) {
            service = null
            displayId = -1
            onProblem(NativeProblem.CONNECTION_LOST)
        }
    }

    init { ShizukuLink.lostListeners += lost }

    private fun report(problem: NativeProblem) = main.post { if (!closed) onProblem(problem) }

    /** Gives the session a place to draw: first time it opens the app; later it only moves the picture. */
    fun attach(target: Surface) {
        if (closed || !target.isValid) return
        surface = target
        val current = service
        if (current != null) {
            val w = width
            val h = height
            worker.execute {
                if (displayId >= 0) runCatching { current.resize(displayId, w, h, NativeApps.density(w, h), target) }
                    .onFailure { Log.e(TAG, "resize failed", it); report(NativeProblem.NO_DISPLAY) }
            }
            return
        }
        if (connecting) return
        connecting = true
        ShizukuLink.connect(context) { connected ->
            connecting = false
            val drawOn = surface
            if (closed || drawOn == null) return@connect
            if (connected == null) return@connect onProblem(NativeProblem.NOT_READY)
            service = connected
            val w = width
            val h = height
            worker.execute { open(connected, drawOn, w, h) }
        }
    }

    private fun open(on: INativeDisplayService, target: Surface, w: Int, h: Int) {
        try {
            displayId = on.create(target, w, h, NativeApps.density(w, h))
        } catch (e: Exception) {
            Log.e(TAG, "create failed", e)
            report(NativeProblem.NO_DISPLAY)
            return
        }
        val reason = runCatching { on.launch(displayId, packageName) }.getOrElse { it.toString() }
        if (reason.isEmpty()) Log.i(TAG, "native app open on display $displayId")
        else {
            Log.w(TAG, "launch failed: $reason")
            report(NativeProblem.LAUNCH_REFUSED)
        }
    }

    /** Changes the app's own screen size. [resizeTexture] runs first. */
    fun resize(newWidth: Int, newHeight: Int, resizeTexture: () -> Unit) {
        cancelTouch()
        width = newWidth
        height = newHeight
        resizeTexture()
        surface?.let(::attach)
    }

    private fun call(work: (INativeDisplayService, Int) -> Boolean, blockedMatters: Boolean) {
        val current = service ?: return
        worker.execute {
            if (displayId < 0) return@execute
            val sent = runCatching { work(current, displayId) }.onFailure { Log.w(TAG, "input failed", it) }.getOrDefault(false)
            val now = SystemClock.uptimeMillis()
            if (!sent && blockedMatters && now - blockedToldAt > 5000) {
                blockedToldAt = now
                report(NativeProblem.INPUT_BLOCKED)
            }
        }
    }

    private fun motion(action: Int, x: Float, y: Float) {
        val started = downTime
        call({ s, id -> s.touch(id, action, x, y, started) }, action == MotionEvent.ACTION_DOWN)
    }

    /** A pointer on the window, through the same gate that separates taps from long presses. */
    fun touch(action: TouchAction, u: Float, v: Float) {
        val x = NativeApps.pixel(u, width)
        val y = NativeApps.pixel(v, height)
        val now = SystemClock.uptimeMillis()
        val events = when (action) {
            TouchAction.DOWN -> { gate.longPressMs = longPressMs(); gate.down(x, y, now) }
            TouchAction.MOVE -> gate.move(x, y, now)
            TouchAction.UP -> gate.up(x, y)
            TouchAction.CANCEL -> gate.cancel()
        }
        for (event in events) when (event.action) {
            PressAction.DOWN -> { downTime = SystemClock.uptimeMillis(); motion(MotionEvent.ACTION_DOWN, event.x, event.y) }
            PressAction.MOVE -> motion(MotionEvent.ACTION_MOVE, event.x, event.y)
            PressAction.UP -> motion(MotionEvent.ACTION_UP, event.x, event.y)
            PressAction.CANCEL -> motion(MotionEvent.ACTION_CANCEL, event.x, event.y)
            // The finger stays down, so the app sees its own long press.
            PressAction.LONG_PRESS -> {}
        }
    }

    private fun cancelTouch() = gate.cancel().forEach { motion(MotionEvent.ACTION_CANCEL, it.x, it.y) }

    fun key(code: Int, meta: Int = 0) {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            call({ s, id -> s.key(id, action, code, meta) }, action == KeyEvent.ACTION_DOWN)
        }
    }

    fun back() = key(KeyEvent.KEYCODE_BACK)

    /** Types as key presses. Returns false for text a key map cannot spell, such as Arabic. */
    fun type(text: String): Boolean {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return false
        for (e in events) call({ s, id -> s.key(id, e.action, e.keyCode, e.metaState) }, false)
        return true
    }

    /** Removes the display. The app itself is not force-stopped and keeps its data. */
    fun release() {
        if (closed) return
        cancelTouch()
        closed = true
        ShizukuLink.lostListeners -= lost
        val current = service
        service = null
        worker.execute {
            if (current != null && displayId >= 0) runCatching { current.release(displayId) }
            displayId = -1
        }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "NexusNative"
        const val SLOP = 24f
    }
}
