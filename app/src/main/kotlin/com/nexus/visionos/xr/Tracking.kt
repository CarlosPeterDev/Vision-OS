package com.nexus.visionos.xr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.nexus.xr.*
import java.util.concurrent.Executors
import kotlin.math.atan

/** Orientation-only (3DoF) head tracking from the rotation-vector sensors. */
class HeadTracker(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available get() = sensor != null
    @Volatile var displayDegrees = 90
    @Volatile private var device: Quat? = null
    @Volatile private var yawOffset: Float? = null

    fun start() { sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { manager.unregisterListener(this) }
    fun recenter() { yawOffset = null }
    val hasReading get() = device != null

    /** Latest camera orientation. Without a sensor the view stays fixed and says so in Settings. */
    fun orientation(): Quat {
        val raw = device ?: return Quat.IDENTITY
        val camera = cameraOrientation(raw, displayDegrees)
        val offset = yawOffset ?: yawOf(camera).also { yawOffset = it }
        return recentered(camera, offset)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)
        device = Quat(q[0], q[1], q[2], q[3])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

class HandFrame(val hands: List<Hand>, val aspect: Float, val timeMs: Long, val latencyMs: Long)

/** MediaPipe Hand Landmarker in live-stream mode. At most one frame is in flight. */
class HandTracker(context: Context) : ImageAnalysis.Analyzer {
    @Volatile var latest: HandFrame? = null
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var minIntervalMs = 33L
    /** Extra counter-clockwise quarter turns, matching the passthrough correction. */
    @Volatile var extraTurns = 0
    /** For phones or lenses where left and right come out reversed. */
    @Volatile var swapSides = false

    private val identity = HandIdentity()
    @Volatile private var busySince = 0L
    private var lastSubmit = 0L
    @Volatile private var aspect = 4f / 3f
    private var closed = false

    private val landmarker: HandLandmarker? = try {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                if (latest == null) Log.i("NexusXR", "First hand tracking result")
                val now = SystemClock.uptimeMillis()
                val hands = result.landmarks().map { list -> list.map { Point(it.x(), it.y()) } }
                // MediaPipe names hands assuming a mirrored selfie image; the back camera is not mirrored.
                val sides = result.handednesses().map { names ->
                    when (names.firstOrNull()?.categoryName()) {
                        "Left" -> if (swapSides) Side.LEFT else Side.RIGHT
                        "Right" -> if (swapSides) Side.RIGHT else Side.LEFT
                        else -> Side.UNKNOWN
                    }
                }
                latest = HandFrame(identity.assign(hands, sides), aspect, now, now - result.timestampMs())
                busySince = 0
            }
            .setErrorListener { error = it.message; busySince = 0 }
            .build()
        HandLandmarker.createFromOptions(context, options).also { Log.i("NexusXR", "Hand landmarker ready") }
    } catch (t: Throwable) { // includes missing native libraries
        Log.e("NexusXR", "Hand landmarker failed", t)
        error = t.message ?: t.javaClass.simpleName
        null
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val now = SystemClock.uptimeMillis()
            if (now - lastSubmit < minIntervalMs || (busySince != 0L && now - busySince < STALL_MS)) return
            try {
                val source: Bitmap = it.toBitmap()
                val degrees = it.imageInfo.rotationDegrees - extraTurns * 90
                val upright = if (degrees % 360 == 0) source else Bitmap.createBitmap(
                    source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true
                )
                synchronized(this) {
                    val detector = landmarker ?: return
                    if (closed) return
                    aspect = upright.width.toFloat() / upright.height
                    lastSubmit = now
                    busySince = now
                    detector.detectAsync(BitmapImageBuilder(upright).build(), now)
                }
            } catch (e: RuntimeException) {
                error = e.message
                busySince = 0
            }
        }
    }

    private companion object { const val STALL_MS = 3000L }

    @Synchronized fun close() {
        closed = true
        landmarker?.close()
    }
}

/** One CameraX session feeds both passthrough and hand tracking, so cameras never compete. */
class CameraFeed(private val activity: ComponentActivity, private val tracker: HandTracker) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private fun selector(size: Size) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()

    fun start(rotation: Int, surfaces: Preview.SurfaceProvider, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val cameras = future.get()
                val preview = Preview.Builder().setResolutionSelector(selector(Size(1280, 960)))
                    .setTargetRotation(rotation).build()
                val images = ImageAnalysis.Builder().setResolutionSelector(selector(Size(640, 480)))
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                images.setAnalyzer(executor, tracker)
                preview.setSurfaceProvider(surfaces)
                cameras.unbindAll()
                val lens = if (cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                else CameraSelector.DEFAULT_FRONT_CAMERA
                cameras.bindToLifecycle(activity, lens, preview, images)
                provider = cameras
                analysis = images
            } catch (e: Exception) {
                Log.e("NexusXR", "Camera failed", e)
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun setRotation(rotation: Int) { analysis?.targetRotation = rotation }
    fun stop() { provider?.unbindAll(); provider = null; analysis = null }
    fun shutdown() { stop(); executor.shutdown() }

    /** Vertical field of view of the full 4:3 image in landscape, from the lens data. */
    fun verticalFov(): Float = runCatching {
        val manager = activity.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val info = manager.getCameraCharacteristics(id)
        val size = info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        val focal = info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!![0]
        val height = minOf(size.height, size.width * 0.75f)
        Math.toDegrees(2.0 * atan(height / (2.0 * focal))).toFloat().coerceIn(35f, 80f)
    }.getOrDefault(50f)
}
