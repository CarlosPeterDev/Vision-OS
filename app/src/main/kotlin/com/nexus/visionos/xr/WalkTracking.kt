package com.nexus.visionos.xr

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.nexus.xr.Affine
import com.nexus.xr.PoseFusion
import com.nexus.xr.Quat
import com.nexus.xr.Vec3
import com.nexus.xr.beforeTurn
import com.nexus.xr.yuvToArgb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan

/**
 * Walk-around (6DoF) tracking with Google's ARCore. ARCore owns the camera while it runs, so it
 * also supplies the camera picture and the pictures for hand tracking; CameraX is stopped.
 *
 * [open], [resume], [pause] and [close] are called on the main thread, [update] on the GL thread.
 * [lock] keeps the session from being paused while a frame is being read.
 */
class WalkTracking(private val activity: Activity, private val hands: HandTracker, private val sensor: () -> Quat) {
    enum class Support { READY, INSTALLING, CHECKING, UNSUPPORTED }

    val fusion = PoseFusion()
    private val lock = Any()
    private var session: Session? = null
    private var running = false
    private var installAsked = false
    private var sensorOrientation = 90

    /** Vertical field of view of the current picture, in degrees; 0 before the first frame. */
    @Volatile var fovY = 0f
        private set
    /** Maps the shader's screen coordinates to the camera texture. */
    @Volatile var textureMatrix: FloatArray? = null
        private set
    /** Why tracking is not working right now, as ARCore names it; empty while it works. */
    @Volatile var trouble = "STARTING"
        private set
    /** True once tracking has worked at least once since [open]. */
    @Volatile var everTracked = false
        private set

    /** Maps the hand-tracking picture, before it was turned upright, to the view. */
    @Volatile private var imageToView: Affine? = null
    @Volatile private var turned = 0
    private var lastTimestamp = 0L
    private var geometry = Triple(-1, -1, -1)
    private val converter = Executors.newSingleThreadExecutor()
    private val converting = AtomicBoolean(false)

    /** Places a point of the upright hand-tracking picture in the view. */
    fun toView(x: Float, y: Float): Pair<Float, Float> {
        val map = imageToView ?: return Pair(x, y)
        val (u, v) = beforeTurn(x, y, turned)
        return map.map(u, v)
    }

