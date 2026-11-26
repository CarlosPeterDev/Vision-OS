package com.nexus.visionos.xr

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.nexus.visionos.R
import com.nexus.xr.AppType

/** Counts of real interactions, which the tutorial watches. */
class Stats {
    var presses = 0
    var moves = 0
    var resizes = 0
    var palms = 0
}

/** What built-in apps may ask of the spatial shell. */
interface Shell {
    val activity: Activity
    val settings: XrSettings
    val stats: Stats

    fun open(app: AppType)
    fun openUrl(url: String)
    fun pendingUrl(): String?
    fun typeText(text: String)
    fun sendKey(code: Int)
    fun editAction(action: Int)
    fun textBeforeCursor(): String
    fun replaceBeforeCursor(count: Int, text: String)
    fun pickVideo()
    fun videoUri(): Uri?
    /** Shows a video or audio file in the player window. */
    fun play(uri: Uri)
    fun pickPhotos()
    fun pickedPhotos(): List<Uri>
    fun pickFolder()
    fun pickedFolder(): Uri?
    /** Changes whenever picked or captured content changes. */
    fun libraryVersion(): Int
    /** False for password fields and web pages, whose text is never read or learned. */
    fun canSuggest(): Boolean
    /** Types what the user says into the focused field; calling again stops it. */
    fun dictate()
    fun capturePhoto()
    fun toggleRecording()
    fun isRecording(): Boolean
    fun statusLine(): String

    fun recenter()
    fun arrange()
    fun toggleHeadset()
    fun toggleCinema()
    fun isHeadset(): Boolean
    fun startCalibration()
    fun toggleLensGrid()
    fun volume(direction: Int)
    fun brightness(delta: Float)
    fun settingsChanged()

    fun assistant(context: Context): SpatialApp
    fun exit()
}

class AppInfo(val type: AppType, val title: Int, val icon: Int, val colours: IntArray)

private fun colours(top: Long, bottom: Long) = intArrayOf(top.toInt(), bottom.toInt())

val APP_INFO: Map<AppType, AppInfo> = listOf(
    AppInfo(AppType.HOME, R.string.app_home, R.drawable.ic_ui_launcher, colours(0xFF8FA8D8, 0xFF50659A)),
    AppInfo(AppType.BROWSER, R.string.app_browser, R.drawable.ic_ui_browser, colours(0xFF5AC8FA, 0xFF1565C0)),
    AppInfo(AppType.NOTES, R.string.app_notes, R.drawable.ic_ui_notes, colours(0xFFFFD66B, 0xFFE08A00)),
    AppInfo(AppType.CALCULATOR, R.string.app_calculator, R.drawable.ic_ui_calculator, colours(0xFFFF9F5A, 0xFFC4471B)),
    AppInfo(AppType.CLOCK, R.string.app_clock, R.drawable.ic_ui_clock, colours(0xFF6D7488, 0xFF22262F)),
    AppInfo(AppType.MEDIA, R.string.app_media, R.drawable.ic_ui_media, colours(0xFFC58BFF, 0xFF5B2BC2)),
    AppInfo(AppType.SETTINGS, R.string.app_controls, R.drawable.ic_ui_settings, colours(0xFFA9B4C2, 0xFF515C6C)),
    AppInfo(AppType.ASSISTANT, R.string.app_assistant, R.drawable.ic_ui_assistant, colours(0xFF8EE8FF, 0xFF7A4DF0)),
    AppInfo(AppType.KEYBOARD, R.string.app_keyboard, R.drawable.ic_ui_keyboard, colours(0xFF7F8EA3, 0xFF3A4556)),
    AppInfo(AppType.CENTER, R.string.app_center, R.drawable.ic_ui_center, colours(0xFF5CE1C6, 0xFF0F7F72)),
    AppInfo(AppType.TUTORIAL, R.string.app_tutorial, R.drawable.ic_ui_tutorial, colours(0xFFFF8FB1, 0xFFC2316A)),
    AppInfo(AppType.PHOTOS, R.string.app_photos, R.drawable.ic_ui_photos, colours(0xFFFFB45A, 0xFFE0457B)),
    AppInfo(AppType.FILES, R.string.app_files, R.drawable.ic_ui_files, colours(0xFF6FB6FF, 0xFF2C55C9)),
    AppInfo(AppType.CAMERA, R.string.app_camera, R.drawable.ic_ui_camera, colours(0xFF9AA3AE, 0xFF2F353E)),
    AppInfo(AppType.LAUNCHER, R.string.app_launcher, R.drawable.ic_ui_launcher, colours(0xFF7EE08A, 0xFF1F8A4C))
).associateBy { it.type }

fun appTitle(context: Context, app: AppType): String = context.getString(APP_INFO.getValue(app).title)
