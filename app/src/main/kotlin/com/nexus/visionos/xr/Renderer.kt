package com.nexus.visionos.xr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import com.nexus.visionos.R
import com.nexus.xr.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.tan

class CursorMark(val position: Vec3, val pinched: Boolean, val progress: Float = 0f)

/** [height] runs from -1, the bottom of the view, to 1, the top. */
data class SceneMessage(val text: String, val tint: Int, val height: Float)

/** Immutable description of one frame, built on the main thread. */
class Scene(
    val windows: List<SpatialWindow> = emptyList(),
    val titles: Map<Int, String> = emptyMap(),
    val focusedId: Int = -1,
    val cursors: List<CursorMark> = emptyList(),
    /** Hand landmarks in eye-viewport coordinates. */
    val hands: List<List<Point>> = emptyList(),
    val stereo: Boolean = false,
    val passthrough: Boolean = true,
    val quality: Int = 1,
    val ipd: Float = 0.063f,
    val distortion: Float = 0.22f,
    val cameraTurns: Int = 0,
    val displayDegrees: Int = 90,
    val cameraFov: Float = 50f,
    val blank: Boolean = false,
    /** Fade per window id; missing means opaque. */
    val alphas: Map<Int, Float> = emptyMap(),
    /** Window controls being aimed at. */
    val highlights: Set<Pair<Int, Region>> = emptySet(),
    val environment: Int = 0,
    val dim: Float = 0f,
    /** Redraw the camera pixels of the hands over the windows. */
    val occlusion: Boolean = false,
    /** Hands used for occlusion, present even when outlines are hidden. */
    val handShapes: List<List<Point>> = emptyList(),
    val fit: HeadsetFit = HeadsetFit(),
    /** Progress of the opening animation; 1 when it is over. */
    val intro: Float = 1f,
    /** Messages drawn inside the 3D picture, so that in headset mode both eyes see them. */
    val messages: List<SceneMessage> = emptyList(),
    val lensZoom: Float = 1f,
    val cursorScale: Float = 1f,
    val lensGrid: Boolean = false,
    /** Set by walk-around tracking, whose camera picture has its own field of view; 0 otherwise. */
    val trackedFov: Float = 0f
)

/** Vertical field of view of one eye viewport, the same for the camera view and the virtual places. */
fun viewFov(scene: Scene, aspect: Float): Float =
    if (scene.trackedFov > 0f) scene.trackedFov else croppedFov(scene.cameraFov, aspect)

