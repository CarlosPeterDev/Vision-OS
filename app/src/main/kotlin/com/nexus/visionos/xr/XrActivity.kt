package com.nexus.visionos.xr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import com.nexus.voice.SttEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.KeyEvent
import com.nexus.visionos.SystemStt
import com.nexus.voice.Language
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Bundle
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
import android.widget.LinearLayout
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
    private lateinit var warning: LinearLayout
    /** The warning twice, side by side, for someone who was wearing the headset. */
    private lateinit var warningTexts: List<TextView>
    /** The headset pictures are being placed with a finger on the screen. */
    private var fitDragging = false
    private var fitSide = 0
    private var fitLast = Point(0f, 0f)
    private var fitSpread = 0f
    private var fitMoved = 0f
    private var fitTappedAt = 0L
    private lateinit var voice: VoiceStack

    private var stereo = false
    private var lensGridUntil = 0L
    /** Saving a picture or video can hold up the GPU for a moment on slow phones. */
    private var captureGraceUntil = 0L
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
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) {
        if (it.isNotEmpty()) { photos = it; library++ }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            settings.folder = uri.toString()
            library++
        }
    }
    private val dictationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) dictate()
    }
    private var photos = emptyList<Uri>()
    private var library = 0
    private var dictation: Job? = null
    private var recording: VideoCapture? = null
    private lateinit var recordingBadge: TextView
    private lateinit var listeningBadge: TextView
    private var voiceControl: VoiceControl? = null
    private var voiceMessage = ""
    private var resumeListening = false
    /** Steps the AI still wants to run once the user approves the one being asked about. */
    private var aiHeld: VoiceCommand? = null
    private var aiRest = emptyList<VoiceCommand>()
    private var timerEnd = 0L
    private var aimRay: Ray? = null
    private val resizing = HashSet<Int>()
    private val offlineStt by lazy { SystemStt(applicationContext, offline = true) { settings.voiceOfflineOnly } }
    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startListening()
    }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        showHud(getString(R.string.working), SystemClock.uptimeMillis() + 60_000)
        io.execute {
            val added = uris.count { Imports.add(applicationContext, it) }
            runOnUiThread {
                library++
                Log.i("NexusXR", "Imported $added of ${uris.size}")
                showHud(getString(R.string.imported, added, uris.size), SystemClock.uptimeMillis() + 3500)
            }
        }
    }
    private val commands = VoiceCommands()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

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
        head.fixed = intent.getBooleanExtra("fixedHead", false)
        if (head.fixed) Log.i("NexusXR", "Head tracking held still for testing")
        hands = HandTracker(this, settings.gpuTracking)
        if (hands.gpuCrashed) {
            settings.gpuTracking = false
            voiceNotice = getString(R.string.gpu_failed)
        }
        camera = CameraFeed(this, hands)
        cameraFov = camera.verticalFov()
        voice = VoiceStack(this, lifecycleScope) { feature, e -> voiceNotice = "$feature → Android: ${e.message}" }
        voice.interceptor = ::onSpeech

        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.setEGLConfigChooser(RecordableConfigChooser())
        gl.preserveEGLContextOnPause = true
        renderer = XrRenderer(gl, head::orientation) { action -> runOnUiThread { if (!isDestroyed) action() } }
        gl.setRenderer(renderer)
        gl.setOnTouchListener { _, event -> touchFallback(event); true }
        warningTexts = List(2) {
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
            }
        }
        warning = LinearLayout(this).apply {
            setBackgroundColor(0xE6000000.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            warningTexts.forEach { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
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
        recordingBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            background = LiquidGlass(0xE0202A, 0xD0, 0xCC)
            setPadding(32, 14, 32, 14)
            visibility = View.GONE
        }
        listeningBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            background = LiquidGlass(0x5A35B5, 0xD0, 0xCC)
            setPadding(32, 14, 32, 14)
            visibility = View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(gl)
            addView(listeningBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = 24; marginStart = 24 })
            addView(recordingBadge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 24 })
            addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })
            addView(warning)
        })
        open(AppType.HOME)
        // Optionally opens one more window in front. The activity is not exported to other apps.
        startApp = AppType.entries.firstOrNull { it.name == intent.getStringExtra("open") }
        // Test hook (the activity is not exported): one photo, then four seconds of recording.
        if (intent.getBooleanExtra("captureTest", false)) {
            gl.postDelayed({ capturePhoto() }, 12_000)
            gl.postDelayed({ toggleRecording() }, 15_000)
            gl.postDelayed({ if (isRecording()) toggleRecording() }, 19_000)
        }
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
        if (resumeListening) {
            resumeListening = false
            startListening()
        }
    }

    override fun onPause() {
        // The microphone is never used while spatial mode is off the screen.
        resumeListening = voiceControl?.active == true
        stopListening()
        surfaces.values.forEach { it.flush() }
        // The encoder must be detached while the GL thread still runs.
        stopRecording(wait = true)
        dictation?.cancel()
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
        io.shutdown()
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
        Log.w("NexusXR", "Immersive view halted")
        stopRecording()
        halted = reason
        val wasStereo = stereo
        stereo = false
        fitDragging = false
        gestures.reset()
        controller.cancelAll()
        for (text in warningTexts) text.text = reason + "\n\n" + getString(R.string.tap_continue)
        warningTexts[1].visibility = if (wasStereo) View.VISIBLE else View.GONE
        warning.visibility = View.VISIBLE
    }

    private fun resumeFromWarning() {
        halted = null
        warning.visibility = View.GONE
        camera.stop()
        cameraRunning = false
        settings.passthrough = false // continue safely; passthrough can be re-enabled later
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }

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
        // A slow phone gets fewer effects.
        val level = governor.update(renderer.fps, now)
        Motion.level = level
        windows.defaultDistance = settings.windowDistance
        controller.freeResize = settings.freeResize
        if (timerEnd != 0L && SystemClock.elapsedRealtime() >= timerEnd) ring()
        if (level != loggedLevel) {
            loggedLevel = level
            Log.i("NexusXR", "Performance level $level at ${renderer.fps} FPS")
        }
        hands.minIntervalMs = if (level == PerformanceGovernor.MAX_LEVEL) 55 else 33
        hands.extraTurns = settings.cameraTurns
        hands.swapSides = settings.swapHandSides
        val passthrough = settings.passthrough
        val lastFrame = renderer.lastCameraFrameMs
        // The camera has failed when the app keeps drawing but no new picture arrives. When the whole
        // phone stalls, for example while a heavy web page loads, nothing is drawn either.
        val started = lastFrame >= cameraStartedAt
        val silent = now - maxOf(lastFrame, cameraStartedAt)
        val limit = if (!started) FIRST_FRAME_MS else if (recording != null || now < captureGraceUntil) 6000L else 2000L
        if (passthrough && cameraRunning && silent > limit && (!started || renderer.framesWithoutCamera >= STALL_FRAMES)) {
            halt(getString(R.string.camera_stalled))
        }

        if (fitDragging && !stereo) {
            fitDragging = false
            hudUntil = 0
        }
        val aspect = when {
            gl.height <= 0 -> 1.7f
            stereo -> settings.fit.eye(0, gl.width, gl.height).aspect
            else -> gl.width.toFloat() / gl.height
        }
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
        recording?.let {
            val seconds = (SystemClock.elapsedRealtime() - it.startedAt) / 1000
            recordingBadge.text = "● REC %02d:%02d".format(seconds / 60, seconds % 60)
        }

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
        val shortcuts = settings.palmShortcuts
        if (palm.update(shortcuts && free.any { !it.palmToUser }, now)) toggleInFront(AppType.HOME, orientation)
        if (menuPalm.update(shortcuts && free.any { it.palmToUser }, now)) toggleInFront(AppType.CENTER, orientation)
        // Where a spoken "click" lands: the hand's cursor if there is one, else the middle of the view.
        aimRay = pointers.keys.firstOrNull()?.let { aims[it] }?.let { viewportRay(orientation, it.x, it.y, fov, aspect) } ?: centreRay

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
            cursorScale = settings.cursorSize,
            hands = if (settings.showHands && level < PerformanceGovernor.MAX_LEVEL) shapes else emptyList(),
            handShapes = shapes,
            occlusion = settings.occlusion && level < PerformanceGovernor.MAX_LEVEL,
            environment = settings.scene,
            dim = maxOf(settings.dim, if (cinema) CINEMA_DIM else 0f),
            fit = settings.fit, lensZoom = settings.lensZoom,
            lensGrid = stereo && (fitDragging || now < lensGridUntil),
            messages = headsetMessages(),
            stereo = base.stereo, passthrough = base.passthrough, quality = base.quality, ipd = base.ipd,
            distortion = base.distortion, cameraTurns = base.cameraTurns, displayDegrees = base.displayDegrees,
            cameraFov = base.cameraFov, blank = halted != null
        )
    }

    /**
     * Flat Android views cover the screen once, so in headset mode only one eye would see them.
     * They are hidden there and their text is drawn into both eyes instead.
     */
    private fun headsetMessages(): List<SceneMessage> {
        val badges = listOf(
            Triple(hud, 0xF0101520.toInt(), 0.55f),
            Triple(listeningBadge, 0xF05A35B5.toInt(), -0.5f),
            Triple(recordingBadge, 0xF0C01822.toInt(), -0.78f)
        )
        for ((badge, _, _) in badges) badge.alpha = if (stereo) 0f else 1f
        if (!stereo) return emptyList()
        return badges.filter { it.first.visibility == View.VISIBLE }
            .map { (badge, tint, height) -> SceneMessage(badge.text.toString(), tint, height) }
    }

    override fun startDragFit() {
        stereo = true
        fitDragging = true
        fitTappedAt = 0
        controller.cancelAll()
        touchRay = null
        showHud(getString(R.string.fit_drag_help), Long.MAX_VALUE)
        Log.i("NexusXR", "Finger fit on")
    }

    private fun stopDragFit() {
        if (!fitDragging) return
        fitDragging = false
        Log.i("NexusXR", "Finger fit saved: ${settings.fit}")
        say(getString(R.string.fit_saved))
    }

    /** One finger moves the picture under it, two fingers resize both, a double tap finishes. */
    private fun dragFit(event: MotionEvent) {
        val w = gl.width.coerceAtLeast(1).toFloat()
        val h = gl.height.coerceAtLeast(1).toFloat()
        fun spread() = if (event.pointerCount < 2) 0f else
            kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fitSide = if (event.x < w / 2) 0 else 1
                fitLast = Point(event.x, event.y)
                fitMoved = 0f
                fitSpread = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                fitSpread = spread()
                fitMoved = w
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                val now = spread()
                if (fitSpread > 0 && now > 0) settings.fit = settings.fit.scaled(now / fitSpread)
                fitSpread = now
            } else if (fitSpread == 0f) {
                val dx = event.x - fitLast.x
                val dy = event.y - fitLast.y
                fitMoved += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                fitLast = Point(event.x, event.y)
                settings.fit = settings.fit.dragged(fitSide, dx / w, -dy / h)
            }
            MotionEvent.ACTION_UP -> if (fitMoved < w * 0.02f) {
                val now = SystemClock.uptimeMillis()
                if (now - fitTappedAt < 600) stopDragFit() else fitTappedAt = now
            }
        }
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
        if (settings.voiceAutoStart) startListening()
        // Test hook: run spoken sentences through the same path the microphone uses.
        intent.getStringExtra("say")?.let { script ->
            script.split('|').forEachIndexed { i, sentence ->
                gl.postDelayed({ lifecycleScope.launch { heard(sentence.trim()) } }, 8_000L + i * 7_000L)
            }
        }
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
            surfaces[win.id]?.let { surface ->
                val w = (win.width * PIXELS_PER_METRE).toInt()
                val h = (win.height * PIXELS_PER_METRE).toInt()
                // Reshaped once the corner is let go, so the content is laid out only once.
                if ((kotlin.math.abs(w - surface.width) > 12 || kotlin.math.abs(h - surface.height) > 12) &&
                    !controller.isHeld(win.id) && resizing.add(win.id)
                ) {
                    renderer.resizeWindow(win.id, w, h) {
                        if (surfaces[win.id] === surface) surface.resize(w, h) {}
                        resizing.remove(win.id)
                    }
                }
            }
            if (win.id in surfaces) continue
            val width = (win.width * PIXELS_PER_METRE).toInt()
            val height = (win.height * PIXELS_PER_METRE).toInt()
            val surface = SpatialSurface(
                this, "nexus-${win.id}", width, height, settings.textScale, { settings.longPressMs.toLong() }, { feedback.hover() }
            ) { buildApp(it, win.app, this) }
            surfaces[win.id] = surface
            renderer.createWindow(win.id, width, height) { target ->
                if (surfaces[win.id] === surface) surface.attach(target)
            }
        }
    }

    /** Touch uses the same pointer pipeline as the hands (handheld mode). */
    private fun touchFallback(event: MotionEvent) {
        if (halted != null) return
        if (fitDragging) {
            if (stereo) return dragFit(event)
            fitDragging = false
        }
        if (stereo) return
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
        stopListening()
        dictation?.cancel()
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

    override fun play(uri: Uri) {
        video = uri
        open(AppType.MEDIA)
    }

    override fun pickPhotos() {
        stereo = false
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun pickedPhotos() = photos

    override fun pickFolder() {
        stereo = false
        folderPicker.launch(null)
    }

    /** The saved folder, only while Android still grants access to it. */
    override fun pickedFolder(): Uri? = contentResolver.persistedUriPermissions
        .firstOrNull { it.isReadPermission && it.uri.toString() == settings.folder }?.uri

    override fun libraryVersion() = library
    override fun canSuggest() = target()?.canSuggest() == true

    override fun dictate() {
        dictation?.let { it.cancel(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dictationPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopListening()
        val field = windows.focused?.id ?: return
        voice.engine.cancel() // one user of the microphone at a time
        val audio = getSystemService(AudioManager::class.java)
        if (audio.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        dictation = lifecycleScope.launch {
            try {
                showHud("● " + getString(R.string.listening), Long.MAX_VALUE)
                voice.stt.listen(voice.settings.selectedLanguage).collect { event ->
                    when (event) {
                        is SttEvent.Partial -> showHud("● " + event.text, Long.MAX_VALUE)
                        // Typed into the field that was focused when dictation began.
                        is SttEvent.Final -> surfaces[field]?.type(event.text + " ")
                    }
                }
                hudUntil = 0
            } catch (e: CancellationException) {
                hudUntil = 0
                throw e
            } catch (e: Exception) {
                showHud(e.message ?: e.javaClass.simpleName, SystemClock.uptimeMillis() + 4000)
            } finally {
                audio.abandonAudioFocusRequest(audioFocus)
                dictation = null
            }
        }
    }

    override fun capturePhoto() {
        feedback.click()
        captureGraceUntil = SystemClock.uptimeMillis() + 8000
        renderer.requestPhoto { pixels, w, h ->
            io.execute {
                val saved = savePhoto(applicationContext, pixels, w, h)
                runOnUiThread {
                    library++
                    Log.i("NexusXR", if (saved) "Photo saved" else "Photo failed")
                    showHud(getString(if (saved) R.string.photo_saved else R.string.capture_failed), SystemClock.uptimeMillis() + 3000)
                }
            }
        }
    }

    override fun toggleRecording() {
        if (recording != null) return stopRecording()
        val capture = VideoCapture.start(applicationContext, gl.width, gl.height)
        if (capture == null) {
            showHud(getString(R.string.capture_failed), SystemClock.uptimeMillis() + 3000)
            return
        }
        recording = capture
        recordingBadge.visibility = View.VISIBLE
        Log.i("NexusXR", "Recording started")
        renderer.startEncoding(capture.surface, capture.width, capture.height) { stopRecording() }
    }

    override fun isRecording() = recording != null

    private fun stopRecording(wait: Boolean = false) {
        val capture = recording ?: return
        recording = null
        recordingBadge.visibility = View.GONE
        val detached = CountDownLatch(1)
        renderer.stopEncoding {
            detached.countDown()
            if (!wait) runOnUiThread { finishRecording(capture) }
        }
        if (wait) {
            detached.await(1, TimeUnit.SECONDS)
            finishRecording(capture)
        }
    }

    private fun finishRecording(capture: VideoCapture) {
        val saved = capture.finish()
        captureGraceUntil = SystemClock.uptimeMillis() + 8000
        library++
        Log.i("NexusXR", if (saved) "Recording saved" else "Recording failed")
        showHud(getString(if (saved) R.string.video_saved else R.string.capture_failed), SystemClock.uptimeMillis() + 3000)
    }

    // ---- Voice ----

    override fun toggleVoiceControl() {
        if (voiceControl?.active == true) {
            stopListening()
            showHud(getString(R.string.voice_off), SystemClock.uptimeMillis() + 2500)
        } else startListening()
    }

    private fun startListening() {
        if (voiceControl?.active == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        // Only one user of the microphone at a time.
        dictation?.cancel()
        voice.engine.cancel()
        val control = VoiceControl(
            lifecycleScope, offlineStt,
            { when (settings.voiceLanguage) { 1 -> Language.ENGLISH; 2 -> Language.ARABIC; else -> null } },
            { words -> showListening(words) },
            { sentence -> heard(sentence) },
            { reason ->
                voiceMessage = reason
                listeningBadge.visibility = View.GONE
                Log.w("NexusXR", "Voice control stopped: $reason")
                showHud(reason, SystemClock.uptimeMillis() + 8000)
            }
        )
        voiceControl = control
        voiceMessage = ""
        control.start()
        showListening("")
        Log.i("NexusXR", "Voice control on")
    }

    private fun stopListening() {
        voiceControl?.stop()
        voiceControl = null
        listeningBadge.visibility = View.GONE
    }

    /** Always on screen while the microphone is in use, and says who is doing the recognising. */
    private fun showListening(words: String) {
        if (voiceControl?.active != true) return
        val engine = getString(if (offlineStt.hasOnDevice) R.string.listening_on_device else R.string.listening_system)
        listeningBadge.text = "● $engine" + if (words.isEmpty()) "" else "\n$words"
        listeningBadge.visibility = View.VISIBLE
    }

    override fun isListening() = voiceControl?.active == true

    override fun voiceControlState(): String {
        val engine = getString(if (offlineStt.hasOnDevice) R.string.has_offline_recognizer else R.string.lacks_offline_recognizer)
        return if (voiceMessage.isEmpty()) engine else "$engine\n$voiceMessage"
    }

    private fun say(message: String, millis: Long = 3500) {
        voiceMessage = message
        showHud(message, SystemClock.uptimeMillis() + millis)
    }

    /** One sentence from hands-free listening: a built-in command first, otherwise the AI model. */
    private suspend fun heard(sentence: String) {
        var text = sentence.trim()
        if (settings.wakeWord) {
            if (VoiceCommands.normalize(text.substringBefore(' ')) !in WAKE_WORDS) return
            text = text.substringAfter(' ', "").trim()
        }
        if (text.isEmpty()) return
        val result = commands.handle(text, SystemClock.uptimeMillis())
        // The sentence itself is not logged: it may be something private being typed.
        Log.i("NexusXR", "Heard a " + if (result == null) "request for the AI model" else "built-in command")
        val held = aiHeld
        val rest = aiRest
        aiHeld = null
        aiRest = emptyList()
        when (result) {
            is CommandResult.AskToConfirm -> say(getString(R.string.confirm_needed, text), 20_000)
            CommandResult.Cancelled -> say(getString(R.string.cancelled))
            is CommandResult.Run -> {
                perform(result.command, text)
                if (held != null && result.command === held) runSteps(rest)
            }
            null -> askAi(text)
        }
    }

    private fun perform(command: VoiceCommand, label: String) {
        hudUntil = 0
        val done = run(command)
        Log.i("NexusXR", "Command ${command.javaClass.simpleName} ${if (done) "done" else "not possible"}")
        // Commands that answer on screen, such as the time, keep their own message.
        if (hudUntil == 0L || !done) say(if (done) "✓ $label" else getString(R.string.not_possible, label))
    }

    private suspend fun askAi(text: String) {
        if (!settings.voiceAi) return say(getString(R.string.not_a_command, text))
        if (!voice.llm.configured) return say(getString(R.string.no_ai_model, text), 6000)
        say(getString(R.string.ai_thinking, text), 40_000)
        val plan = try {
            val reply = StringBuilder()
            withTimeout(40_000) {
                voice.llm.reply(text, AiControl.systemPrompt(windows.focused?.app)).collect {
                    if (reply.length < 4000) reply.append(it)
                }
            }
            AiControl.parse(reply.toString())
        } catch (e: TimeoutCancellationException) {
            return say(getString(R.string.ai_timeout))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return say(e.message ?: e.javaClass.simpleName, 6000)
        }
        if (plan.say.isNotEmpty()) say(plan.say.take(300), 9000)
        else if (plan.steps.isEmpty()) say(getString(R.string.ai_nothing))
        runSteps(plan.steps)
    }

    /** Runs the AI's steps in order, stopping to ask before any that could act for the user. */
    private suspend fun runSteps(steps: List<VoiceCommand>) {
        for ((i, step) in steps.withIndex()) {
            if (AiControl.needsConfirmation(step)) {
                commands.askFirst(step, SystemClock.uptimeMillis())
                aiHeld = step
                aiRest = steps.drop(i + 1)
                return say(getString(R.string.ai_confirm, describe(step)), 20_000)
            }
            perform(step, describe(step))
            // New windows and web pages need a moment before the next step can use them.
            delay(if (step is VoiceCommand.Search || step is VoiceCommand.GoTo || step is VoiceCommand.Open) 2500 else 700)
        }
    }

    private fun describe(command: VoiceCommand) = when (command) {
        is VoiceCommand.Do -> command.act.phrases.first()
        is VoiceCommand.Open -> "open " + VoiceCommands.NAMES[command.app]?.first()
        is VoiceCommand.Close -> "close " + VoiceCommands.NAMES[command.app]?.first()
        is VoiceCommand.Type -> "type " + command.text
        is VoiceCommand.Search -> "search " + command.query
        is VoiceCommand.GoTo -> "go to " + command.address
        is VoiceCommand.ClickLabel -> "click " + command.label
        is VoiceCommand.Timer -> "timer ${command.minutes}"
        is VoiceCommand.AddTask -> "add task " + command.text
        else -> command.toString().lowercase()
    }

    /** Commands spoken to the assistant window; it speaks the short answer. */
    private fun onSpeech(sentence: String): String? {
        val result = commands.handle(sentence, SystemClock.uptimeMillis()) ?: return null
        val arabic = sentence.any { it in '\u0600'..'\u06FF' }
        fun answer(english: String, arabicText: String) = if (arabic) arabicText else english
        return when (result) {
            is CommandResult.AskToConfirm -> answer("That closes things. Say confirm to continue, or cancel.", "ده هيقفل حاجات. قول تأكيد علشان أكمل، أو إلغاء.")
            CommandResult.Cancelled -> answer("Cancelled.", "تمام، لغيت.")
            is CommandResult.Run -> if (run(result.command)) answer("Done.", "تمام.") else answer("I can't do that here.", "مش هينفع هنا.")
        }
    }

    private fun run(command: VoiceCommand): Boolean {
        when (command) {
            is VoiceCommand.Open -> open(command.app)
            is VoiceCommand.Close -> {
                val window = windows.find(command.app) ?: return false
                windows.close(window.id)
                onClosed(window.id)
            }
            VoiceCommand.CloseAll -> {
                // The assistant stays, so it can finish its reply.
                windows.windows.filter { it.app != AppType.ASSISTANT }.forEach { windows.close(it.id) }
                open(AppType.HOME)
            }
            VoiceCommand.Exit -> gl.postDelayed({ exit() }, 1500)
            VoiceCommand.Recenter -> recenter()
            VoiceCommand.Arrange -> arrange()
            VoiceCommand.Cinema -> toggleCinema()
            VoiceCommand.Photo -> capturePhoto()
            is VoiceCommand.Do -> return act(command.act)
            is VoiceCommand.Type -> return target()?.takeIf { it.hasTextFocus }?.type(command.text) == true
            is VoiceCommand.Search -> openUrl(searchUrl(settings.searchEngine, command.query))
            is VoiceCommand.GoTo -> {
                val spoken = command.address.trim().replace(Regex("\\s+(dot|دوت|نقطه|نقطة)\\s+", RegexOption.IGNORE_CASE), ".")
                openUrl(
                    when {
                        spoken.startsWith("https://") -> spoken
                        '.' in spoken && ' ' !in spoken -> "https://" + spoken.removePrefix("http://").lowercase()
                        else -> searchUrl(settings.searchEngine, spoken)
                    }
                )
            }
            is VoiceCommand.ClickLabel -> {
                val surface = target() ?: return false
                surface.clickLabel(command.label) { found ->
                    if (!found) say(getString(R.string.label_not_found, command.label))
                }
            }
            is VoiceCommand.Timer -> setTimer(command.minutes)
            is VoiceCommand.AddTask -> TaskStore.add(this, command.text)
        }
        return true
    }

    private fun act(act: Act): Boolean {
        val focused = windows.focused
        val surface = target()
        fun change(update: (SpatialWindow) -> SpatialWindow): Boolean {
            val window = focused ?: return false
            windows.replace(update(window))
            return true
        }
        fun scale(factor: Float) = change { it.copy(scale = (it.scale * factor).coerceIn(SpatialWindow.MIN_SCALE, SpatialWindow.MAX_SCALE)) }
        fun side(value: Float) = value.coerceIn(SpatialWindow.MIN_SIDE, SpatialWindow.MAX_SIDE)
        fun inApp(app: AppType): Boolean {
            val window = focused?.takeIf { it.app == app } ?: windows.find(app) ?: return false
            return surfaces[window.id]?.command(act) == true
        }
        fun key(code: Int): Boolean {
            val field = surface?.takeIf { it.hasTextFocus } ?: return false
            field.key(code)
            return true
        }
        // The games use the movement words themselves.
        if (focused?.app == AppType.GAMES && surface?.command(act) == true) return true
        val look = head.orientation()
        when (act) {
            Act.CLOSE_THIS -> {
                val window = focused ?: return false
                windows.close(window.id)
                onClosed(window.id)
            }
            Act.MINIMIZE -> { windows.minimize(focused?.id ?: return false) }
            Act.RESTORE_ALL -> windows.windows.filter { it.minimized }.forEach { windows.replace(it.copy(minimized = false)) }
            Act.BIGGER -> return scale(1.2f)
            Act.SMALLER -> return scale(1 / 1.2f)
            Act.WIDER -> return change { it.copy(width = side(it.width + 0.18f)) }
            Act.NARROWER -> return change { it.copy(width = side(it.width - 0.18f)) }
            Act.TALLER -> return change { it.copy(height = side(it.height + 0.14f)) }
            Act.SHORTER -> return change { it.copy(height = side(it.height - 0.14f)) }
            Act.CLOSER -> return change { it.copy(distance = (it.distance - 0.25f).coerceAtLeast(0.9f)) }
            Act.FARTHER -> return change { it.copy(distance = (it.distance + 0.25f).coerceAtMost(3.5f)) }
            Act.MOVE_LEFT -> return change { it.copy(yaw = it.yaw - 0.3f) }
            Act.MOVE_RIGHT -> return change { it.copy(yaw = it.yaw + 0.3f) }
            Act.MOVE_UP -> return change { it.copy(pitch = (it.pitch + 0.2f).coerceAtMost(1.1f)) }
            Act.MOVE_DOWN -> return change { it.copy(pitch = (it.pitch - 0.2f).coerceAtLeast(-1.1f)) }
            Act.BRING_HERE -> return change { it.copy(yaw = yawOf(look), pitch = pitchOf(look)) }
            Act.NEXT_WINDOW -> {
                val next = windows.windows.firstOrNull { !it.minimized && it.app != AppType.KEYBOARD && it.id != focused?.id } ?: return false
                windows.focus(next.id)
                windows.replace(next.copy(yaw = yawOf(look), pitch = pitchOf(look)))
            }
            Act.SAVE_LAYOUT -> settings.savedLayout =
                LayoutCodec.encode(windows.windows.filter { it.app != AppType.KEYBOARD }, yawOf(look), pitchOf(look))
            Act.RESTORE_LAYOUT -> {
                val saved = LayoutCodec.decode(settings.savedLayout, yawOf(look), pitchOf(look))
                if (saved.isEmpty()) return false
                windows.restore(saved)
            }
            Act.CLICK -> {
                val ray = aimRay ?: return false
                if (windows.pick(ray) == null) return false
                controller.handle(VOICE_POINTER, PointerPhase.DOWN, ray)
                gl.postDelayed({ controller.handle(VOICE_POINTER, PointerPhase.UP, ray) }, 90)
            }
            Act.LONG_CLICK -> {
                val hit = aimRay?.let(windows::pick)?.takeIf { it.region == Region.CONTENT } ?: return false
                return surfaces[hit.windowId]?.longPressAt(hit.u, hit.v) == true
            }
            Act.SCROLL_DOWN -> return surface?.scroll(1) == true
            Act.SCROLL_UP -> return surface?.scroll(-1) == true
            Act.PAGE_TOP -> return surface?.scroll(-1, toEnd = true) == true
            Act.PAGE_BOTTOM -> return surface?.scroll(1, toEnd = true) == true
            Act.DELETE -> return key(KeyEvent.KEYCODE_DEL)
            Act.DELETE_WORD -> return surface?.deleteWord() == true
            Act.CLEAR_TEXT -> return surface?.clearText() == true
            Act.ENTER -> return key(KeyEvent.KEYCODE_ENTER)
            Act.SPACE -> return surface?.type(" ") == true
            Act.SELECT_ALL -> return surface?.menu(android.R.id.selectAll) == true
            Act.COPY -> return surface?.menu(android.R.id.copy) == true
            Act.CUT -> return surface?.menu(android.R.id.cut) == true
            Act.PASTE -> return surface?.menu(android.R.id.paste) == true
            Act.UNDO -> return surface?.menu(android.R.id.undo) == true
            Act.SHOW_KEYBOARD -> open(AppType.KEYBOARD)
            Act.HIDE_KEYBOARD -> {
                val window = windows.find(AppType.KEYBOARD) ?: return false
                windows.close(window.id)
            }
            Act.BACK, Act.FORWARD, Act.RELOAD, Act.STOP_LOADING, Act.NEXT_TAB, Act.PREVIOUS_TAB, Act.CLOSE_TAB, Act.BOOKMARK,
            Act.ZOOM_IN, Act.ZOOM_OUT -> return inApp(AppType.BROWSER)
            Act.NEW_TAB, Act.SHOW_BOOKMARKS, Act.SHOW_HISTORY, Act.HOME_PAGE ->
                return if (windows.find(AppType.BROWSER) == null) { open(AppType.BROWSER); true } else inApp(AppType.BROWSER)
            Act.PLAY, Act.PAUSE, Act.SKIP, Act.REWIND -> return inApp(AppType.MEDIA)
            Act.VOLUME_UP -> volume(1)
            Act.VOLUME_DOWN -> volume(-1)
            Act.MUTE, Act.UNMUTE -> getSystemService(AudioManager::class.java).adjustStreamVolume(
                AudioManager.STREAM_MUSIC, if (act == Act.MUTE) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                AudioManager.FLAG_SHOW_UI
            )
            Act.BRIGHTER -> brightness(0.2f)
            Act.DIMMER -> brightness(-0.2f)
            Act.DIM_MORE -> settings.dim += 0.2f
            Act.DIM_LESS -> settings.dim -= 0.2f
            Act.GAP_WIDER -> settings.fit = settings.fit.let { it.copy(gap = it.gap + 0.02f) }
            Act.GAP_NARROWER -> settings.fit = settings.fit.let { it.copy(gap = it.gap - 0.02f) }
            Act.SCREENS_BIGGER -> settings.fit = settings.fit.scaled(1.07f)
            Act.SCREENS_SMALLER -> settings.fit = settings.fit.scaled(0.93f)
            Act.HEADSET_ON -> stereo = true
            Act.HEADSET_OFF -> stereo = false
            Act.CAMERA_VIEW -> { settings.passthrough = true; settingsChanged() }
            Act.VIRTUAL_VIEW -> { settings.passthrough = false; settingsChanged() }
            Act.NEXT_SCENE -> { settings.scene = (settings.scene + 1) % 4; settings.passthrough = false; settingsChanged() }
            Act.START_RECORDING -> { if (recording != null) return false; toggleRecording(); return recording != null }
            Act.STOP_RECORDING -> { if (recording == null) return false; toggleRecording() }
            Act.TIME -> say(android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date()), 5000)
            Act.BATTERY -> say(getString(R.string.battery, getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)), 5000)
            Act.HELP -> open(AppType.VOICE)
            Act.STOP_LISTENING -> { stopListening(); say(getString(R.string.voice_off)) }
        }
        return true
    }

    // ---- Timer and files ----

    override fun setTimer(minutes: Int) {
        timerEnd = if (minutes <= 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    override fun timerEndsAt() = timerEnd

    private fun ring() {
        timerEnd = 0
        showHud(getString(R.string.time_up), SystemClock.uptimeMillis() + 8000)
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2500)
            gl.postDelayed({ tone.release() }, 3000)
        }
    }

    override fun importFiles() {
        stereo = false // the system file picker is a normal flat screen
        importPicker.launch(arrayOf("*/*"))
    }

    override fun isGpuTracking() = hands.gpu

    override fun statusLine(): String {
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date())
        val latest = hands.latest
        val hand = hands.error?.let { "✗ $it" }
            ?: latest?.let { "${it.hands.size} · ${it.latencyMs} ms" } ?: "…"
        return "$time · $battery% · ${renderer.fps} FPS\n" +
            getString(R.string.status_tracking, getString(if (head.available) R.string.three_dof else R.string.no_tracking), hand) +
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
        private const val VOICE_POINTER = 102
        private const val STALL_FRAMES = 20
        private val WAKE_WORDS = setOf("nexus", "نكسس", "نيكسس", "نكزس")
        private const val CINEMA_DIM = 0.7f
        private const val SETTLE_MS = 1500L
        private const val STILL_MS = 1500L
        private const val MAX_SETTLE_MS = 10_000L
        private const val STILL_COS = 0.9986f // about 3 degrees
        private const val FIRST_FRAME_MS = 15_000L
        private const val PIXELS_PER_METRE = 760
    }
}
