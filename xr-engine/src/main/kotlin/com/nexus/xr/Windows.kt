package com.nexus.xr

import kotlin.math.*

enum class AppType {
    HOME, BROWSER, NOTES, CALCULATOR, CLOCK, MEDIA, SETTINGS, ASSISTANT, KEYBOARD,
    CENTER, TUTORIAL, PHOTOS, FILES, CAMERA, LAUNCHER,
    TASKS, CALENDAR, PAINT, CONVERTER, GAMES, WEATHER, VOICE
}

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
    val minimized: Boolean = false,
    /** Bent around the viewer like a cinema screen. */
    val curved: Boolean = false
) {
    val w get() = width * scale
    val h get() = height * scale
    val center get() = direction(yaw, pitch) * distance
    val right get() = Vec3(cos(yaw), 0f, sin(yaw))
    val up get() = Vec3(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
    val forward get() = direction(yaw, pitch)
    fun point(u: Float, v: Float): Vec3 {
        if (!curved) return center + right * u + up * v
        // A cylinder of radius [distance] around the viewer; [u] is arc length.
        val angle = u / distance
        return (forward * cos(angle) + right * sin(angle)) * distance + up * v
    }

    companion object {
        const val BAR = 0.075f
        /** Distance from the content's lower edge to the middle of the control row. */
        const val CONTROL_DROP = 0.062f
        const val CONTROL_RADIUS = 0.032f
        const val CONTROL_GAP = 0.03f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.2f
        /** Limits for a window's own width and height in metres, before scaling. */
        const val MIN_SIDE = 0.4f
        const val MAX_SIDE = 2.0f
    }
}

enum class Region { CONTENT, TITLE, GRAB, CLOSE, MINIMIZE, FARTHER, NEARER, RESIZE }

/** [u] and [v] are content coordinates in [0,1] from the top left (clamped outside it). */
data class Hit(val windowId: Int, val region: Region, val t: Float, val u: Float, val v: Float)

/** One item under a window, centred at ([x], [y]) in window-local metres. */
data class Control(val region: Region, val x: Float, val y: Float, val halfWidth: Float, val halfHeight: Float)

/**
 * The separated controls under a window: close, minimize, grab bar, farther and nearer, with a
 * gap between each. The resize corner sits clear of them. Drawing and hit testing share this.
 */
fun controlLayout(win: SpatialWindow): List<Control> {
    val r = SpatialWindow.CONTROL_RADIUS
    val gap = SpatialWindow.CONTROL_GAP
    val grabHalf = (win.w * 0.15f).coerceIn(0.06f, 0.13f)
    val y = -win.h / 2 - SpatialWindow.CONTROL_DROP
    val first = grabHalf + gap + r
    val second = first + 2 * r + gap
    val resizeX = maxOf(win.w / 2, second + r + gap + 0.035f)
    return listOf(
        Control(Region.CLOSE, -second, y, r, r),
        Control(Region.MINIMIZE, -first, y, r, r),
        Control(Region.GRAB, 0f, y, grabHalf, r),
        Control(Region.FARTHER, first, y, r, r),
        Control(Region.NEARER, second, y, r, r),
        Control(Region.RESIZE, resizeX, y + 0.012f, 0.035f, 0.045f)
    )
}

/** Where a ray meets a window: distance along the ray, then local metres from the centre. */
fun planeHit(ray: Ray, win: SpatialWindow): Triple<Float, Float, Float>? {
    if (win.curved) {
        // The cylinder is centred on the viewer, so the ray direction decides the arc position.
        val a = ray.direction dot win.right
        val b = ray.direction dot win.up
        val c = ray.direction dot win.forward
        val flat = hypot(a, c)
        if (flat < 1e-4f || c <= 0f) return null
        val t = win.distance / flat
        return Triple(t, atan2(a, c) * win.distance, t * b)
    }
    val normal = win.forward
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
    val halfW = win.w / 2
    val halfH = win.h / 2
    val region = when {
        abs(x) <= halfW && abs(y) <= halfH -> Region.CONTENT
        abs(x) <= halfW && y > halfH && y <= halfH + SpatialWindow.BAR -> Region.TITLE
        y < -halfH -> {
            // Hit areas reach into half of each gap, which forgives an unsteady hand.
            val slack = SpatialWindow.CONTROL_GAP * 0.45f
            controlLayout(win).firstOrNull {
                abs(x - it.x) <= it.halfWidth + slack && abs(y - it.y) <= it.halfHeight + slack
            }?.region ?: return null
        }
        else -> return null
    }
    val (u, v) = contentCoords(win, x, y)
    return Hit(win.id, region, t, u, v)
}

/**
 * Saves window arrangements relative to the viewing direction, so they come back in the same
 * shape wherever the user faces next time.
 */
object LayoutCodec {
    fun encode(windows: List<SpatialWindow>, headYaw: Float, headPitch: Float): String =
        windows.filter { !it.minimized }.joinToString(";") {
            val yaw = atan2(sin(it.yaw - headYaw), cos(it.yaw - headYaw))
            "${it.app.name},$yaw,${it.pitch - headPitch},${it.distance},${it.scale},${it.curved},${it.width},${it.height}"
        }

    fun decode(text: String, headYaw: Float, headPitch: Float): List<SpatialWindow> =
        text.split(';').mapNotNull { item ->
            val p = item.split(',')
            if (p.size != 6 && p.size != 8) return@mapNotNull null
            val app = AppType.entries.firstOrNull { it.name == p[0] } ?: return@mapNotNull null
            val n = (1..4).map { p[it].toFloatOrNull()?.takeIf { v -> v.isFinite() } ?: return@mapNotNull null }
            SpatialWindow(
                0, app, headYaw + n[0], (headPitch + n[1]).coerceIn(-1.1f, 1.1f), n[2].coerceIn(0.9f, 3.5f),
                side(p.getOrNull(6)), side(p.getOrNull(7)),
                n[3].coerceIn(SpatialWindow.MIN_SCALE, SpatialWindow.MAX_SCALE), curved = p[5] == "true"
            )
        }.distinctBy { it.app }

    /** Zero means "use the app's normal size". */
    private fun side(text: String?) = text?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(SpatialWindow.MIN_SIDE, SpatialWindow.MAX_SIDE) ?: 0f
}

/** Window list in focus order: the last element is focused. Not thread safe. */
class WindowManager(private val maxWindows: Int = 6) {
    private val items = mutableListOf<SpatialWindow>()
    private var nextId = 1

    /** How far away new and rearranged windows are placed, in metres. */
    var defaultDistance = 1.7f

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
        val (width, height) = sizeOf(app)
        // Fan new windows out around the current heading so they do not cover each other.
        val used = items.count { !it.minimized && it.app != AppType.KEYBOARD }
        val offset = if (keyboard) 0f else floatArrayOf(0f, 0.62f, -0.62f, 1.24f, -1.24f, 0f)[used % 6]
        val window = SpatialWindow(
            nextId++, app, headYaw + offset, restingPitch(app, eye),
            if (keyboard) 1.35f else defaultDistance, width, height
        )
        items += window
        return window
    }

    private fun sizeOf(app: AppType) = when (app) {
        AppType.KEYBOARD -> 0.95f to 0.42f
        AppType.CALCULATOR -> 0.55f to 0.72f
        AppType.BROWSER, AppType.MEDIA, AppType.PHOTOS, AppType.FILES, AppType.PAINT -> 1.2f to 0.75f
        AppType.GAMES -> 0.7f to 0.75f
        else -> 0.9f to 0.65f
    }

    /** Reopens saved windows where they were; the keyboard is never restored. */
    fun restore(saved: List<SpatialWindow>) {
        for (want in saved.filter { it.app != AppType.KEYBOARD }.take(maxWindows)) {
            val window = find(want.app) ?: open(want.app, want.yaw, want.pitch)
            replace(
                window.copy(
                    yaw = want.yaw, pitch = want.pitch, distance = want.distance,
                    width = if (want.width > 0f) want.width else window.width,
                    height = if (want.height > 0f) want.height else window.height,
                    scale = want.scale, curved = want.curved, minimized = false
                )
            )
        }
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
            replace(w.copy(yaw = headYaw + slot * 0.68f, pitch = eye, distance = defaultDistance + 0.05f, scale = 1f, curved = false))
        }
        find(AppType.KEYBOARD)?.let { replace(it.copy(yaw = headYaw, pitch = eye - 0.38f, distance = 1.35f, scale = 1f)) }
    }
}
