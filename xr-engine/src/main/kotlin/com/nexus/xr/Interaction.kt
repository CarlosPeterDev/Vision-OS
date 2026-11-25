package com.nexus.xr

import kotlin.math.*

enum class TouchAction { DOWN, MOVE, UP, CANCEL }

interface InteractionListener {
    fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float)
    fun onClosed(windowId: Int)
}

/**
 * Turns rays plus pointer phases into window operations. Each pointer captures its target
 * from DOWN until UP or CANCEL, even when the ray leaves the window.
 */
class InteractionController(private val windows: WindowManager, private val listener: InteractionListener) {
    private sealed interface Capture { val windowId: Int }
    private data class Content(override val windowId: Int, var u: Float, var v: Float) : Capture
    private data class Drag(override val windowId: Int, val yawOffset: Float, val pitchOffset: Float) : Capture
    private data class Resize(override val windowId: Int, val startRadius: Float, val startScale: Float) : Capture
    private data class Button(override val windowId: Int, val region: Region) : Capture
    private data class TwoHand(override val windowId: Int, val startSeparation: Float, val startScale: Float) : Capture

    private val captures = mutableMapOf<Int, Capture>()
    private val rays = mutableMapOf<Int, Ray>()

    fun isCapturing(pointer: Int) = pointer in captures

    fun handle(pointer: Int, phase: PointerPhase, ray: Ray) {
        rays[pointer] = ray
        when (phase) {
            PointerPhase.HOVER -> {}
            PointerPhase.DOWN -> down(pointer, ray)
            PointerPhase.MOVE -> move(pointer, ray)
            PointerPhase.UP -> release(pointer, ray, cancelled = false)
            PointerPhase.CANCEL -> release(pointer, ray, cancelled = true)
        }
    }

    private fun down(pointer: Int, ray: Ray) {
        if (pointer in captures) release(pointer, ray, cancelled = true)
        // A second pinch while another hand drags a window starts two-hand scaling.
        val dragging = captures.entries.firstOrNull { it.value is Drag }
        if (dragging != null) {
            val win = windows.get(dragging.value.windowId) ?: return
            val other = rays[dragging.key] ?: return
            captures.remove(dragging.key)
            captures[pointer] = TwoHand(win.id, separation(other, ray).coerceAtLeast(0.02f), win.scale)
            captures[dragging.key] = captures.getValue(pointer)
            return
        }
        val hit = windows.pick(ray) ?: return
        // One pointer per window keeps touch streams well formed.
        if (captures.values.any { it.windowId == hit.windowId }) return
        windows.focus(hit.windowId)
        val win = windows.get(hit.windowId) ?: return
        captures[pointer] = when (hit.region) {
            Region.CONTENT -> {
                listener.onContentTouch(win.id, TouchAction.DOWN, hit.u, hit.v)
                Content(win.id, hit.u, hit.v)
            }
            Region.TITLE -> Drag(win.id, wrap(win.yaw - yaw(ray)), win.pitch - pitch(ray))
            Region.RESIZE -> Resize(win.id, radius(ray, win).coerceAtLeast(0.05f), win.scale)
            else -> Button(win.id, hit.region)
        }
    }

    private fun move(pointer: Int, ray: Ray) {
        val capture = captures[pointer] ?: return
        val win = windows.get(capture.windowId) ?: run { captures.remove(pointer); return }
        when (capture) {
            is Content -> {
                val (_, x, y) = planeHit(ray, win) ?: return
                val (u, v) = contentCoords(win, x, y)
                capture.u = u
                capture.v = v
                listener.onContentTouch(win.id, TouchAction.MOVE, u, v)
            }
            is Drag -> windows.replace(
                win.copy(
                    yaw = yaw(ray) + capture.yawOffset,
                    pitch = (pitch(ray) + capture.pitchOffset).coerceIn(-1.1f, 1.1f)
                )
            )
            is Resize -> windows.replace(win.copy(scale = clampScale(capture.startScale * radius(ray, win) / capture.startRadius)))
            is TwoHand -> {
                val others = captures.filter { it.value === capture }.keys.mapNotNull { rays[it] }
                if (others.size == 2) windows.replace(
                    win.copy(scale = clampScale(capture.startScale * separation(others[0], others[1]) / capture.startSeparation))
                )
            }
            is Button -> {}
        }
    }

    private fun release(pointer: Int, ray: Ray, cancelled: Boolean) {
        val capture = captures.remove(pointer) ?: return
        when (capture) {
            is Content -> listener.onContentTouch(
                capture.windowId, if (cancelled) TouchAction.CANCEL else TouchAction.UP, capture.u, capture.v
            )
            is TwoHand -> captures.entries.removeAll { it.value === capture }
            is Button -> {
                // Like a normal button: it only fires when released on the same target.
                val hit = windows.pick(ray)
                if (!cancelled && hit?.windowId == capture.windowId && hit.region == capture.region) press(capture)
            }
            else -> {}
        }
    }

    private fun press(button: Button) {
        val win = windows.get(button.windowId) ?: return
        when (button.region) {
            Region.CLOSE -> { windows.close(win.id); listener.onClosed(win.id) }
            Region.MINIMIZE -> windows.minimize(win.id)
            Region.NEARER -> windows.replace(win.copy(distance = (win.distance - 0.2f).coerceAtLeast(0.9f)))
            Region.FARTHER -> windows.replace(win.copy(distance = (win.distance + 0.2f).coerceAtMost(3.5f)))
            else -> {}
        }
    }

    fun cancelAll() {
        captures.keys.toList().forEach { release(it, rays[it] ?: Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f)), true) }
    }

    private fun yaw(r: Ray) = atan2(r.direction.x, -r.direction.z)
    private fun pitch(r: Ray) = asin(r.direction.y.coerceIn(-1f, 1f))
    private fun wrap(a: Float) = atan2(sin(a), cos(a))
    private fun separation(a: Ray, b: Ray) = (a.direction - b.direction).length
    private fun radius(r: Ray, w: SpatialWindow) = planeHit(r, w)?.let { hypot(it.second, it.third) } ?: 0f
    private fun clampScale(s: Float) = s.coerceIn(SpatialWindow.MIN_SCALE, SpatialWindow.MAX_SCALE)
}
