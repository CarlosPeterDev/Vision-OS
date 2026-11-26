package com.nexus.xr

import kotlin.math.*

enum class AppType { HOME, BROWSER, NOTES, CALCULATOR, CLOCK, MEDIA, SETTINGS, ASSISTANT, KEYBOARD }

/** A flat window on a sphere around the user, always facing the centre. */
data class SpatialWindow(
    val id: Int,
    val app: AppType,
    val yaw: Float,
    val pitch: Float,
    val distance: Float,
    val width: Float,
    val height: Float,
    val scale: Float = 1f,
    val minimized: Boolean = false
) {
    val w get() = width * scale
    val h get() = height * scale
    val center get() = direction(yaw, pitch) * distance
    val right get() = Vec3(cos(yaw), 0f, sin(yaw))
    val up get() = Vec3(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
    fun point(u: Float, v: Float) = center + right * u + up * v

    companion object {
        const val BAR = 0.075f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.2f
    }
}

enum class Region { CONTENT, TITLE, CLOSE, MINIMIZE, FARTHER, NEARER, RESIZE }

/** [u] and [v] are content coordinates in [0,1] from the top left (clamped outside it). */
data class Hit(val windowId: Int, val region: Region, val t: Float, val u: Float, val v: Float)

/** Intersection of a ray with the window plane, as local metres from the centre. */
fun planeHit(ray: Ray, win: SpatialWindow): Triple<Float, Float, Float>? {
    val normal = direction(win.yaw, win.pitch)
    val denom = ray.direction dot normal
    if (denom <= 1e-4f) return null
    val t = ((win.center - ray.origin) dot normal) / denom
    if (t <= 0f) return null
    val p = ray.origin + ray.direction * t - win.center
    return Triple(t, p dot win.right, p dot win.up)
}

fun contentCoords(win: SpatialWindow, x: Float, y: Float) =
    Pair((x / win.w + 0.5f).coerceIn(0f, 1f), (0.5f - y / win.h).coerceIn(0f, 1f))

fun hitTest(ray: Ray, win: SpatialWindow): Hit? {
    val (t, x, y) = planeHit(ray, win) ?: return null
    val bar = SpatialWindow.BAR
    val halfW = win.w / 2
    val halfH = win.h / 2
    if (abs(x) > halfW) return null
    val region = when {
        abs(y) <= halfH -> Region.CONTENT
        y > halfH && y <= halfH + bar -> {
            // Square buttons from the right edge: close, minimize, farther, nearer.
            when (((halfW - x) / bar).toInt()) {
                0 -> Region.CLOSE
                1 -> Region.MINIMIZE
                2 -> Region.FARTHER
                3 -> Region.NEARER
                else -> Region.TITLE
            }
        }
        y < -halfH && y >= -halfH - bar && x >= halfW - bar * 3 -> Region.RESIZE
        else -> return null
    }
    val (u, v) = contentCoords(win, x, y)
    return Hit(win.id, region, t, u, v)
}

/** Window list in focus order: the last element is focused. Not thread safe. */
class WindowManager(private val maxWindows: Int = 6) {
    private val items = mutableListOf<SpatialWindow>()
    private var nextId = 1

    val windows: List<SpatialWindow> get() = items.toList()
    val focused: SpatialWindow? get() = items.lastOrNull { !it.minimized && it.app != AppType.KEYBOARD }
    fun get(id: Int) = items.firstOrNull { it.id == id }
    fun find(app: AppType) = items.firstOrNull { it.app == app }

    /** Opens or restores the single window of [app]. Returns evicted window ids through [onEvict]. */
    fun open(app: AppType, headYaw: Float, headPitch: Float = 0f, onEvict: (Int) -> Unit = {}): SpatialWindow {
        val eye = headPitch.coerceIn(-0.7f, 0.7f)
        find(app)?.let { existing ->
            val shown = if (existing.minimized) existing.copy(minimized = false, yaw = headYaw, pitch = restingPitch(app, eye))
            else existing
            replace(shown)
            focus(shown.id)
            return shown
        }
        if (items.size >= maxWindows) {
            val victim = items.firstOrNull { it.app != AppType.HOME && it.app != AppType.KEYBOARD } ?: items.first()
            close(victim.id)
            onEvict(victim.id)
        }
        val keyboard = app == AppType.KEYBOARD
        val (width, height) = when (app) {
            AppType.KEYBOARD -> 0.95f to 0.38f
            AppType.CALCULATOR -> 0.5f to 0.68f
            AppType.BROWSER, AppType.MEDIA -> 1.2f to 0.75f
            else -> 0.9f to 0.65f
        }
        // Fan new windows out around the current heading so they do not cover each other.
        val used = items.count { !it.minimized && it.app != AppType.KEYBOARD }
        val offset = if (keyboard) 0f else floatArrayOf(0f, 0.62f, -0.62f, 1.24f, -1.24f, 0f)[used % 6]
        val window = SpatialWindow(
            nextId++, app, headYaw + offset, restingPitch(app, eye),
            if (keyboard) 1.35f else 1.7f, width, height
        )
        items += window
        return window
    }

    /** The keyboard rests below the line of sight, everything else on it. */
    private fun restingPitch(app: AppType, eye: Float) = if (app == AppType.KEYBOARD) eye - 0.38f else eye

    fun replace(window: SpatialWindow) {
        val i = items.indexOfFirst { it.id == window.id }
        if (i >= 0) items[i] = window
    }

    fun focus(id: Int) {
        val window = get(id) ?: return
        items.remove(window)
        items += window
    }

    fun close(id: Int) { items.removeAll { it.id == id } }
    fun minimize(id: Int) { get(id)?.let { replace(it.copy(minimized = true)) } }

    /** Nearest visible window under the ray. Equal depths go to the one focused last. */
    fun pick(ray: Ray): Hit? {
        var best: Hit? = null
        for (w in items.asReversed()) {
            if (w.minimized) continue
            val hit = hitTest(ray, w) ?: continue
            if (best == null || hit.t < best.t - 0.01f) best = hit
        }
        return best
    }

    /** Lays visible windows side by side around [headYaw]. */
    fun arrange(headYaw: Float, headPitch: Float = 0f) {
        val eye = headPitch.coerceIn(-0.7f, 0.7f)
        val visible = items.filter { !it.minimized && it.app != AppType.KEYBOARD }
        visible.forEachIndexed { i, w ->
            val slot = if (i % 2 == 0) i / 2 else -(i + 1) / 2
            replace(w.copy(yaw = headYaw + slot * 0.68f, pitch = eye, distance = 1.75f, scale = 1f))
        }
        find(AppType.KEYBOARD)?.let { replace(it.copy(yaw = headYaw, pitch = eye - 0.38f, distance = 1.35f, scale = 1f)) }
    }
}
