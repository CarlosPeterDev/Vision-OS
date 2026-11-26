package com.nexus.visionos.xr

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import java.util.concurrent.Executors

/** Decodes pictures off the main thread, never larger than needed. */
private class Pictures(private val context: Context) {
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
        shown = (shell.pickedPhotos() + ownCaptures(context)).distinct()
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

private class Document(val id: String, val name: String, val mime: String, val size: Long) {
    val folder get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/** Read-only browsing of one folder the user chose in the system picker. */
internal fun files(context: Context, shell: Shell): SpatialApp {
    val pictures = Pictures(context)
    val path = mutableListOf<Pair<String, String>>()
    var tree: Uri? = null
    lateinit var body: FrameLayout
    lateinit var location: TextView

    fun children(root: Uri, parent: String): List<Document> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parent)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
        )
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext() && size < 500) add(Document(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3))) }
        }.orEmpty()
    }

    fun message(text: String) {
        body.removeAllViews()
        body.addView(TextView(context).apply { this.text = text; setTextColor(-1); gravity = Gravity.CENTER })
    }

    lateinit var list: () -> Unit
    fun preview(root: Uri, document: Document) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, document.id)
        when {
            document.mime.startsWith("video/") || document.mime.startsWith("audio/") -> shell.play(uri)
            document.mime.startsWith("image/") -> {
                body.removeAllViews()
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener { list() } }
                body.addView(image)
                pictures.load(uri, 1400, image)
            }
            document.mime.startsWith("text/") || document.mime == "application/json" -> {
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0
                        while (total < buffer.size) {
                            val read = stream.read(buffer, total, buffer.size - total)
                            if (read < 0) break
                            total += read
                        }
                        String(buffer, 0, total)
                    }
                }.getOrNull() ?: return message(context.getString(R.string.file_unreadable))
                body.removeAllViews()
                body.addView(context.scrolling {
                    key(context.getString(R.string.dismiss)) { list() }
                    text(content, 13f)
                })
            }
            else -> message(context.getString(R.string.file_unsupported))
        }
    }
    list = list@{
        val root = tree ?: return@list message(context.getString(R.string.files_empty))
        location.text = path.joinToString(" / ") { it.second }
        val documents = runCatching { children(root, path.last().first) }.getOrNull()
        if (documents == null) message(context.getString(R.string.file_unreadable)) else {
            body.removeAllViews()
            body.addView(context.scrolling {
                documents.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() })).forEach { document ->
                    val detail = if (document.folder) "" else "  ·  " + Formatter.formatShortFileSize(context, document.size)
                    val icon = when {
                        document.folder -> R.drawable.ic_ui_files
                        document.mime.startsWith("image/") -> R.drawable.ic_ui_photos
                        document.mime.startsWith("video/") || document.mime.startsWith("audio/") -> R.drawable.ic_ui_media
                        else -> R.drawable.ic_ui_notes
                    }
                    val button = key(document.name + detail) {
                        if (document.folder) { path += document.id to document.name; list() } else preview(root, document)
                    }
                    button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    button.setPadding(context.px(14), 0, context.px(10), 0)
                    button.compoundDrawablePadding = context.px(10)
                    button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                }
            })
        }
    }
    val view = context.vertical {
        row(height = context.px(52)) {
            iconKey(R.drawable.ic_ui_up) { if (path.size > 1) path.removeAt(path.lastIndex); list() }
            iconKey(R.drawable.ic_ui_files, context.getString(R.string.choose_folder), 3f) { shell.pickFolder() }
        }
        location = text(size = 12f)
        body = FrameLayout(context)
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    var version = -1
    val stop = ticker(700) {
        if (version != shell.libraryVersion()) {
            version = shell.libraryVersion()
            val next = shell.pickedFolder()
            if (next != tree) {
                tree = next
                path.clear()
                if (next != null) path += DocumentsContract.getTreeDocumentId(next) to context.getString(R.string.chosen_folder)
            }
            list()
        }
    }
    return SpatialApp(view) { stop(); pictures.close() }
}
