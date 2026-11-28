package com.nexus.xr

import kotlin.math.*

enum class TouchAction { DOWN, MOVE, UP, CANCEL }

interface InteractionListener {
    fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float)
    fun onClosed(windowId: Int)
    /** A free pointer is over a window's content. */
    fun onContentHover(windowId: Int, u: Float, v: Float) {}
    fun onHoverEnd(windowId: Int) {}
    /** Something was pressed: content, a control or a drag handle. */
    fun onPressed(region: Region) {}
    /** A drag or resize that really changed the window was released. */
    fun onAdjusted(region: Region) {}
}

/**
 * Turns rays plus pointer phases into window operations. Each pointer captures its target
 * from DOWN until UP or CANCEL, even when the ray leaves the window.
 */
class InteractionController(private val windows: WindowManager, private val listener: InteractionListener) {
    private sealed interface Capture { val windowId: Int }
    private data class Content(override val windowId: Int, var u: Float, var v: Float) : Capture
    private data class Drag(
        override val windowId: Int, val yawOffset: Float, val pitchOffset: Float, val startYaw: Float, val startPitch: Float
    ) : Capture
    private data class Resize(override val windowId: Int, val startRadius: Float, val startScale: Float) : Capture
    /** Free resize: the corner follows the ray, so width and height change separately. */
    private data class Stretch(
        override val windowId: Int, val startX: Float, val startY: Float, val startWidth: Float, val startHeight: Float
    ) : Capture
    private data class Button(override val windowId: Int, val region: Region) : Capture
    private data class TwoHand(override val windowId: Int, val startSeparation: Float, val startScale: Float) : Capture

    private val captures = mutableMapOf<Int, Capture>()
    private val rays = mutableMapOf<Int, Ray>()

    private val hovers = mutableMapOf<Int, Hit>()

    /** When set, the resize corner changes width and height independently. */
    var freeResize = false

    fun isCapturing(pointer: Int) = pointer in captures
    fun isHeld(windowId: Int) = captures.values.any { it.windowId == windowId }

    /** What each free pointer is aiming at, for highlighting. */
    fun hovered(): Collection<Hit> = hovers.values

    private fun hover(pointer: Int, hit: Hit?) {
        val previous = if (hit == null) hovers.remove(pointer) else hovers.put(pointer, hit)
        val leftContent = previous?.region == Region.CONTENT &&
            (hit?.region != Region.CONTENT || hit.windowId != previous.windowId)
        if (leftContent) listener.onHoverEnd(previous!!.windowId)
        if (hit?.region == Region.CONTENT) listener.onContentHover(hit.windowId, hit.u, hit.v)
    }

