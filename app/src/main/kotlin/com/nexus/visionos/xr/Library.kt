package com.nexus.visionos.xr

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import java.util.concurrent.Executors

/** Decodes pictures off the main thread, never larger than needed. */
internal class Pictures(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    fun load(uri: Uri, target: Int, into: ImageView) {
        into.tag = uri
        executor.execute {
            if (closed) return@execute
            val bitmap: Bitmap? = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val largest = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(maxOf(1, largest / target))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            main.post { if (!closed && into.tag == uri) into.setImageBitmap(bitmap) }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}

/** Pictures this app saved itself, which need no storage permission. */
private fun ownCaptures(context: Context): List<Uri> = runCatching {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    context.contentResolver.query(
        collection, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("%NexusVision%"), "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        buildList { while (cursor.moveToNext() && size < 60) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
    }
}.getOrNull().orEmpty()

internal fun photos(context: Context, shell: Shell): SpatialApp {
    val pictures = Pictures(context)
    lateinit var body: FrameLayout
    var shown = emptyList<Uri>()

    lateinit var grid: () -> Unit
    fun viewer(index: Int) {
        body.removeAllViews()
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pictures.load(shown[index], 1400, image)
        body.addView(context.vertical {
            addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
            row(height = context.px(52)) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                iconKey(R.drawable.ic_ui_back) { viewer((index + shown.size - 1) % shown.size) }
                iconKey(R.drawable.ic_ui_launcher) { grid() }
                iconKey(R.drawable.ic_ui_forward) { viewer((index + 1) % shown.size) }
            }
        })
    }
    grid = {
        body.removeAllViews()
        shown = (shell.pickedPhotos() + importedPictures(context) + ownCaptures(context)).distinct()
        if (shown.isEmpty()) body.addView(TextView(context).apply {
            text = context.getString(R.string.photos_empty)
            setTextColor(-1)
            gravity = Gravity.CENTER
        }) else body.addView(context.scrolling {
            shown.indices.chunked(4).forEach { chunk ->
                row(height = context.px(104)) {
                    chunk.forEach { index ->
                        val thumb = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = LiquidGlass(radius = context.px(14).toFloat())
                            clipToOutline = true
                            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                            isClickable = true
                            foreground = glassStates(body = 0x04, radius = context.px(14).toFloat())
                            setOnClickListener { viewer(index) }
                        }
                        pictures.load(shown[index], 240, thumb)
                        addView(thumb, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(context.px(5), context.px(5), context.px(5), context.px(5)) })
                    }
                    repeat(4 - chunk.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                }
            }
        })
    }
    val view = context.vertical {
        row(height = context.px(52)) { iconKey(R.drawable.ic_ui_photos, context.getString(R.string.choose_photos)) { shell.pickPhotos() } }
        body = FrameLayout(context)
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    var version = -1
    val stop = ticker(700) { if (version != shell.libraryVersion()) { version = shell.libraryVersion(); grid() } }
    return SpatialApp(view) { stop(); pictures.close() }
}