    /** Asks whether this phone can do it, offering to install Google Play Services for AR if needed. */
    fun support(): Support = runCatching {
        val apk = ArCoreApk.getInstance()
        val availability = apk.checkAvailability(activity)
        when {
            availability.isTransient -> Support.CHECKING
            !availability.isSupported -> Support.UNSUPPORTED
            apk.requestInstall(activity, !installAsked) == ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installAsked = true
                Support.INSTALLING
            }
            else -> Support.READY
        }
    }.getOrElse {
        Log.w("NexusXR", "ARCore is not usable", it)
        Support.UNSUPPORTED
    }

    /** Throws if the session cannot be made or the camera is still busy. */
    fun resume() {
        val current = session ?: Session(activity).also { made ->
            val config = Config(made)
            // The newest picture without waiting, so drawing never stalls on the camera.
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO
            // Only the position is needed; the rest would only add heat.
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            made.configure(config)
            session = made
        }
        current.resume()
        sensorOrientation = runCatching {
            activity.getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(current.cameraConfig.cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 90
        synchronized(lock) {
            geometry = Triple(-1, -1, -1)
            running = true
        }
        Log.i("NexusXR", "Walk tracking started")
    }

    fun pause() {
        synchronized(lock) {
            if (!running) return
            running = false
            fusion.lost()
            trouble = "STARTING"
            runCatching { session?.pause() }
        }
    }

    fun close() {
        pause()
        synchronized(lock) {
            session?.close()
            session = null
        }
        converter.shutdown()
    }

    /**
     * Reads the latest frame on the GL thread. Returns true when it carries a new camera picture.
     * [displayRotation] is the Android surface rotation, and the size is that of one eye's picture.
     */
    fun update(texture: Int, displayRotation: Int, width: Int, height: Int): Boolean = synchronized(lock) {
        val current = session
        if (!running || current == null || width <= 0 || height <= 0) return false
        try {
            val wanted = Triple(displayRotation, width, height)
            val reshaped = wanted != geometry
            if (reshaped) {
                geometry = wanted
                current.setDisplayGeometry(displayRotation, width, height)
            }
            current.setCameraTextureName(texture)
            val frame = current.update()
            if (frame.timestamp == 0L) return false
            val fresh = frame.timestamp != lastTimestamp
            lastTimestamp = frame.timestamp
            if (reshaped || textureMatrix == null || frame.hasDisplayGeometryChanged()) mapScreen(frame)
            val camera = frame.camera
            val projection = FloatArray(16)
            camera.getProjectionMatrix(projection, 0, 0.05f, 20f)
            if (projection[5] > 0f) fovY = Math.toDegrees(2.0 * atan(1.0 / projection[5])).toFloat()
            if (camera.trackingState == TrackingState.TRACKING) {
                val pose = camera.displayOrientedPose
                fusion.tracked(
                    Quat(pose.qw(), pose.qx(), pose.qy(), pose.qz()), Vec3(pose.tx(), pose.ty(), pose.tz()), sensor()
                )
                if (!everTracked) Log.i("NexusXR", "Walk tracking has a position")
                everTracked = true
                trouble = ""
            } else {
                fusion.lost()
                trouble = camera.trackingFailureReason.name.let { if (it == "NONE") "STARTING" else it }
            }
            if (fresh) feedHands(frame, displayRotation)
            fresh
        } catch (e: Exception) {
            // The picture stops arriving, and the shell's frozen-camera check then takes over.
            Log.w("NexusXR", "Walk tracking frame failed", e)
            false
        }
    }

    private fun mapScreen(frame: Frame) {
        val corners = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f)
        val texture = FloatArray(6)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, corners, Coordinates2d.TEXTURE_NORMALIZED, texture
        )
        textureMatrix = Affine.textureMatrix(texture)
        val image = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val view = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, image, Coordinates2d.VIEW_NORMALIZED, view)
        imageToView = Affine.fromCorners(view)
    }

    private fun feedHands(frame: Frame, displayRotation: Int) {
        if (!hands.wantsFrame(SystemClock.uptimeMillis()) || !converting.compareAndSet(false, true)) return
        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            converting.set(false)
            return
        } catch (e: RuntimeException) {
            converting.set(false)
            Log.w("NexusXR", "No picture for hand tracking", e)
            return
        }
        // The planes are copied at once, so ARCore gets its picture back without waiting.
        val picture = image.use { Planes(it) }
        val degrees = (sensorOrientation - displayRotation * 90 + 360) % 360
        turned = degrees
        converter.execute {
            try {
                val pixels = IntArray(picture.width * picture.height)
                yuvToArgb(
                    picture.y, picture.u, picture.v, picture.width, picture.height,
                    picture.yStride, picture.uvStride, picture.uvStep, pixels
                )
                val bitmap = Bitmap.createBitmap(pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888)
                hands.submit(bitmap, degrees)
            } catch (e: RuntimeException) {
                Log.w("NexusXR", "Hand picture conversion failed", e)
            } finally {
                converting.set(false)
            }
        }
    }

    private class Planes(image: Image) {
        val width = image.width
        val height = image.height
        val yStride = image.planes[0].rowStride
        val uvStride = image.planes[1].rowStride
        val uvStep = image.planes[1].pixelStride
        val y = bytes(image, 0)
        val u = bytes(image, 1)
        val v = bytes(image, 2)

        private fun bytes(image: Image, index: Int): ByteArray {
            val buffer = image.planes[index].buffer
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}
