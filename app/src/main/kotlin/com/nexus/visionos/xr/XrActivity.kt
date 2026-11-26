package com.nexus.visionos.xr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager as AndroidWindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nexus.visionos.R
import com.nexus.visionos.VoiceStack
import com.nexus.visionos.speechAttributes
import com.nexus.xr.*

/** The spatial shell: tracking in, window interaction, then one immutable scene per frame out. */
class XrActivity : ComponentActivity(), Shell, InteractionListener {
    private val windows = WindowManager()
    private val controller = InteractionController(windows, this)
    private val gestures = GestureEngine()
    private val palm = PalmHold()
    private val surfaces = HashMap<Int, SpatialSurface>()

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: XrRenderer
    private lateinit var head: HeadTracker
    private lateinit var hands: HandTracker
    private lateinit var camera: CameraFeed
    private lateinit var warning: TextView
    private lateinit var voice: VoiceStack
    private lateinit var prefs: android.content.SharedPreferences

    private var stereo = false
    private var passthrough = true
    private var quality = 1
    private var ipd = 0.063f
    private var distortion = 0.22f
    private var cameraTurns = 0
    private var showHands = true
    private var cameraFov = 50f
    private var cameraRotation = -1
    private var cameraStartedAt = 0L
    private var cameraRunning = false
    private var halted: String? = null
    private var running = false
    private var lastHandFrame: HandFrame? = null
    private var pointers = emptyMap<Int, PointerState>()
    private var video: Uri? = null
    private var url: String? = null
    private var voiceNotice = ""
    private var resumedAt = 0L
    private var placed = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else useVirtual(getString(R.string.camera_denied_virtual))
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) talk()
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { video = it ?: video }

    private val audioFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(speechAttributes).build()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(AndroidWindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Use the whole panel, so the two headset views are centred on the physical screen.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = AndroidWindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        prefs = getSharedPreferences("xr", Context.MODE_PRIVATE)
        stereo = intent.getBooleanExtra(EXTRA_HEADSET, false)
        passthrough = prefs.getBoolean("passthrough", true)
        quality = prefs.getInt("quality", 1)
        ipd = prefs.getFloat("ipd", 0.063f)
        distortion = prefs.getFloat("distortion", 0.22f)
        cameraTurns = prefs.getInt("cameraTurns", 0)

        head = HeadTracker(this)
        hands = HandTracker(this)
        hands.extraTurns = cameraTurns
        camera = CameraFeed(this, hands)
        cameraFov = camera.verticalFov()
        voice = VoiceStack(this, lifecycleScope) { feature, e -> voiceNotice = "$feature → Android: ${e.message}" }

        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.preserveEGLContextOnPause = true
        renderer = XrRenderer(gl, head::orientation) { action -> runOnUiThread { if (!isDestroyed) action() } }
        gl.setRenderer(renderer)
        gl.setOnTouchListener { _, event -> touchFallback(event); true }
        warning = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(0xE6000000.toInt())
            setPadding(48, 48, 48, 48)
            visibility = View.GONE
            setOnClickListener { resumeFromWarning() }
        }
        setContentView(FrameLayout(this).apply { addView(gl); addView(warning) })
        open(AppType.HOME)
        if (!head.available) voiceNotice = getString(R.string.no_gyro)
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        head.start()
        running = true
        resumedAt = SystemClock.uptimeMillis()
        if (passthrough || !cameraRunning) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        gestures.reset()
        controller.cancelAll()
        voice.engine.cancel()
        head.stop()
        camera.stop()
        cameraRunning = false
        gl.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        surfaces.values.forEach { it.release() }
        surfaces.clear()
        camera.shutdown()
        hands.close()
        voice.shutdown()
        super.onDestroy()
    }

    private val rotation: Int
        get() = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_90

    private fun startCamera() {
        // The camera also drives hand tracking, so it runs in the virtual environment too.
        if (cameraRunning) return
        cameraRunning = true
        cameraStartedAt = SystemClock.uptimeMillis()
        cameraRotation = rotation
        camera.start(cameraRotation, { renderer.provideCamera(it) }) { halt(getString(R.string.camera_failed, it)) }
    }

    private fun useVirtual(reason: String) {
        passthrough = false
        voiceNotice = reason
    }

    /** Leaves immersive viewing and says why. The camera view is never left frozen. */
    private fun halt(reason: String) {
        if (halted != null) return
        halted = reason
        stereo = false
        gestures.reset()
        controller.cancelAll()
        warning.text = reason + "\n\n" + getString(R.string.tap_continue)
        warning.visibility = View.VISIBLE
    }

    private fun resumeFromWarning() {
        if (thermal() >= PowerManager.THERMAL_STATUS_SEVERE) return
        halted = null
        warning.visibility = View.GONE
        camera.stop()
        cameraRunning = false
        passthrough = false // continue safely; passthrough can be re-enabled from Controls
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

    private fun thermal() = getSystemService(PowerManager::class.java).currentThermalStatus

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            step()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val displayRotation = rotation
        head.displayDegrees = displayRotation * 90
        if (cameraRunning && displayRotation != cameraRotation) {
            cameraRotation = displayRotation
            camera.setRotation(displayRotation)
        }
        val heat = thermal()
        hands.minIntervalMs = if (heat >= PowerManager.THERMAL_STATUS_MODERATE) 80 else 33
        if (heat >= PowerManager.THERMAL_STATUS_SEVERE) halt(getString(R.string.too_hot))
        val lastFrame = renderer.lastCameraFrameMs
        if (passthrough && cameraRunning && now - maxOf(lastFrame, cameraStartedAt) > if (lastFrame < cameraStartedAt) FIRST_FRAME_MS else 1500) {
            halt(getString(R.string.camera_stalled))
        }

        val eyeWidth = if (stereo) gl.width / 2 else gl.width
        val aspect = if (gl.height > 0) eyeWidth.toFloat() / gl.height else 1.7f
        placeWhenSettled(now)
        val orientation = head.orientation()
        val effectiveQuality = if (heat >= PowerManager.THERMAL_STATUS_MODERATE) 0 else quality
        val base = Scene(
            stereo = stereo, passthrough = passthrough, quality = effectiveQuality, ipd = ipd, distortion = distortion,
            cameraTurns = cameraTurns, displayDegrees = displayRotation * 90, cameraFov = cameraFov
        )
        val fov = viewFov(base, aspect)
        val latest = hands.latest
        var skeletons = emptyList<List<Point>>()
        val fresh = latest != null && now - latest.timeMs < 300
        val updates = when {
            halted != null -> emptyList()
            latest != null && latest !== lastHandFrame -> {
                lastHandFrame = latest
                gestures.update(latest.hands, latest.aspect, latest.timeMs)
            }
            !fresh -> gestures.update(emptyList(), 1f, now)
            else -> null
        }
        fun toViewport(p: Point, imageAspect: Float): Point {
            val (x, y) = coverMap(p.x, p.y, imageAspect, aspect)
            return Point(x, y)
        }
        if (latest != null && fresh) {
            if (showHands) skeletons = latest.hands.map { hand -> hand.points.map { toViewport(it, latest.aspect) } }
        }
        if (updates != null) {
            for (p in updates) {
                val v = toViewport(p.position, latest?.aspect ?: 4f / 3f)
                controller.handle(p.handId, p.phase, viewportRay(orientation, v.x, v.y, fov, aspect))
            }
            pointers = updates.filter { it.phase != PointerPhase.CANCEL }.associateBy { it.handId }
        }
        // An open palm held up brings the home window in front of the user.
        if (palm.update(pointers.values.any { it.openPalm && !controller.isCapturing(it.handId) }, now)) {
            val existing = windows.find(AppType.HOME)
            if (existing != null && !existing.minimized && kotlin.math.abs(existing.yaw - yawOf(orientation)) < 0.3f) {
                windows.minimize(existing.id)
            } else {
                open(AppType.HOME)
                windows.find(AppType.HOME)?.let {
                    windows.replace(it.copy(yaw = yawOf(orientation), pitch = pitchOf(orientation), distance = 1.5f))
                }
            }
        }

        val cursors = pointers.values.map { p ->
            val v = toViewport(p.position, latest?.aspect ?: 4f / 3f)
            val ray = viewportRay(orientation, v.x, v.y, fov, aspect)
            val distance = windows.pick(ray)?.t ?: 1.5f
            CursorMark(ray.direction * (distance * 0.98f), controller.isCapturing(p.handId))
        }

        syncSurfaces()
        val visible = windows.windows.filter { !it.minimized }
        renderer.scene = Scene(
            windows = visible,
            titles = visible.associate { it.id to appTitle(this, it.app) },
            focusedId = windows.focused?.id ?: -1,
            cursors = cursors, hands = skeletons,
            stereo = base.stereo, passthrough = base.passthrough, quality = base.quality, ipd = base.ipd,
            distortion = base.distortion, cameraTurns = base.cameraTurns, displayDegrees = base.displayDegrees,
            cameraFov = base.cameraFov, blank = halted != null
        )
    }

    /**
     * The first sensor readings arrive while the user is still turning the phone, so "forward"
     * is chosen after a short settle and the windows go exactly where the user is looking.
     */
    private fun placeWhenSettled(now: Long) {
        if (placed || now - resumedAt < SETTLE_MS || !head.hasReading) return
        placed = true
        head.recenter()
        val look = head.orientation()
        windows.arrange(yawOf(look), pitchOf(look))
        Log.i("NexusXR", "Windows placed at pitch %.2f".format(pitchOf(look)))
    }

    private fun syncSurfaces() {
        val alive = windows.windows.map { it.id }.toSet()
        surfaces.keys.filter { it !in alive }.forEach { id -> surfaces.remove(id)?.release(); renderer.destroyWindow(id) }
        for (win in windows.windows) {
            if (win.id in surfaces) continue
            val width = (win.width * PIXELS_PER_METRE).toInt()
            val height = (win.height * PIXELS_PER_METRE).toInt()
            val surface = SpatialSurface(this, "nexus-${win.id}", width, height) { buildApp(it, win.app, this) }
            surfaces[win.id] = surface
            renderer.createWindow(win.id, width, height) { target ->
                if (surfaces[win.id] === surface) surface.attach(target)
            }
        }
    }

    /** Touch uses the same pointer pipeline as the hands (handheld mode). */
    private fun touchFallback(event: MotionEvent) {
        if (stereo || halted != null) return
        val aspect = gl.width.toFloat() / gl.height.coerceAtLeast(1)
        val ray = viewportRay(
            head.orientation(), event.x / gl.width, event.y / gl.height, viewFov(renderer.scene, aspect), aspect
        )
        val phase = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
            MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
            MotionEvent.ACTION_UP -> PointerPhase.UP
            MotionEvent.ACTION_CANCEL -> PointerPhase.CANCEL
            else -> return
        }
        controller.handle(TOUCH_POINTER, phase, ray)
    }

    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val audio = getSystemService(AudioManager::class.java)
        if (audio.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        voiceNotice = ""
        voice.engine.start(voice.settings.selectedLanguage).invokeOnCompletion { audio.abandonAudioFocusRequest(audioFocus) }
    }

    // ---- InteractionListener ----

    override fun onContentTouch(windowId: Int, action: TouchAction, u: Float, v: Float) {
        surfaces[windowId]?.touch(action, u, v)
    }

    override fun onClosed(windowId: Int) {
        if (windows.windows.none { it.app == AppType.ASSISTANT }) voice.engine.cancel()
    }

    // ---- Shell ----

    override fun open(app: AppType) {
        val look = head.orientation()
        windows.open(app, yawOf(look), pitchOf(look))
    }

    override fun openUrl(url: String) {
        this.url = url
        val existing = windows.find(AppType.BROWSER)
        open(AppType.BROWSER)
        if (existing != null) surfaces[existing.id]?.navigate(url)
    }

    override fun pendingUrl() = url.also { url = null }
    private fun target() = windows.focused?.let { surfaces[it.id] }
    override fun typeText(text: String) { target()?.type(text) }
    override fun sendKey(code: Int) { target()?.key(code) }
    override fun editAction(action: Int) { target()?.menu(action) }

    override fun pickVideo() {
        stereo = false // the system file picker is a normal flat screen
        videoPicker.launch(arrayOf("video/*", "audio/*"))
    }

    override fun videoUri() = video

    override fun statusLine(): String {
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date())
        val latest = hands.latest
        val hand = hands.error?.let { "✗ $it" }
            ?: latest?.let { "${it.hands.size} · ${it.latencyMs} ms" } ?: "…"
        return "$time · 🔋 $battery% · ${renderer.fps} FPS\n" +
            getString(R.string.status_tracking, getString(if (head.available) R.string.three_dof else R.string.no_tracking), hand, thermal()) +
            (if (voiceNotice.isEmpty()) "" else "\n$voiceNotice")
    }

    override fun controls(): List<Pair<String, () -> Unit>> {
        val audio = getSystemService(AudioManager::class.java)
        fun volume(direction: Int) = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        fun brightness(delta: Float) {
            val params = window.attributes
            val current = if (params.screenBrightness < 0) 0.6f else params.screenBrightness
            params.screenBrightness = (current + delta).coerceIn(0.1f, 1f)
            window.attributes = params
        }
        return listOf(
            getString(R.string.ctl_recenter) to {
                head.recenter()
                val look = head.orientation()
                windows.arrange(yawOf(look), pitchOf(look))
            },
            getString(R.string.ctl_arrange) to {
                val look = head.orientation()
                windows.arrange(yawOf(look), pitchOf(look))
            },
            getString(R.string.ctl_environment) to { passthrough = !passthrough; cameraStartedAt = SystemClock.uptimeMillis(); save() },
            getString(R.string.ctl_glass) to { quality = (quality + 1) % 3; save() },
            getString(R.string.ctl_headset) to { stereo = !stereo },
            getString(R.string.ctl_hands) to { showHands = !showHands },
            "IPD −" to { ipd = (ipd - 0.002f).coerceAtLeast(0.052f); save() },
            "IPD +" to { ipd = (ipd + 0.002f).coerceAtMost(0.074f); save() },
            getString(R.string.ctl_rotate_camera) to { cameraTurns = (cameraTurns + 1) % 4; hands.extraTurns = cameraTurns; save() },
            getString(R.string.ctl_lens_less) to { distortion = (distortion - 0.04f).coerceAtLeast(0f); save() },
            getString(R.string.ctl_lens_more) to { distortion = (distortion + 0.04f).coerceAtMost(0.6f); save() },
            getString(R.string.exit_spatial) to { exit() },
            "🔉" to { volume(AudioManager.ADJUST_LOWER) },
            "🔊" to { volume(AudioManager.ADJUST_RAISE) },
            "🔅" to { brightness(-0.15f) },
            "🔆" to { brightness(0.15f) }
        )
    }

    private fun save() {
        prefs.edit().putBoolean("passthrough", passthrough).putInt("quality", quality).putFloat("ipd", ipd)
            .putFloat("distortion", distortion).putInt("cameraTurns", cameraTurns).apply()
    }

    override fun assistant(context: Context): SpatialApp =
        assistantApp(context, lifecycleScope, voice, { voiceNotice }) { talk() }

    override fun exit() { finish() }

    companion object {
        const val EXTRA_HEADSET = "headset"
        private const val TOUCH_POINTER = 100
        private const val SETTLE_MS = 1500L
        private const val FIRST_FRAME_MS = 15_000L
        private const val PIXELS_PER_METRE = 760
    }
}
