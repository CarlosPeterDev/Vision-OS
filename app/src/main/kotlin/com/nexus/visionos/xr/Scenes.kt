package com.nexus.visionos.xr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Virtual places. The last one is the user's own 360° photo, once they have chosen it. */
object Places {
    const val BUILT_IN = 7
    const val PHOTO = BUILT_IN
    private const val WIDTH = 2048

    private fun file(context: Context) = File(context.filesDir, "panorama.jpg")
    fun hasPhoto(context: Context) = file(context).exists()
    fun count(context: Context) = BUILT_IN + if (hasPhoto(context)) 1 else 0
    fun next(context: Context, current: Int) = (current + 1) % count(context)

    fun load(context: Context): Bitmap? = if (hasPhoto(context)) BitmapFactory.decodeFile(file(context).path) else null

    /** Keeps a private copy sized for the graphics chip. Returns false if it is not a picture. */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        fun open() = requireNotNull(context.contentResolver.openInputStream(uri))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return false
        // A 360° picture covers twice as much sideways as up and down.
        val scaled = Bitmap.createScaledBitmap(decoded, WIDTH, WIDTH / 2, true)
        file(context).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }.getOrDefault(false)
}
