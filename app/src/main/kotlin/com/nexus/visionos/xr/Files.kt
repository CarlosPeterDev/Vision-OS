package com.nexus.visionos.xr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.xr.AppType
import java.io.File
import java.util.concurrent.Executors

private const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
private const val MAX_TEXT_BYTES = 256 * 1024

/** The app's private "My files" folder, which holds copies of whatever the user imported. */
object Imports {
    fun folder(context: Context) = File(context.applicationContext.filesDir, "imports").apply { mkdirs() }

    /** Copies one picked file in. Call off the main thread. */
    fun add(context: Context, source: Uri): Boolean {
        var target: File? = null
        return try {
            var name = "file"
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0).orEmpty()
            }
            val file = free(folder(context), safeName(name))
            val partial = File(file.path + ".part")
            target = partial
            context.contentResolver.openInputStream(source)!!.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_IMPORT_BYTES && partial.parentFile!!.usableSpace > buffer.size * 4L) { "Too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(partial.renameTo(file))
            true
        } catch (e: Exception) {
            target?.delete()
            false
        }
    }

    /** A plain file name only: no folders, hidden files or control characters. */
    private fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trimStart('.').takeLast(100).ifBlank { "file" }
        .let { if (it.endsWith(".part")) "$it.file" else it }

    private fun free(folder: File, name: String): File {
        var file = File(folder, name)
        var n = 2
        val base = name.substringBeforeLast('.', name)
        val extension = if ('.' in name) "." + name.substringAfterLast('.') else ""
        while (file.exists()) file = File(folder, "$base ($n)$extension").also { n++ }
        return file
    }

    fun list(context: Context): List<File> =
        folder(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Saves a copy to the phone's Downloads folder, where other apps can see it. */
    fun export(context: Context, file: File): Boolean {
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(file.name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            uri?.let { context.contentResolver.delete(it, null, null) }
            false
        }
    }
}

internal fun importedPictures(context: Context): List<Uri> =
    Imports.list(context).filter { Imports.mime(it.name).startsWith("image/") }.map { Uri.fromFile(it) }

private class Item(val name: String, val mime: String, val size: Long, val uri: Uri, val file: File?, val folderId: String?)

private val TEXT_ENDINGS = setOf("txt", "md", "csv", "json", "log", "xml", "kt", "java", "py", "js", "html", "css", "dart", "yml", "yaml")