class XrRenderer(
    private val view: GLSurfaceView,
    private val head: () -> Pose6,
    private val postToMain: (() -> Unit) -> Unit
) : GLSurfaceView.Renderer {
    @Volatile var scene = Scene()
    /** While set, ARCore supplies the camera picture and the head position. */
    @Volatile var walk: WalkTracking? = null
    @Volatile var displayRotation = 1
    private var walkTexture = 0
    private var walkPicture = false
    /** Where the head is in this frame. */
    private var headAt = Vec3(0f, 0f, 0f)
    @Volatile var lastCameraFrameMs = 0L
        private set
    /** Frames drawn since the camera last delivered one. */
    @Volatile var framesWithoutCamera = 0
        private set
    @Volatile var fps = 0
        private set

    private val photoRequest = java.util.concurrent.atomic.AtomicReference<((ByteBuffer, Int, Int) -> Unit)?>(null)
    private var encoder: EncoderTarget? = null

    /** The next finished frame is handed to [result] on the GL thread. */
    fun requestPhoto(result: (ByteBuffer, Int, Int) -> Unit) = photoRequest.set(result)

    fun startEncoding(surface: Surface, w: Int, h: Int, onError: () -> Unit) = view.queueEvent {
        runCatching { encoder = EncoderTarget(surface, w, h) }.onFailure {
            Log.e("NexusXR", "Encoder failed", it)
            postToMain(onError)
        }
    }

    /** [done] runs once no more frames will reach the encoder. */
    fun stopEncoding(done: () -> Unit) = view.queueEvent {
        encoder?.release()
        encoder = null
        done()
    }

    private class External(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
        val matrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val fresh = AtomicBoolean(false)
        var hasFrame = false
        fun latch() {
            if (fresh.getAndSet(false)) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(matrix)
                hasFrame = true
            }
        }
    }

    private class WindowSlot(var width: Int, var height: Int, val onSurface: (Surface) -> Unit) { var external: External? = null }
    private class Title(val key: String, val texture: Int, val width: Int)
    private class Target(val fbo: Int, val texture: Int, val width: Int, val height: Int)

    // All fields below are used on the GL thread only.
    private val slots = HashMap<Int, WindowSlot>()
    private val titles = HashMap<Int, Title>()
    private var ready = false
    private var camera: External? = null
    private var cameraRequest: SurfaceRequest? = null
    private var pendingCamera: SurfaceRequest? = null
    private var width = 1
    private var height = 1
    private val icons = HashMap<Int, Int>()
    private var photoTexture = 0
    /** Kept so the picture can be uploaded again if Android drops the graphics context. */
    private var photo: Bitmap? = null

    fun setPhoto(bitmap: Bitmap?) = view.queueEvent {
        photo = bitmap
        if (ready) uploadPhoto()
    }

    private fun uploadPhoto() {
        if (photoTexture != 0) glDeleteTextures(1, intArrayOf(photoTexture), 0)
        photoTexture = 0
        val bitmap = photo ?: return
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        glActiveTexture(GL_TEXTURE0)
        photoTexture = ids[0]
    }
    private class Label(val texture: Int, val width: Int, val height: Int)
    private val labels = HashMap<SceneMessage, Label>()
    private var blurA: Target? = null
    private var blurB: Target? = null
    private var eye: Target? = null
    private var frames = 0
    private var fpsSince = 0L
    private var logged = false

    private lateinit var cameraProgram: Program
    private lateinit var skyProgram: Program
    private lateinit var blurProgram: Program
    private lateinit var glassProgram: Program
    private lateinit var contentProgram: Program
    private lateinit var textureProgram: Program
    private lateinit var flatProgram: Program
    private lateinit var ringProgram: Program
    private lateinit var lensProgram: Program
    private lateinit var shadowProgram: Program

    private val viewProjection = FloatArray(16)
    private val quad = floats((SEGMENTS + 1) * 2 * 5)
    private val hull = floats(4096)
    private val lines = floats(2 * 2 * 32)

    private val glExecutor = Executor { view.queueEvent(it) }

    // ---- Called from the main thread ----

    fun provideCamera(request: SurfaceRequest) = view.queueEvent {
        if (ready) bindCamera(request) else pendingCamera = request
    }

    fun createWindow(id: Int, w: Int, h: Int, onSurface: (Surface) -> Unit) = view.queueEvent {
        val slot = WindowSlot(w, h, onSurface)
        slots[id] = slot
        if (ready) attach(slot)
    }

    /** Changes the buffer size of a window's texture. [then] runs on the main thread afterwards. */
    fun resizeWindow(id: Int, w: Int, h: Int, then: () -> Unit) = view.queueEvent {
        slots[id]?.let {
            it.width = w
            it.height = h
            it.external?.surfaceTexture?.setDefaultBufferSize(w, h)
        }
        postToMain(then)
    }

    fun destroyWindow(id: Int) = view.queueEvent {
        slots.remove(id)?.external?.let(::release)
        titles.remove(id)?.let { glDeleteTextures(1, intArrayOf(it.texture), 0) }
    }

    // ---- GL thread ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        encoder = null
        // A new context: every old GL object is already gone.
        walkTexture = 0
        walkPicture = false
        titles.clear()
        icons.clear()
        labels.clear()
        photoTexture = 0
        uploadPhoto()
        blurA = null; blurB = null; eye = null
        camera = null
        cameraRequest?.invalidate()
        cameraRequest = null
        cameraProgram = Program(Shaders.SCREEN_VERTEX, Shaders.CAMERA)
        skyProgram = Program(Shaders.SCREEN_VERTEX, Shaders.SKY)
        blurProgram = Program(Shaders.SCREEN_VERTEX, Shaders.BLUR)
        glassProgram = Program(Shaders.WORLD_VERTEX, Shaders.GLASS)
        contentProgram = Program(Shaders.WORLD_VERTEX, Shaders.CONTENT)
        textureProgram = Program(Shaders.WORLD_VERTEX, Shaders.TEXTURE)
        flatProgram = Program(Shaders.FLAT_VERTEX, Shaders.FLAT)
        ringProgram = Program(Shaders.WORLD_VERTEX, Shaders.RING)
        lensProgram = Program(Shaders.SCREEN_VERTEX, Shaders.LENS)
        shadowProgram = Program(Shaders.WORLD_VERTEX, Shaders.SHADOW)
        ready = true
        slots.values.forEach(::attach)
        pendingCamera?.let(::bindCamera)
        pendingCamera = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    private fun external(w: Int, h: Int, onFrame: () -> Unit): External {
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        glBindTexture(target, id[0])
        parameters(target)
        val texture = SurfaceTexture(id[0]).apply { setDefaultBufferSize(w, h) }
        val result = External(id[0], texture, Surface(texture))
        texture.setOnFrameAvailableListener { result.fresh.set(true); onFrame() }
        return result
    }

    private fun release(e: External) {
        e.surfaceTexture.setOnFrameAvailableListener(null)
        // The producer (virtual display) is released on the main thread before the surface.
        postToMain { e.surface.release(); view.queueEvent { e.surfaceTexture.release() } }
        glDeleteTextures(1, intArrayOf(e.texture), 0)
    }

    private fun attach(slot: WindowSlot) {
        val e = external(slot.width, slot.height) {}
        slot.external = e
        postToMain { slot.onSurface(e.surface) }
    }

    private fun bindCamera(request: SurfaceRequest) {
        camera?.let { old ->
            old.surfaceTexture.setOnFrameAvailableListener(null)
            glDeleteTextures(1, intArrayOf(old.texture), 0)
        }
        val e = external(request.resolution.width, request.resolution.height) {
            if (lastCameraFrameMs == 0L) Log.i("NexusXR", "First camera frame")
            lastCameraFrameMs = android.os.SystemClock.uptimeMillis()
            framesWithoutCamera = 0
        }
        camera = e
        cameraRequest = request
        request.provideSurface(e.surface, glExecutor) {
            e.surface.release()
            e.surfaceTexture.release()
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = scene
        countFrame()
        framesWithoutCamera++
        slots.values.forEach { it.external?.latch() }
        runCatching { camera?.latch() }.onFailure { Log.w("NexusXR", "Camera frame skipped", it) }

        glDisable(GL_DEPTH_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, width, height)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (s.blank) return

        dropUnusedLabels(s)
        // In headset mode each eye's picture is placed where its lens is.
        val rects = if (s.stereo) List(2) { s.fit.eye(it, width, height) } else null
        val eyeWidth = rects?.get(0)?.width ?: width
        val eyeHeight = rects?.get(0)?.height ?: height
        val aspect = eyeWidth.toFloat() / eyeHeight
        updateWalk(eyeWidth, eyeHeight)
        // Read after the walk update, so the windows match the picture behind them.
        val pose = head()
        val q = pose.orientation
        headAt = pose.position
        val blurred = renderBlur(s, q, aspect, eyeWidth, eyeHeight)

        if (rects == null) {
            drawEye(s, q, aspect, 0f, null, blurred)
        } else {
            val buffer = eye?.takeIf { it.width == eyeWidth && it.height == eyeHeight } ?: run {
                eye?.let(::delete)
                target(eyeWidth, eyeHeight).also { eye = it }
            }
            for ((side, rect) in rects.withIndex()) {
                drawEye(s, q, aspect, if (side == 0) -s.ipd / 2 else s.ipd / 2, buffer, blurred)
                glBindFramebuffer(GL_FRAMEBUFFER, 0)
                glViewport(rect.x, rect.y, rect.width, rect.height)
                // A picture never spills into the other eye's half of the screen.
                glEnable(GL_SCISSOR_TEST)
                glScissor(side * (width / 2), 0, width / 2, height)
                glDisable(GL_BLEND)
                lensProgram.use()
                lensProgram.texture("uTexture", buffer.texture, GL_TEXTURE_2D, 0)
                lensProgram.set("uK", s.distortion)
                lensProgram.set("uAspect", aspect)
                lensProgram.set("uZoom", s.lensZoom)
                lensProgram.set("uGrid", if (s.lensGrid) 1 else 0)
                fullScreen(lensProgram)
                glDisable(GL_SCISSOR_TEST)
            }
        }
        if (!logged) { logged = true; Log.i("NexusXR", "First XR frame rendered") }
        capture()
    }

    private fun updateWalk(eyeWidth: Int, eyeHeight: Int) {
        val tracking = walk
        if (tracking == null) {
            walkPicture = false
            return
        }
        if (walkTexture == 0) {
            val id = IntArray(1)
            glGenTextures(1, id, 0)
            glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
            parameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            walkTexture = id[0]
        }
        if (tracking.update(walkTexture, displayRotation, eyeWidth, eyeHeight)) {
            if (!walkPicture) Log.i("NexusXR", "First camera frame")
            walkPicture = true
            lastCameraFrameMs = android.os.SystemClock.uptimeMillis()
            framesWithoutCamera = 0
        }
    }

    private val hasCameraPicture get() = if (walk != null) walkPicture else camera?.hasFrame == true

    private fun capture() {
        photoRequest.getAndSet(null)?.let { result ->
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            // The saved picture is opaque.
            for (i in 3 until pixels.capacity() step 4) pixels.put(i, -1)
            result(pixels, width, height)
        }
        encoder?.copyFrame(width, height)
    }

    private fun countFrame() {
        val now = android.os.SystemClock.uptimeMillis()
        frames++
        if (now - fpsSince >= 1000) {
            fps = frames
            frames = 0
            fpsSince = now
        }
    }

    private fun background(s: Scene, q: Quat, aspect: Float) {
        glDisable(GL_BLEND)
        if (s.passthrough && hasCameraPicture) {
            bindCameraProgram(s, aspect)
            fullScreen(cameraProgram)
        } else {
            skyProgram.use()
            val t = tan(Math.toRadians(viewFov(s, aspect) / 2.0)).toFloat()
            skyProgram.set("uTan", t * aspect, t)
            skyProgram.set("uHead", q.x, q.y, q.z, q.w)
            // Without a picture the 360° place shows the first one instead.
            val place = if (s.environment == Places.PHOTO && photoTexture == 0) 0 else s.environment
            skyProgram.set("uScene", place)
            if (place == Places.PHOTO) skyProgram.texture("uPhoto", photoTexture, GL_TEXTURE_2D, 1)
            fullScreen(skyProgram)
        }
        if (s.dim > 0.01f) {
            glEnable(GL_BLEND)
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            flatProgram.use()
            flatProgram.set("uColor", 0f, 0f, 0f, s.dim)
            fullScreen(flatProgram)
            glDisable(GL_BLEND)
        }
    }

    private fun bindCameraProgram(s: Scene, aspect: Float) {
        cameraProgram.use()
        val matrix = walk?.textureMatrix
        if (walk != null && matrix != null) {
            // ARCore's matrix already holds the crop and the turn.
            cameraProgram.texture("uTexture", walkTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            cameraProgram.matrix("uTransform", matrix)
            cameraProgram.set("uCrop", 1f, 1f)
            cameraProgram.set("uTurns", 0)
            return
        }
        val cam = camera ?: return
        cameraProgram.texture("uTexture", cam.texture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        cameraProgram.matrix("uTransform", cam.matrix)
        val crop = if (aspect > 4f / 3f) floatArrayOf(1f, (4f / 3f) / aspect) else floatArrayOf(aspect / (4f / 3f), 1f)
        cameraProgram.set("uCrop", crop[0], crop[1])
        cameraProgram.set("uTurns", (((s.displayDegrees / 90) + s.cameraTurns) % 4))
    }

    /** One shared, downsampled and blurred copy of the background serves every glass window. */
    private fun renderBlur(s: Scene, q: Quat, aspect: Float, eyeWidth: Int, eyeHeight: Int): Int {
        if (s.quality == 0) return 0
        val w = max(eyeWidth / 8, 16)
        val h = max(eyeHeight / 8, 16)
        if (blurA?.width != w || blurA?.height != h) {
            blurA?.let(::delete); blurB?.let(::delete)
            blurA = target(w, h); blurB = target(w, h)
        }
        val a = blurA!!
        val b = blurB!!
        glBindFramebuffer(GL_FRAMEBUFFER, a.fbo)
        glViewport(0, 0, w, h)
        background(s, q, aspect)
        repeat(if (s.quality == 2) 2 else 1) {
            for ((from, to) in listOf(a to b, b to a)) {
                glBindFramebuffer(GL_FRAMEBUFFER, to.fbo)
                blurProgram.use()
                blurProgram.texture("uTexture", from.texture, GL_TEXTURE_2D, 0)
                if (from === a) blurProgram.set("uStep", 1.4f / w, 0f) else blurProgram.set("uStep", 0f, 1.4f / h)
                fullScreen(blurProgram)
            }
        }
        return a.texture
    }

    private fun drawEye(s: Scene, q: Quat, aspect: Float, eyeOffset: Float, into: Target?, blurred: Int) {
        val w = into?.width ?: width
        val h = into?.height ?: height
        glBindFramebuffer(GL_FRAMEBUFFER, into?.fbo ?: 0)
        glViewport(0, 0, w, h)
        background(s, q, aspect)

        // View = translate(-eye) * inverse(head rotation).
        val rotation = FloatArray(16)
        val inverse = q.conjugate()
        listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f)).forEachIndexed { column, axis ->
            val r = inverse.rotate(axis)
            rotation[column * 4] = r.x; rotation[column * 4 + 1] = r.y; rotation[column * 4 + 2] = r.z
        }
        rotation[15] = 1f
        val view = FloatArray(16).also { Matrix.setIdentityM(it, 0); Matrix.translateM(it, 0, -eyeOffset, 0f, 0f) }
        val viewRotated = FloatArray(16).also {
            Matrix.multiplyMM(it, 0, view, 0, rotation, 0)
            Matrix.translateM(it, 0, -headAt.x, -headAt.y, -headAt.z)
        }
        val projection = FloatArray(16).also { Matrix.perspectiveM(it, 0, viewFov(s, aspect), aspect, 0.05f, 20f) }
        Matrix.multiplyMM(viewProjection, 0, projection, 0, viewRotated, 0)

        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA) // premultiplied alpha everywhere
        // Far to near; the list order (focus) breaks ties.
        for (win in paintOrder(s.windows, headAt)) drawWindow(s, win, w, h, blurred)

        if (s.occlusion && s.passthrough && hasCameraPicture) drawHandOcclusion(s, aspect)
        glEnable(GL_BLEND)
        drawHands(s)

        if (s.intro < 1f) drawIntro(s)
        drawMessages(s, q, aspect)

        for (cursor in s.cursors) {
            // A billboard facing the viewer, so both eyes see the cursor at the same 3D point.
            val p = cursor.position
            val right = q.rotate(Vec3(1f, 0f, 0f))
            val up = q.rotate(Vec3(0f, 1f, 0f))
            val size = (p - headAt).length * s.cursorScale * if (cursor.pinched) 0.017f else 0.024f
            ringProgram.use()
            ringProgram.set("uFill", if (cursor.pinched) 1f else 0f)
            ringProgram.set("uProgress", cursor.progress)
            putQuad(p - right * size - up * size, p + right * size - up * size, p - right * size + up * size, p + right * size + up * size)
            submitQuad(ringProgram)
        }
    }

    /** The view fades up from black when spatial mode opens. */
    private fun drawIntro(s: Scene) {
        val left = (1f - s.intro).coerceIn(0f, 1f)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        flatProgram.use()
        flatProgram.set("uColor", 0f, 0f, 0f, left * left)
        fullScreen(flatProgram)
    }

    /** Head-locked panels a little over a metre away, drawn once for each eye. */
    private fun drawMessages(s: Scene, q: Quat, aspect: Float) {
        if (s.messages.isEmpty()) return
        val distance = 1.2f
        val halfHeight = tan(Math.toRadians(viewFov(s, aspect) / 2.0)).toFloat() * distance
        val metresPerPixel = halfHeight * aspect * 2 * MESSAGE_SHARE / MESSAGE_PIXELS
        for (message in s.messages) {
            val label = labels.getOrPut(message) { label(message) }
            val halfW = label.width * metresPerPixel / 2
            val halfH = label.height * metresPerPixel / 2
            val y = message.height * halfHeight
            fun corner(x: Float, dy: Float) = headAt + q.rotate(Vec3(x, y + dy, -distance))
            textureProgram.use()
            textureProgram.texture("uTexture", label.texture, GL_TEXTURE_2D, 0)
            textureProgram.set("uAlpha", 1f)
            putQuad(corner(-halfW, -halfH), corner(halfW, -halfH), corner(-halfW, halfH), corner(halfW, halfH))
            submitQuad(textureProgram)
        }
    }

    private fun label(message: SceneMessage): Label {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textSize = 34f }
        val room = MESSAGE_PIXELS - 56
        val widest = message.text.split('\n').maxOf { Layout.getDesiredWidth(it, paint) }
        val textWidth = kotlin.math.ceil(widest).toInt().coerceIn(40, room)
        val layout = StaticLayout.Builder.obtain(message.text, 0, message.text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val w = textWidth + 56
        val h = layout.height + 32
        val texture = textTexture(w, h) { canvas, _ ->
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = message.tint }
            canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 30f, 30f, fill)
            canvas.translate(28f, 16f)
            layout.draw(canvas)
        }
        return Label(texture, w, h)
    }

    private fun dropUnusedLabels(s: Scene) {
        if (labels.isEmpty()) return
        val unused = labels.keys.filter { it !in s.messages }
        for (key in unused) labels.remove(key)?.let { glDeleteTextures(1, intArrayOf(it.texture), 0) }
    }

    private fun glass(s: Scene, blurred: Int, viewportW: Int, viewportH: Int, width: Float, height: Float, radius: Float, focus: Float, alpha: Float) {
        glassProgram.use()
        glassProgram.texture("uBlur", blurred, GL_TEXTURE_2D, 0)
        glassProgram.set("uViewport", viewportW.toFloat(), viewportH.toFloat())
        glassProgram.set("uSize", width, height)
        glassProgram.set("uRadius", radius)
        glassProgram.set("uQuality", s.quality)
        glassProgram.set("uFocus", focus)
        glassProgram.set("uAlpha", alpha)
    }

    private fun drawWindow(s: Scene, win: SpatialWindow, viewportW: Int, viewportH: Int, blurred: Int) {
        val alpha = s.alphas[win.id] ?: 1f
        val halfW = win.w / 2
        val halfH = win.h / 2
        val rim = 0.012f
        val panelTop = halfH + SpatialWindow.BAR
        val panelW = win.w + rim * 2
        val panelH = panelTop + halfH + rim * 2

        if (s.quality > 0) {
            val m = 0.07f
            shadowProgram.use()
            shadowProgram.set("uSize", panelW + m * 2, panelH + m * 2)
            shadowProgram.set("uMargin", m)
            shadowProgram.set("uAlpha", alpha)
            drawQuad(shadowProgram, win, -halfW - rim - m, -halfH - rim - m - 0.025f, halfW + rim + m, panelTop + rim + m - 0.025f)
        }

        glass(s, blurred, viewportW, viewportH, panelW, panelH, 0.062f, if (win.id == s.focusedId) 0.55f else 0f, alpha)
        drawQuad(glassProgram, win, -halfW - rim, -halfH - rim, halfW + rim, panelTop + rim)

        slots[win.id]?.external?.takeIf { it.hasFrame }?.let { content ->
            contentProgram.use()
            contentProgram.texture("uTexture", content.texture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            contentProgram.matrix("uTransform", content.matrix)
            contentProgram.set("uSize", win.w, win.h)
            contentProgram.set("uAlpha", alpha)
            drawQuad(contentProgram, win, -halfW, -halfH, halfW, halfH)
        }

        val title = title(win, s.titles[win.id].orEmpty())
        val titleHalf = (title.width / 640f / 2).coerceAtMost(halfW)
        textureProgram.use()
        textureProgram.texture("uTexture", title.texture, GL_TEXTURE_2D, 0)
        textureProgram.set("uAlpha", alpha)
        drawQuad(textureProgram, win, -titleHalf, halfH, titleHalf, panelTop)

        // Separate glass buttons under the window, each with its own space.
        for (control in controlLayout(win)) {
            val aimed = (win.id to control.region) in s.highlights
            val grow = if (aimed) 1.16f else 1f
            val hw = control.halfWidth * grow
            var hh = control.halfHeight * grow
            val icon = when (control.region) {
                Region.CLOSE -> R.drawable.ic_ui_close
                Region.MINIMIZE -> R.drawable.ic_ui_minimize
                Region.FARTHER -> R.drawable.ic_ui_farther
                Region.NEARER -> R.drawable.ic_ui_nearer
                Region.RESIZE -> R.drawable.ic_ui_resize
                Region.BACK -> R.drawable.ic_ui_back
                else -> 0
            }
            if (control.region == Region.GRAB) hh = 0.013f * grow
            if (control.region != Region.RESIZE) {
                glass(s, blurred, viewportW, viewportH, hw * 2, hh * 2, minOf(hw, hh), if (aimed) 1f else 0.12f, alpha)
                drawQuad(glassProgram, win, control.x - hw, control.y - hh, control.x + hw, control.y + hh)
            }
            if (icon != 0) {
                val half = minOf(hw, hh) * if (control.region == Region.RESIZE) 1.15f else 0.62f
                textureProgram.use()
                textureProgram.texture("uTexture", icon(icon), GL_TEXTURE_2D, 0)
                textureProgram.set("uAlpha", if (control.region == Region.RESIZE && !aimed) alpha * 0.7f else alpha)
                drawQuad(textureProgram, win, control.x - half, control.y - half, control.x + half, control.y + half)
            }
        }
    }

    /**
     * Draws the camera image again inside a rough outline of each hand, so hands appear in front
     * of the windows. The outline comes from the landmarks, so its edges are approximate.
     */
    private fun drawHandOcclusion(s: Scene, aspect: Float) {
        hull.clear()
        var count = 0
        fun vertex(x: Float, y: Float) {
            if (hull.remaining() >= 2) { hull.put(x / aspect * 2 - 1).put(1 - y * 2); count++ }
        }
        for (hand in s.handShapes) {
            if (hand.size < 21) continue
            // Work in square units, where one unit is the viewport height.
            fun x(i: Int) = hand[i].x * aspect
            fun y(i: Int) = hand[i].y
            val palm = maxOf(kotlin.math.hypot(x(0) - x(9), y(0) - y(9)), kotlin.math.hypot(x(5) - x(17), y(5) - y(17)))
            val radius = palm * 0.17f
            for ((a, b) in BONES) {
                val dx = x(b) - x(a)
                val dy = y(b) - y(a)
                val length = kotlin.math.hypot(dx, dy)
                if (length < 1e-5f) continue
                val nx = -dy / length * radius
                val ny = dx / length * radius
                val ex = dx / length * radius * 0.7f
                val ey = dy / length * radius * 0.7f
                vertex(x(a) + nx - ex, y(a) + ny - ey); vertex(x(a) - nx - ex, y(a) - ny - ey); vertex(x(b) + nx + ex, y(b) + ny + ey)
                vertex(x(b) + nx + ex, y(b) + ny + ey); vertex(x(a) - nx - ex, y(a) - ny - ey); vertex(x(b) - nx + ex, y(b) - ny + ey)
            }
            for ((a, b, c) in PALM) { vertex(x(a), y(a)); vertex(x(b), y(b)); vertex(x(c), y(c)) }
        }
        if (count == 0) return
        glDisable(GL_BLEND)
        bindCameraProgram(s, aspect)
        hull.flip()
        val position = cameraProgram.attribute("aPosition")
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, hull)
        glEnableVertexAttribArray(position)
        glDrawArrays(GL_TRIANGLES, 0, count)
        glDisableVertexAttribArray(position)
    }

    private fun drawHands(s: Scene) {
        if (s.hands.isEmpty()) return
        flatProgram.use()
        flatProgram.set("uColor", 0.35f, 0.75f, 0.85f, 0.7f)
        for (hand in s.hands) {
            if (hand.size < 21) continue
            lines.clear()
            for ((a, b) in BONES) for (i in intArrayOf(a, b)) lines.put(hand[i].x * 2 - 1).put(1 - hand[i].y * 2)
            lines.flip()
            val position = flatProgram.attribute("aPosition")
            glEnableVertexAttribArray(position)
            glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, lines)
            glLineWidth(3f)
            glDrawArrays(GL_LINES, 0, BONES.size * 2)
            glDisableVertexAttribArray(position)
        }
    }

    /** A rectangle in window coordinates; curved windows are split into a strip that follows the arc. */
    private fun drawQuad(program: Program, win: SpatialWindow, left: Float, bottom: Float, right: Float, top: Float) {
        val segments = if (win.curved) SEGMENTS else 1
        quad.clear()
        for (i in 0..segments) {
            val u = i.toFloat() / segments
            val x = left + (right - left) * u
            for ((y, v) in listOf(bottom to 0f, top to 1f)) {
                val p = win.point(x, y)
                quad.put(p.x).put(p.y).put(p.z).put(u).put(v)
            }
        }
        submit(program, (segments + 1) * 2)
    }

    private fun putQuad(bl: Vec3, br: Vec3, tl: Vec3, tr: Vec3) {
        quad.clear()
        val uv = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        listOf(bl, br, tl, tr).forEachIndexed { i, p -> quad.put(p.x).put(p.y).put(p.z).put(uv[i * 2]).put(uv[i * 2 + 1]) }
    }

    private fun submitQuad(program: Program) = submit(program, 4)

    private fun submit(program: Program, vertices: Int) {
        program.matrix("uViewProjection", viewProjection)
        val position = program.attribute("aPosition")
        val uv = program.attribute("aUv")
        quad.position(0)
        glVertexAttribPointer(position, 3, GL_FLOAT, false, 20, quad)
        glEnableVertexAttribArray(position)
        quad.position(3)
        glVertexAttribPointer(uv, 2, GL_FLOAT, false, 20, quad)
        glEnableVertexAttribArray(uv)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, vertices)
        glDisableVertexAttribArray(position)
        glDisableVertexAttribArray(uv)
        quad.position(0)
    }

    private val screen = floats(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private fun fullScreen(program: Program) {
        val position = program.attribute("aPosition")
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, screen)
        glEnableVertexAttribArray(position)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(position)
    }

    private fun title(win: SpatialWindow, text: String): Title {
        val h = (SpatialWindow.BAR * 640).toInt()
        val room = (win.w * 640).toInt().coerceAtLeast(64)
        val key = "$text:${room / 8}"
        titles[win.id]?.let {
            if (it.key == key) return it
            glDeleteTextures(1, intArrayOf(it.texture), 0)
        }
        val measure = Paint().apply { textSize = h * 0.52f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val w = (measure.measureText(text).toInt() + h).coerceIn(64, room)
        val texture = textTexture(w, h) { canvas, paint ->
            paint.textSize = h * 0.52f
            paint.typeface = measure.typeface
            paint.textAlign = Paint.Align.CENTER
            val length = paint.measureText(text)
            if (length > w - h * 0.4f) paint.textSize *= (w - h * 0.4f) / length
            canvas.drawText(text, w / 2f, h * 0.7f, paint)
        }
        return Title(key, texture, w).also { titles[win.id] = it }
    }

    private fun icon(resource: Int) = icons.getOrPut(resource) {
        textTexture(96, 96) { canvas, _ ->
            view.context.getDrawable(resource)?.apply { setBounds(0, 0, 96, 96); draw(canvas) }
        }
    }

    private fun textTexture(w: Int, h: Int, draw: (Canvas, Paint) -> Unit): Int {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt()) }
        draw(Canvas(bitmap), paint)
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(GL_TEXTURE_2D, id[0])
        parameters(GL_TEXTURE_2D)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return id[0]
    }

    private fun parameters(target: Int) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun target(w: Int, h: Int): Target {
        val texture = IntArray(1)
        val fbo = IntArray(1)
        glGenTextures(1, texture, 0)
        glBindTexture(GL_TEXTURE_2D, texture[0])
        parameters(GL_TEXTURE_2D)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glGenFramebuffers(1, fbo, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture[0], 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }
        return Target(fbo[0], texture[0], w, h)
    }

    private fun delete(t: Target) {
        glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
        glDeleteTextures(1, intArrayOf(t.texture), 0)
    }

    private fun floats(count: Int) = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private companion object {
        const val SEGMENTS = 24
        const val MESSAGE_PIXELS = 720
        /** The widest message as a share of one eye's view. */
        const val MESSAGE_SHARE = 0.62f
        val PALM = listOf(Triple(0, 1, 5), Triple(0, 5, 9), Triple(0, 9, 13), Triple(0, 13, 17), Triple(1, 2, 5))
        val BONES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17
        )
    }
}