    fun handle(pointer: Int, phase: PointerPhase, ray: Ray) {
        rays[pointer] = ray
        when (phase) {
            PointerPhase.HOVER -> if (pointer !in captures) hover(pointer, windows.pick(ray))
            PointerPhase.DOWN -> { hover(pointer, null); down(pointer, ray) }
            PointerPhase.MOVE -> move(pointer, ray)
            PointerPhase.UP -> release(pointer, ray, cancelled = false)
            PointerPhase.CANCEL -> { hover(pointer, null); release(pointer, ray, cancelled = true) }
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
        // A window grabbed while still animating is picked up where it is seen, so it does not jump.
        if (hit.region == Region.TITLE || hit.region == Region.GRAB) {
            windows.get(hit.windowId)?.let {
                val seen = windows.onScreen(it)
                windows.replace(it.copy(yaw = seen.yaw, pitch = seen.pitch, distance = seen.distance, anchor = seen.anchor))
            }
        }
        // A grabbed window turns to face the viewer, wherever they have walked, then follows the ray.
        if (hit.region == Region.TITLE || hit.region == Region.GRAB) {
            windows.get(hit.windowId)?.let { windows.replace(facing(it, ray.origin)) }
        }
        val win = windows.get(hit.windowId) ?: return
        listener.onPressed(hit.region)
        captures[pointer] = when (hit.region) {
            Region.CONTENT -> {
                listener.onContentTouch(win.id, TouchAction.DOWN, hit.u, hit.v)
                Content(win.id, hit.u, hit.v)
            }
            Region.TITLE, Region.GRAB -> Drag(win.id, wrap(win.yaw - yaw(ray)), win.pitch - pitch(ray), win.yaw, win.pitch)
            Region.RESIZE -> {
                val point = if (freeResize) planeHit(ray, win) else null
                if (point != null) Stretch(win.id, point.second, point.third, win.width, win.height)
                else Resize(win.id, radius(ray, win).coerceAtLeast(0.05f), win.scale)
            }
            else -> Button(win.id, hit.region)
        }
    }

    private fun move(pointer: Int, ray: Ray) {
        val capture = captures[pointer] ?: return
        val win = windows.get(capture.windowId) ?: run { captures.remove(pointer); return }
        when (capture) {
            is Content -> {
                val seen = windows.onScreen(win)
                val (_, x, y) = planeHit(ray, seen) ?: return
                val (u, v) = contentCoords(seen, x, y)
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
            is Stretch -> {
                val (_, x, y) = planeHit(ray, win) ?: return
                // The window grows around its centre, so each edge moves with the corner.
                windows.replace(
                    win.copy(
                        width = side(capture.startWidth + 2 * (x - capture.startX) / win.scale),
                        height = side(capture.startHeight - 2 * (y - capture.startY) / win.scale)
                    )
                )
            }
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
            is Drag -> windows.get(capture.windowId)?.let {
                if (!cancelled && abs(wrap(it.yaw - capture.startYaw)) + abs(it.pitch - capture.startPitch) > 0.08f)
                    listener.onAdjusted(Region.GRAB)
            }
            is Stretch -> windows.get(capture.windowId)?.let {
                if (!cancelled && abs(it.width - capture.startWidth) + abs(it.height - capture.startHeight) > 0.08f)
                    listener.onAdjusted(Region.RESIZE)
            }
            is Resize -> windows.get(capture.windowId)?.let {
                if (!cancelled && abs(it.scale - capture.startScale) > 0.08f) listener.onAdjusted(Region.RESIZE)
            }
        }
    }

    /** The window a held pointer is working on. */
    fun capturedWindow(pointer: Int): Int? = captures[pointer]?.windowId

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

    /** Forgets pointers that are gone, so no highlight stays behind. */
    fun retainHovers(alive: Set<Int>) = hovers.keys.filter { it !in alive }.forEach { hover(it, null) }

    fun cancelAll() {
        retainHovers(emptySet())
        captures.keys.toList().forEach { release(it, rays[it] ?: Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f)), true) }
    }

    /** The same spot in the room, described from [viewer], so dragging circles around that point. */
    private fun facing(win: SpatialWindow, viewer: Vec3): SpatialWindow {
        if ((viewer - win.anchor).length < 0.05f) return win
        val to = win.center - viewer
        val distance = to.length
        if (distance < 0.3f) return win
        val way = to * (1f / distance)
        return win.copy(
            anchor = viewer, distance = distance,
            yaw = atan2(way.x, -way.z), pitch = asin(way.y.coerceIn(-1f, 1f))
        )
    }

    private fun yaw(r: Ray) = atan2(r.direction.x, -r.direction.z)
    private fun pitch(r: Ray) = asin(r.direction.y.coerceIn(-1f, 1f))
    private fun wrap(a: Float) = atan2(sin(a), cos(a))
    private fun separation(a: Ray, b: Ray) = (a.direction - b.direction).length
    private fun radius(r: Ray, w: SpatialWindow) = planeHit(r, w)?.let { hypot(it.second, it.third) } ?: 0f
    private fun side(s: Float) = s.coerceIn(SpatialWindow.MIN_SIDE, SpatialWindow.MAX_SIDE)
    private fun clampScale(s: Float) = s.coerceIn(SpatialWindow.MIN_SCALE, SpatialWindow.MAX_SCALE)
}
