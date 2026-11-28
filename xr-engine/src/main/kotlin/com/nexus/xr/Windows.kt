package com.nexus.xr

import kotlin.math.*

enum class AppType {
    HOME, BROWSER, NOTES, CALCULATOR, CLOCK, MEDIA, SETTINGS, ASSISTANT, KEYBOARD,
    CENTER, TUTORIAL, PHOTOS, FILES, CAMERA, FIT, SPACES, PRACTICE, CHECKUP,
    TASKS, CALENDAR, PAINT, CONVERTER, GAMES, WEATHER, VOICE,
    /** The list of installed Android apps, and the window one of them runs in. */
    NATIVE_APPS, NATIVE
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
    val curved: Boolean = false,
    /** The middle of the sphere the window sits on: where the viewer stood when it was placed. */
    val anchor: Vec3 = Vec3(0f, 0f, 0f)
) {
    val w get() = width * scale
    val h get() = height * scale
    val center get() = anchor + direction(yaw, pitch) * distance
    val right get() = Vec3(cos(yaw), 0f, sin(yaw))
    val up get() = Vec3(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
    val forward get() = direction(yaw, pitch)
    fun point(u: Float, v: Float): Vec3 {
        if (!curved) return center + right * u + up * v
        // A cylinder of radius [distance] around the viewer; [u] is arc length.
        val angle = u / distance
        return anchor + (forward * cos(angle) + right * sin(angle)) * distance + up * v
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

/** BACK exists only on a window running another Android app. */
enum class Region { CONTENT, TITLE, GRAB, CLOSE, MINIMIZE, FARTHER, NEARER, RESIZE, BACK }

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
    val back = if (win.app == AppType.NATIVE) listOf(Control(Region.BACK, -(second + 2 * r + gap), y, r, r)) else emptyList()
    return back + listOf(
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
        // A cylinder around the window's anchor; the viewer may have walked away from its middle.
        val from = ray.origin - win.anchor
        val a = ray.direction dot win.right
        val b = ray.direction dot win.up
        val c = ray.direction dot win.forward
        val ox = from dot win.right
        val oy = from dot win.up
        val oz = from dot win.forward
        val flat = a * a + c * c
        if (flat < 1e-8f) return null
        val half = ox * a + oz * c
        val under = half * half - flat * (ox * ox + oz * oz - win.distance * win.distance)
        if (under < 0f) return null
        val t = (-half + sqrt(under)) / flat
        val z = oz + t * c
        if (t <= 0f || z <= 0f) return null
        return Triple(t, atan2(ox + t * a, z) * win.distance, oy + t * b)
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
 * The order windows are painted in, farthest first. The renderer has no depth test, so the last
 * window painted is the one seen where two overlap. Distances are compared in whole centimetres, so
 * windows at the same distance keep the given (focus) order instead of rounding noise deciding.
 * Drawing and picking both use this.
 */
fun paintOrder(windows: List<SpatialWindow>, eye: Vec3): List<SpatialWindow> =
    windows.sortedByDescending { ((it.center - eye).length * 100f).roundToInt() }

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
            val app = AppType.entries.firstOrNull { it.name == p[0] && it != AppType.NATIVE } ?: return@mapNotNull null
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

    /** Where the viewer is now. New, restored and rearranged windows are placed around this point. */
    var viewer = Vec3(0f, 0f, 0f)

    /** Moves every window along, for when the world under them shifts. */
    fun shift(by: Vec3) {
        for (i in items.indices) items[i] = items[i].copy(anchor = items[i].anchor + by)
    }

    /** How far away new and rearranged windows are placed, in metres. */
    var defaultDistance = 1.7f

    val windows: List<SpatialWindow> get() = items.toList()
    val focused: SpatialWindow? get() = items.lastOrNull { !it.minimized && it.app != AppType.KEYBOARD }
    fun get(id: Int) = items.firstOrNull { it.id == id }

    private var presented = emptyMap<Int, SpatialWindow>()

    /** Records the geometry in the frame on screen, which can lag the targets while animating. */
    fun present(frame: List<SpatialWindow>) { presented = frame.associateBy { it.id } }

    /** [window] where the user currently sees it; a window not drawn yet is at its target. */
    fun onScreen(window: SpatialWindow) = presented[window.id] ?: window
    fun find(app: AppType) = items.firstOrNull { it.app == app }

    /** Opens or restores the single window of [app]. Returns evicted window ids through [onEvict]. */
    fun open(app: AppType, headYaw: Float, headPitch: Float = 0f, onEvict: (Int) -> Unit = {}): SpatialWindow {
        val eye = headPitch.coerceIn(-0.7f, 0.7f)
        find(app)?.let { existing ->
            val shown = if (existing.minimized) {
                existing.copy(minimized = false, yaw = headYaw, pitch = restingPitch(app, eye), anchor = viewer)
            }
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
            if (keyboard) 1.35f else defaultDistance, width, height, anchor = viewer
        )
        items += window
        return window
    }

    private fun sizeOf(app: AppType) = when (app) {
        AppType.KEYBOARD -> 0.95f to 0.42f
        AppType.CALCULATOR -> 0.55f to 0.72f
        AppType.BROWSER, AppType.MEDIA, AppType.PHOTOS, AppType.FILES, AppType.PAINT -> 1.2f to 0.75f
        AppType.GAMES -> 0.7f to 0.75f
        // Most phone apps are drawn for a tall screen.
        AppType.NATIVE -> 0.62f to 1.0f
        AppType.PRACTICE, AppType.CHECKUP -> 0.9f to 0.78f
        else -> 0.9f to 0.65f
    }

    /** Reopens saved windows where they were; the keyboard is never restored. */
    fun restore(saved: List<SpatialWindow>) {
        // Another app's window is never reopened by itself: which app it ran is not saved.
        for (want in saved.filter { it.app != AppType.KEYBOARD && it.app != AppType.NATIVE }.take(maxWindows)) {
            val window = find(want.app) ?: open(want.app, want.yaw, want.pitch)
            replace(
                window.copy(
                    yaw = want.yaw, pitch = want.pitch, distance = want.distance,
                    width = if (want.width > 0f) want.width else window.width,
                    height = if (want.height > 0f) want.height else window.height,
                    scale = want.scale, curved = want.curved, minimized = false, anchor = viewer
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

    /** The window seen under the ray: closing and minimized windows take no input. */
    fun pick(ray: Ray): Hit? {
        val shown = items.filter { !it.minimized }.map { onScreen(it) }
        return paintOrder(shown, ray.origin).asReversed().firstNotNullOfOrNull { hitTest(ray, it) }
    }

    /** Lays visible windows side by side around [headYaw]. */
    fun arrange(headYaw: Float, headPitch: Float = 0f) {
        val eye = headPitch.coerceIn(-0.7f, 0.7f)
        val visible = items.filter { !it.minimized && it.app != AppType.KEYBOARD }
        visible.forEachIndexed { i, w ->
            val slot = if (i % 2 == 0) i / 2 else -(i + 1) / 2
            replace(
                w.copy(
                    yaw = headYaw + slot * 0.68f, pitch = eye, distance = defaultDistance + 0.05f, scale = 1f,
                    curved = false, anchor = viewer
                )
            )
        }
        find(AppType.KEYBOARD)?.let {
            replace(it.copy(yaw = headYaw, pitch = eye - 0.38f, distance = 1.35f, scale = 1f, anchor = viewer))
        }
    }
}