class Program(vertex: String, fragment: String) {
    private val id = glCreateProgram()
    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()

    init {
        for ((type, source) in listOf(GL_VERTEX_SHADER to vertex, GL_FRAGMENT_SHADER to fragment)) {
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            val ok = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GL_TRUE) { "Shader: ${glGetShaderInfoLog(shader)}" }
            glAttachShader(id, shader)
            glDeleteShader(shader)
        }
        glLinkProgram(id)
        val ok = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] == GL_TRUE) { "Link: ${glGetProgramInfoLog(id)}" }
    }

    fun use() = glUseProgram(id)
    private fun uniform(name: String) = uniforms.getOrPut(name) { glGetUniformLocation(id, name) }
    fun attribute(name: String) = attributes.getOrPut(name) { glGetAttribLocation(id, name) }
    fun set(name: String, v: Int) = glUniform1i(uniform(name), v)
    fun set(name: String, v: Float) = glUniform1f(uniform(name), v)
    fun set(name: String, x: Float, y: Float) = glUniform2f(uniform(name), x, y)
    fun set(name: String, x: Float, y: Float, z: Float, w: Float) = glUniform4f(uniform(name), x, y, z, w)
    fun matrix(name: String, m: FloatArray) = glUniformMatrix4fv(uniform(name), 1, false, m, 0)
    fun texture(name: String, texture: Int, target: Int, unit: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(target, texture)
        set(name, unit)
    }
}