/** "My files" (imported copies, which can be edited, exported and deleted) and a read-only phone folder. */
internal fun files(context: Context, shell: Shell): SpatialApp {
    val pictures = Pictures(context)
    val executor = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val path = mutableListOf<Pair<String, String>>()
    var tree: Uri? = null
    var mine = true
    var closePdf: () -> Unit = {}
    var flush: () -> Unit = {}
    lateinit var body: FrameLayout
    lateinit var location: TextView
    lateinit var tabs: LinearLayout
    lateinit var list: () -> Unit

    fun leave() {
        flush()
        flush = {}
        closePdf()
        closePdf = {}
        body.removeAllViews()
    }

    fun message(text: String) {
        leave()
        body.addView(TextView(context).apply { this.text = text; setTextColor(-1); gravity = Gravity.CENTER })
    }

    fun folderChildren(root: Uri, parent: String): List<Item> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parent)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE
        )
        return context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext() && size < 500) {
                    val id = c.getString(0)
                    val mime = c.getString(2).orEmpty()
                    add(
                        Item(
                            c.getString(1).orEmpty(), mime, c.getLong(3), DocumentsContract.buildDocumentUriUsingTree(root, id), null,
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) id else null
                        )
                    )
                }
            }
        }.orEmpty()
    }

    fun showText(item: Item) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(item.uri)!!.use { stream ->
                val buffer = ByteArray(MAX_TEXT_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }.getOrNull() ?: return message(context.getString(R.string.file_unreadable))
        val cut = bytes.size > MAX_TEXT_BYTES
        val content = String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES))
        leave()
        // Only a whole imported file may be edited; a cut or foreign one is shown read-only.
        val file = item.file?.takeIf { !cut }
        if (file == null) {
            body.addView(context.scrolling { key(context.getString(R.string.dismiss)) { list() }; text(content, 13f) })
            return
        }
        val input = editor(context).apply { gravity = Gravity.TOP or Gravity.START; textSize = 14f; setText(content) }
        var saved = content
        flush = {
            val now = input.text.toString()
            if (now != saved && file.exists()) runCatching { file.writeText(now) }.onSuccess { saved = now }
        }
        body.addView(context.vertical {
            row(height = context.px(48)) {
                key(context.getString(R.string.save_close), 2f) { list() }
                iconKey(R.drawable.ic_ui_keyboard) { input.requestFocus(); shell.open(AppType.KEYBOARD) }
            }
            addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }

    fun showPdf(item: Item) {
        var descriptor: ParcelFileDescriptor? = null
        val renderer = runCatching {
            descriptor = context.contentResolver.openFileDescriptor(item.uri, "r")
            PdfRenderer(descriptor!!)
        }.getOrNull()
        if (renderer == null) {
            descriptor?.close()
            return message(context.getString(R.string.file_unreadable))
        }
        leave()
        var page = 0
        val image = ImageView(context).apply { adjustViewBounds = true }
        lateinit var number: TextView
        val scroll = android.widget.ScrollView(context).apply { addView(image, FrameLayout.LayoutParams(-1, -2)) }
        fun show() {
            val bitmap = runCatching {
                renderer.openPage(page).use { p ->
                    val width = 900
                    val height = (width.toLong() * p.height / p.width).toInt().coerceIn(1, 2800)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
            image.setImageBitmap(bitmap)
            scroll.scrollTo(0, 0)
            number.text = "${page + 1} / ${renderer.pageCount}"
        }
        body.addView(context.vertical {
            row(height = context.px(48)) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                iconKey(R.drawable.ic_ui_close) { list() }
                iconKey(R.drawable.ic_ui_back) { if (page > 0) { page--; show() } }
                number = TextView(context).apply { setTextColor(-1); gravity = Gravity.CENTER }
                addView(number, LinearLayout.LayoutParams(0, -1, 1.5f))
                iconKey(R.drawable.ic_ui_forward) { if (page < renderer.pageCount - 1) { page++; show() } }
            }
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        closePdf = { runCatching { renderer.close() }; runCatching { descriptor?.close() } }
        show()
    }

    fun open(item: Item) {
        val ending = item.name.substringAfterLast('.', "").lowercase()
        when {
            item.mime.startsWith("video/") || item.mime.startsWith("audio/") -> shell.play(item.uri)
            item.mime.startsWith("image/") -> {
                leave()
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener { list() } }
                body.addView(image)
                pictures.load(item.uri, 1400, image)
            }
            item.mime == "application/pdf" || ending == "pdf" -> showPdf(item)
            item.mime.startsWith("text/") || item.mime == "application/json" || ending in TEXT_ENDINGS -> showText(item)
            else -> message(context.getString(R.string.file_unsupported))
        }
    }

    /** Export and delete for one imported file. Deleting asks again. */
    fun options(item: Item) {
        val file = item.file ?: return
        leave()
        body.addView(context.vertical {
            gravity = Gravity.CENTER_VERTICAL
            text(item.name, 15f).gravity = Gravity.CENTER
            val result = text("", 13f).apply { gravity = Gravity.CENTER }
            row(height = context.px(52)) {
                key(context.getString(R.string.export_copy), 1f, 0x5AB0FF) {
                    result.text = context.getString(R.string.working)
                    executor.execute {
                        val done = Imports.export(context.applicationContext, file)
                        main.post { result.text = context.getString(if (done) R.string.exported else R.string.export_failed) }
                    }
                }
            }
            row(height = context.px(52)) {
                var sure = false
                lateinit var remove: android.widget.Button
                remove = key(context.getString(R.string.delete_file), 1f, 0xFF7A7A) {
                    if (!sure) { sure = true; remove.text = context.getString(R.string.delete_file_confirm) }
                    else { file.delete(); list() }
                }
            }
            row(height = context.px(52)) { key(context.getString(R.string.dismiss)) { list() } }
        })
    }

    fun rows(items: List<Item>) {
        leave()
        body.addView(context.scrolling {
            items.forEach { item ->
                val detail = if (item.folderId != null) "" else "  ·  " + Formatter.formatShortFileSize(context, item.size)
                val icon = when {
                    item.folderId != null -> R.drawable.ic_ui_files
                    item.mime.startsWith("image/") -> R.drawable.ic_ui_photos
                    item.mime.startsWith("video/") || item.mime.startsWith("audio/") -> R.drawable.ic_ui_media
                    else -> R.drawable.ic_ui_notes
                }
                row(height = context.px(50)) {
                    val button = key(item.name + detail, 5f) {
                        if (item.folderId != null) { path += item.folderId to item.name; list() } else open(item)
                    }
                    button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    button.setPadding(context.px(14), 0, context.px(10), 0)
                    button.compoundDrawablePadding = context.px(10)
                    button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                    if (item.file != null) iconKey(R.drawable.ic_ui_settings) { options(item) }
                }
            }
        })
    }

    fun drawTabs() {
        tabs.removeAllViews()
        tabs.iconKey(R.drawable.ic_ui_files, context.getString(R.string.my_files), 2f, mine) { mine = true; drawTabs(); list() }
        tabs.iconKey(R.drawable.ic_ui_tabs, context.getString(R.string.phone_folder), 2f, !mine) { mine = false; drawTabs(); list() }
        if (mine) tabs.iconKey(R.drawable.ic_ui_download, context.getString(R.string.import_files), 2f) { shell.importFiles() }
        else {
            tabs.iconKey(R.drawable.ic_ui_up) { if (path.size > 1) path.removeAt(path.lastIndex); list() }
            tabs.iconKey(R.drawable.ic_ui_nearer, context.getString(R.string.choose_folder), 2f) { shell.pickFolder() }
        }
    }

    list = list@{
        if (mine) {
            location.text = context.getString(R.string.my_files_note)
            val items = Imports.list(context).map { Item(it.name, Imports.mime(it.name), it.length(), Uri.fromFile(it), it, null) }
            if (items.isEmpty()) message(context.getString(R.string.my_files_empty)) else rows(items)
            return@list
        }
        val root = tree ?: run { location.text = ""; return@list message(context.getString(R.string.files_empty)) }
        location.text = path.joinToString(" / ") { it.second }
        val items = runCatching { folderChildren(root, path.last().first) }.getOrNull()
            ?: return@list message(context.getString(R.string.file_unreadable))
        rows(items.sortedWith(compareBy({ it.folderId == null }, { it.name.lowercase() })))
    }

    val view = context.vertical {
        row(height = context.px(56)) { tabs = this }
        location = text(size = 12f)
        body = FrameLayout(context)
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    drawTabs()
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
    return SpatialApp(view) { stop(); flush(); closePdf(); pictures.close(); executor.shutdown() }.also { app -> app.flush = { flush() } }
}
