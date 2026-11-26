package com.nexus.visionos.xr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaRecorder
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.widget.TextView
import com.nexus.visionos.R
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

private fun newEntry(context: Context, video: Boolean): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "Nexus-${stamp()}" + if (video) ".mp4" else ".png")
        put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/NexusVision")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    return context.contentResolver.insert(collection, values)
}

private fun publish(context: Context, uri: Uri) {
    context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
}

/** Saves raw bottom-up RGBA pixels as a PNG in the gallery. Call off the main thread. */
fun savePhoto(context: Context, pixels: ByteBuffer, width: Int, height: Int): Boolean {
    var uri: Uri? = null
    return try {
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(pixels)
        val upright = Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        uri = newEntry(context, video = false) ?: return false
        context.contentResolver.openOutputStream(uri)!!.use { check(upright.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        publish(context, uri)
        Log.i("NexusXR", "Photo saved ${width}x$height")
        true
    } catch (e: Exception) {
        Log.e("NexusXR", "Photo failed", e)
        uri?.let { context.contentResolver.delete(it, null, null) }
        false
    }
}

/** A silent MP4 of the spatial view, fed by the renderer. */
class VideoCapture private constructor(
    private val context: Context,
    private val recorder: MediaRecorder,
    private val file: ParcelFileDescriptor,
    private val uri: Uri,
    val width: Int,
    val height: Int
) {
    val surface: Surface get() = recorder.surface
    val startedAt = SystemClock.elapsedRealtime()

    /** Returns false when nothing usable was recorded. Stop feeding frames first. */
    fun finish(): Boolean {
        val saved = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        file.close()
        if (saved) publish(context, uri) else context.contentResolver.delete(uri, null, null)
        Log.i("NexusXR", if (saved) "Recording saved" else "Recording discarded")
        return saved
    }

    companion object {
        fun start(context: Context, viewWidth: Int, viewHeight: Int): VideoCapture? {
            val width = minOf(1280, viewWidth) / 16 * 16
            val height = (width * viewHeight / viewWidth) / 16 * 16
            if (width <= 0 || height <= 0) return null
            val uri = newEntry(context, video = true) ?: return null
            var file: ParcelFileDescriptor? = null
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            return try {
                file = context.contentResolver.openFileDescriptor(uri, "w")!!
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(width, height)
                recorder.setVideoFrameRate(30)
                recorder.setVideoEncodingBitRate(6_000_000)
                recorder.setOutputFile(file.fileDescriptor)
                recorder.prepare()
                recorder.start()
                VideoCapture(context, recorder, file, uri, width, height)
            } catch (e: Exception) {
                Log.e("NexusXR", "Recording could not start", e)
                recorder.release()
                file?.close()
                context.contentResolver.delete(uri, null, null)
                null
            }
        }
    }
}

/** Copies each finished frame into the video encoder. GL thread only. */
class EncoderTarget(surface: Surface, private val width: Int, private val height: Int) {
    private val display = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private val target: EGLSurface
    private var lastNs = 0L

    init {
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
        target = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(target != EGL14.EGL_NO_SURFACE) { "No encoder surface: ${EGL14.eglGetError()}" }
    }

    fun copyFrame(sourceWidth: Int, sourceHeight: Int) {
        val now = System.nanoTime()
        if (now - lastNs < 32_000_000) return
        lastNs = now
        val screen = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        // Read from the finished screen buffer and write into the encoder.
        if (!EGL14.eglMakeCurrent(display, target, screen, context)) return
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        EGLExt.eglPresentationTimeANDROID(display, target, now)
        EGL14.eglSwapBuffers(display, target)
        EGL14.eglMakeCurrent(display, screen, screen, context)
    }

    fun release() { EGL14.eglDestroySurface(display, target) }
}

/** Photo and video buttons. The app shows a recording indicator for as long as it records. */
internal fun captureApp(context: Context, shell: Shell): SpatialApp {
    lateinit var status: TextView
    lateinit var record: android.widget.LinearLayout
    var recording: Boolean? = null
    val view = context.vertical {
        status = text(context.getString(R.string.capture_note), 13f).apply { gravity = Gravity.CENTER }
        row(1f) {
            iconKey(R.drawable.ic_ui_camera, context.getString(R.string.take_photo)) { shell.capturePhoto() }
            record = this
        }
        row(height = context.px(60)) {
            iconKey(R.drawable.ic_ui_photos, context.getString(R.string.app_photos)) { shell.open(com.nexus.xr.AppType.PHOTOS) }
        }
    }
    var button: android.widget.LinearLayout? = null
    val stop = ticker(400) {
        if (recording != shell.isRecording()) {
            recording = shell.isRecording()
            button?.let { record.removeView(it) }
            val on = recording == true
            button = record.iconKey(
                if (on) R.drawable.ic_ui_stop else R.drawable.ic_ui_record,
                context.getString(if (on) R.string.stop_recording else R.string.start_recording), active = on
            ) { shell.toggleRecording() }
        }
    }
    return SpatialApp(view, stop)
}
