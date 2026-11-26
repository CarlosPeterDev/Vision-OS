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
    private val menuPalm = PalmHold()
    private val gaze = GazeAim()
    private val governor = PerformanceGovernor()
    private val animator = WindowAnimator()
    override val stats = Stats()
    override val activity: android.app.Activity get() = this
    override lateinit var settings: XrSettings
        private set
    private lateinit var feedback: Feedback
    private lateinit var hud: TextView
    private val surfaces = HashMap<Int, SpatialSurface>()

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: XrRenderer
    private lateinit var head: HeadTracker
    private lateinit var hands: HandTracker
    private lateinit var camera: CameraFeed
    private lateinit var warning: TextView
    private lateinit var voice: VoiceStack

    private var stereo = false
    private var lensGridUntil = 0L
    private var calibration: Calibration? = null
    private var lastStepMs = 0L
    private var loggedLevel = 0
    private var highlighted = emptySet<Pair<Int, Region>>()
    private var touchRay: Ray? = null
    private var cameraFov = 50f
    private var cameraRotation = -1
    private var cameraStartedAt = 0L
    private var cameraRunning = false
    private var halted: String? = null
    private var running = false
    private var lastHandFrame: HandFrame? = null
    /** Where each hand pointer currently aims, in viewport coordinates. */
    private val aims = HashMap<Int, Point>()
    private var pointers = emptyMap<Int, PointerState>()
    private var video: Uri? = null
    private var url: String? = null
    private var voiceNotice = ""
    private var resumedAt = 0L
    private var startApp: AppType? = null
    private var placed = false
    private var stillForward: Vec3? = null
    private var stillSince = 0L
    private var tracedAt = 0L

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
        settings = XrSettings(this)
        feedback = Feedback(this, settings)
        stereo = intent.getBooleanExtra(EXTRA_HEADSET, false)

        head = HeadTracker(this)
        hands = HandTracker(this)
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
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = LiquidGlass(0x101520, 0xC0, 0x99)
            setPadding(36, 20, 36, 20)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(gl)
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })
            addView(warning)
        })
        open(AppType.HOME)
        // Optionally opens one more window in front. The activity is not exported to other apps.
        startApp = AppType.entries.firstOrNull { it.name == intent.getStringExtra("open") }
        if (!head.available) voiceNotice = getString(R.string.no_gyro)
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        head.start()
        running = true
        resumedAt = SystemClock.uptimeMillis()
        if (settings.passthrough || !cameraRunning) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        saveLayout()
        calibration = null
        touchRay = null
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
        feedback.release()
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
        settings.passthrough = false
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
        settings.passthrough = false // continue safely; passthrough can be re-enabled later
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
        val dt = if (lastStepMs == 0L) 0.016f else (now - lastStepMs) / 1000f
        lastStepMs = now
        val displayRotation = rotation
        head.displayDegrees = displayRotation * 90
        if (cameraRunning && displayRotation != cameraRotation) {
            cameraRotation = displayRotation
            camera.setRotation(displayRotation)
        }
        val heat = thermal()
        // A slow phone gets fewer effects; heat counts the same way.
        val measured = governor.update(renderer.fps, now)
        val level = if (heat >= PowerManager.THERMAL_STATUS_MODERATE) PerformanceGovernor.MAX_LEVEL else measured
        Motion.level = level
        if (level != loggedLevel) {
            loggedLevel = level
            Log.i("NexusXR", "Performance level $level at ${renderer.fps} FPS")
        }
        hands.minIntervalMs = when {
            heat >= PowerManager.THERMAL_STATUS_MODERATE -> 80
            level == PerformanceGovernor.MAX_LEVEL -> 55
            else -> 33
        }
        hands.extraTurns = settings.cameraTurns
        hands.swapSides = settings.swapHandSides
        if (heat >= PowerManager.THERMAL_STATUS_SEVERE) halt(getString(R.string.too_hot))
        val passthrough = settings.passthrough
        val lastFrame = renderer.lastCameraFrameMs
        if (passthrough && cameraRunning && now - maxOf(lastFrame, cameraStartedAt) > if (lastFrame < cameraStartedAt) FIRST_FRAME_MS else 1500) {
            halt(getString(R.string.camera_stalled))
        }

        val eyeWidth = if (stereo) gl.width / 2 else gl.width
        val aspect = if (gl.height > 0) eyeWidth.toFloat() / gl.height else 1.7f
        placeWhenSettled(now)
        val orientation = head.orientation()
        val quality = minOf(settings.quality, 2 - level)
        val base = Scene(
            stereo = stereo, passthrough = passthrough, quality = quality, ipd = settings.ipd,
            distortion = settings.distortion, cameraTurns = settings.cameraTurns,
            displayDegrees = displayRotation * 90, cameraFov = cameraFov
        )
        val fov = viewFov(base, aspect)
        val latest = hands.latest
        val fresh = latest != null && now - latest.timeMs < maxOf(300L, latest.latencyMs + 300)
        fun toViewport(p: Point, imageAspect: Float): Point {
            val (x, y) = coverMap(p.x, p.y, imageAspect, aspect)
            return Point(x, y)
        }
        val shapes = if (latest != null && fresh) {
            latest.hands.map { hand -> hand.points.map { toViewport(it, latest.aspect) } }
        } else emptyList()

        // Changing gesture settings releases anything held, without clicking.
        gestures.configure(settings.gestures()).forEach { cancelPointer(it, orientation) }
        // Pointers only advance on new tracking results, so a stalled tracker never lengthens a press.
        val updates = when {
            halted != null -> emptyList()
            latest != null && latest !== lastHandFrame -> {
                lastHandFrame = latest
                gestures.update(latest.hands, latest.aspect, latest.timeMs)
            }
            !fresh -> gestures.update(emptyList(), 1f, now)
            else -> null
        }
        if (updates != null) {
            pointers = updates.filter { it.phase != PointerPhase.CANCEL }.associateBy { it.handId }
            if (calibration != null) {
                updates.firstOrNull()?.let { calibration?.sample(it.ratio, now) }
            } else {
                for (p in updates) {
                    val v = toViewport(p.position, latest?.aspect ?: 4f / 3f)
                    val aim = if (settings.lookAndPinch) gaze.position(p.copy(position = v)) else v
                    aims[p.handId] = aim
                    controller.handle(p.handId, p.phase, viewportRay(orientation, aim.x, aim.y, fov, aspect))
                }
                aims.keys.retainAll(pointers.keys)
            }
        }
        stepCalibration(now)

        val centreRay = viewportRay(orientation, 0.5f, 0.5f, fov, aspect)
        // With look-and-pinch the view's centre highlights even before a hand appears.
        val gazing = settings.lookAndPinch && pointers.isEmpty() && halted == null
        if (gazing) controller.handle(GAZE_POINTER, PointerPhase.HOVER, centreRay)
        controller.retainHovers(pointers.keys + if (gazing) setOf(GAZE_POINTER) else emptySet())
        // A finger resting on the screen sends no events, so its hold is advanced here.
        touchRay?.let { controller.handle(TOUCH_POINTER, PointerPhase.MOVE, it) }

        val aimed = controller.hovered().filter { it.region != Region.CONTENT && it.region != Region.TITLE }
            .map { it.windowId to it.region }.toSet()
        if ((aimed - highlighted).isNotEmpty()) feedback.hover()
        highlighted = aimed

        // An open hand turned away shows Home; turned towards the face it opens the quick menu.
        val free = pointers.values.filter { it.openPalm && !controller.isCapturing(it.handId) }
        if (palm.update(free.any { !it.palmToUser }, now)) toggleInFront(AppType.HOME, orientation)
        if (menuPalm.update(free.any { it.palmToUser }, now)) toggleInFront(AppType.CENTER, orientation)

        val cursors = pointers.values.map { p ->
            val aim = aims[p.handId] ?: toViewport(p.position, latest?.aspect ?: 4f / 3f)
            val ray = viewportRay(orientation, aim.x, aim.y, fov, aspect)
            val distance = windows.pick(ray)?.t ?: 1.5f
            val progress = controller.capturedWindow(p.handId)?.let { surfaces[it]?.holdProgress } ?: 0f
            CursorMark(ray.direction * (distance * 0.98f), controller.isCapturing(p.handId), progress)
        } + if (gazing) {
            listOf(CursorMark(centreRay.direction * ((windows.pick(centreRay)?.t ?: 1.5f) * 0.98f), false))
        } else emptyList()

        val shown = animator.update(windows.windows, dt, level)
        syncSurfaces()
        val cinema = shown.any { it.window.curved }
        renderer.scene = Scene(
            windows = shown.map { it.window },
            alphas = shown.associate { it.window.id to it.alpha },
            titles = shown.associate { it.window.id to appTitle(this, it.window.app) },
            focusedId = windows.focused?.id ?: -1,
            highlights = highlighted,
            cursors = cursors,
            hands = if (settings.showHands && level < PerformanceGovernor.MAX_LEVEL) shapes else emptyList(),
            handShapes = shapes,
            occlusion = settings.occlusion && level < PerformanceGovernor.MAX_LEVEL,
            environment = settings.scene,
            dim = maxOf(settings.dim, if (cinema) CINEMA_DIM else 0f),
            lensShift = settings.lensShift, lensHeight = settings.lensHeight, lensZoom = settings.lensZoom,
            lensGrid = now < lensGridUntil,
            stereo = base.stereo, passthrough = base.passthrough, quality = base.quality, ipd = base.ipd,
            distortion = base.distortion, cameraTurns = base.cameraTurns, displayDegrees = base.displayDegrees,
            cameraFov = base.cameraFov, blank = halted != null
        )
    }

    private fun cancelPointer(state: PointerState, orientation: Quat) =
        controller.handle(state.handId, PointerPhase.CANCEL, Ray(Vec3(0f, 0f, 0f), orientation.rotate(Vec3(0f, 0f, -1f))))

    /** Brings a window in front of the user, or hides it if it is already there. */
    private fun toggleInFront(app: AppType, orientation: Quat) {
        stats.palms++
        feedback.click()
        val yaw = yawOf(orientation)
        val pitch = pitchOf(orientation)
        val existing = windows.find(app)
        val inView = existing != null && !existing.minimized &&
            kotlin.math.abs(kotlin.math.atan2(kotlin.math.sin(existing.yaw - yaw), kotlin.math.cos(existing.yaw - yaw))) < 0.3f &&
            kotlin.math.abs(existing.pitch - pitch) < 0.35f
        if (inView) windows.minimize(existing!!.id)
        else {
            open(app)
            windows.find(app)?.let { windows.replace(it.copy(yaw = yaw, pitch = pitch, distance = 1.5f)) }
        }
    }

    /** Measures an open hand, then a pinch, and derives this user's click thresholds. */
    private class Calibration(val startedAt: Long) {
        val open = mutableListOf<Float>()
        val closed = mutableListOf<Float>()
        fun sample(ratio: Float, now: Long) {
            val t = now - startedAt
            if (t in 2000..4500) open += ratio
            if (t in 7000..9500) closed += ratio
        }
    }

    private fun stepCalibration(now: Long) {
        val current = calibration
        if (current != null) {
            val t = now - current.startedAt
            if (t < 5000) showHud(getString(R.string.calibrate_open), now + 500)
            else if (t < 9700) showHud(getString(R.string.calibrate_pinch), now + 500)
            else {
                calibration = null
                val result = if (current.open.size < 4 || current.closed.size < 4) null else calibratedConfig(
                    settings.gestures(), current.open.sorted()[current.open.size / 2], current.closed.sorted()[current.closed.size / 4]
                )
                if (result != null) {
                    settings.pressRatio = result.pressRatio
                    settings.releaseRatio = result.releaseRatio
                }
                showHud(getString(if (result != null) R.string.calibrate_done else R.string.calibrate_failed), now + 4000)
            }
        }
        if (now > hudUntil) hud.visibility = View.GONE
    }

    private var hudUntil = 0L
    private fun showHud(message: String, until: Long) {
        hud.text = message
        hud.visibility = View.VISIBLE
        hudUntil = until
    }

    private fun saveLayout() {
        if (!placed) return
        // Saved relative to the window in use, so that one comes back straight ahead.
        val anchor = windows.focused ?: return
        settings.layout = LayoutCodec.encode(windows.windows.filter { it.app != AppType.KEYBOARD }, anchor.yaw, anchor.pitch)
    }

    /**
     * The first sensor readings arrive while the user is still turning the phone, so "forward"
     * is chosen after a short settle and the windows go exactly where the user is looking.
     */
    private fun placeWhenSettled(now: Long) {
        traceHead(now)
        if (placed || !head.hasReading) return
        // Restart the still period whenever the view direction moves noticeably.
        val forward = head.orientation().rotate(Vec3(0f, 0f, -1f))
        val reference = stillForward
        if (reference == null || (forward dot reference) < STILL_COS) {
            stillForward = forward
            stillSince = now
        }
        val waited = now - resumedAt
        if (waited < SETTLE_MS || now - stillSince < STILL_MS && waited < MAX_SETTLE_MS) return
        placed = true
        head.recenter()
        val look = head.orientation()
        windows.arrange(yawOf(look), pitchOf(look))
        if (settings.restoreLayout) windows.restore(LayoutCodec.decode(settings.layout, yawOf(look), pitchOf(look)))
        startApp?.let { app ->
            windows.open(app, yawOf(look), pitchOf(look))
            windows.find(app)?.let { windows.replace(it.copy(yaw = yawOf(look), pitch = pitchOf(look), distance = 1.45f)) }
        }
        if (!settings.tutorialSeen && startApp == null) {
            // Opened to the side; Home stays in front.
            windows.open(AppType.TUTORIAL, yawOf(look), pitchOf(look))
            windows.find(AppType.HOME)?.let { windows.focus(it.id) }
        }
        Log.i("NexusXR", "Windows placed at pitch %.2f".format(pitchOf(look)))
    }

    /** Logs the view direction for the first half minute, to diagnose sensor drift. */
    private fun traceHead(now: Long) {
        if (!head.hasReading || now - resumedAt > 30_000 || now - tracedAt < 3_000) return
        tracedAt = now
        val look = head.orientation()
        Log.i("NexusXR", "Head yaw %.2f pitch %.2f".format(yawOf(look), pitchOf(look)))
    }

    private fun syncSurfaces() {
        val alive = windows.windows.map { it.id }.toSet()
        // A closing window keeps its content until its fade has finished.
        surfaces.keys.filter { it !in alive && !animator.isVisible(it) }
            .forEach { id -> surfaces.remove(id)?.release(); renderer.destroyWindow(id) }
        for (win in windows.windows) {
            if (win.id in surfaces) continue
            val width = (win.width * PIXELS_PER_METRE).toInt()
            val height = (win.height * PIXELS_PER_METRE).toInt()
            val surface = SpatialSurface(this, "nexus-${win.id}", width, height, { feedback.hover() }) { buildApp(it, win.app, this) }
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
        if (phase == PointerPhase.DOWN) Log.i("NexusXR", "screen touch hits ${windows.pick(ray)}")
        touchRay = if (phase == PointerPhase.DOWN || phase == PointerPhase.MOVE) ray else null
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

    override fun onContentHover(windowId: Int, u: Float, v: Float) { surfaces[windowId]?.hover(u, v) }
    override fun onHoverEnd(windowId: Int) { surfaces[windowId]?.hoverEnd() }

    override fun onPressed(region: Region) {
        stats.presses++
        feedback.click()
    }

    override fun onAdjusted(region: Region) {
        if (region == Region.RESIZE) stats.resizes++ else stats.moves++
    }

    override fun onClosed(windowId: Int) {
        if (windows.windows.none { it.app == AppType.ASSISTANT }) voice.engine.cancel()
    }

    // ---- Shell ----

    override fun open(app: AppType) {
        val look = head.orientation()
        Log.i("NexusXR", "open $app")
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
    override fun textBeforeCursor() = target()?.textBeforeCursor().orEmpty()
    override fun replaceBeforeCursor(count: Int, text: String) { target()?.replaceBeforeCursor(count, text) }
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

    override fun recenter() {
        head.recenter()
        arrange()
    }

    override fun arrange() {
        val look = head.orientation()
        windows.arrange(yawOf(look), pitchOf(look))
    }

    override fun toggleHeadset() { stereo = !stereo }
    override fun isHeadset() = stereo

    override fun toggleLensGrid() {
        val now = SystemClock.uptimeMillis()
        lensGridUntil = if (now < lensGridUntil) 0 else now + 20_000
        if (lensGridUntil > 0) stereo = true // it switches itself off after 20 seconds
    }

    override fun toggleCinema() {
        val target = windows.windows.lastOrNull { it.curved }
            ?: windows.find(AppType.MEDIA) ?: windows.focused?.takeIf { it.app != AppType.HOME }
            ?: run { open(AppType.MEDIA); windows.find(AppType.MEDIA) } ?: return
        val look = head.orientation()
        windows.replace(
            if (target.curved) target.copy(curved = false, scale = 1f, distance = 1.7f)
            else target.copy(curved = true, scale = 1.7f, distance = 2.1f, yaw = yawOf(look), pitch = pitchOf(look), minimized = false)
        )
        windows.focus(target.id)
    }

    override fun startCalibration() {
        stereo = false
        controller.cancelAll()
        calibration = Calibration(SystemClock.uptimeMillis())
    }

    override fun volume(direction: Int) = getSystemService(AudioManager::class.java).adjustStreamVolume(
        AudioManager.STREAM_MUSIC, if (direction < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
        AudioManager.FLAG_SHOW_UI
    )

    override fun brightness(delta: Float) {
        val params = window.attributes
        val current = if (params.screenBrightness < 0) 0.6f else params.screenBrightness
        params.screenBrightness = (current + delta).coerceIn(0.1f, 1f)
        window.attributes = params
    }

    override fun settingsChanged() {
        cameraStartedAt = SystemClock.uptimeMillis()
    }

    override fun assistant(context: Context): SpatialApp =
        assistantApp(context, lifecycleScope, voice, { voiceNotice }) { talk() }

    override fun exit() { finish() }

    companion object {
        const val EXTRA_HEADSET = "headset"
        private const val TOUCH_POINTER = 100
        private const val GAZE_POINTER = 101
        private const val CINEMA_DIM = 0.7f
        private const val SETTLE_MS = 1500L
        private const val STILL_MS = 1500L
        private const val MAX_SETTLE_MS = 10_000L
        private const val STILL_COS = 0.9986f // about 3 degrees
        private const val FIRST_FRAME_MS = 15_000L
        private const val PIXELS_PER_METRE = 760
    }
}
