package com.nexus.visionos.xr

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.nexus.visionos.R
import com.nexus.xr.AppType
import com.nexus.xr.Routine
import com.nexus.xr.Space
import com.nexus.xr.Subtitles
import com.nexus.xr.TrackingSample

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
    fun videoCount(): Int
    fun videoIndex(): Int
    /** Moves along the playlist; does nothing at either end. */
    fun stepVideo(delta: Int)
    fun pickSubtitles()
    fun subtitles(): Subtitles?
    fun subtitleVersion(): Int
    /** Lets the user choose their own 360° picture as a place. */
    fun pickPhotoPlace()
    fun addEvent(atMillis: Long, title: String)
    /** Opens the phone's calendar app with the event filled in, for the user to save there. */
    fun addToPhoneCalendar(atMillis: Long, title: String)
    fun openSpace(space: Space)
    fun saveSpace(space: Space)
    fun runRoutine(routine: Routine)
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
    /** Lets the user place the two headset pictures by dragging them on the screen. */
    fun startDragFit()
    fun volume(direction: Int)
    fun brightness(delta: Float)
    fun settingsChanged()

    fun assistant(context: Context): SpatialApp

    fun toggleVoiceControl()
    fun voiceControlState(): String
    fun isListening(): Boolean
    /** Starts a countdown that rings even when the Clock window is closed; zero cancels it. */
    fun setTimer(minutes: Int)
    /** When the countdown ends, in elapsed-realtime milliseconds; zero when there is none. */
    fun timerEndsAt(): Long
    fun importFiles()
    fun isGpuTracking(): Boolean
    /** What the hand tracker sees right now, for the practice window. */
    fun trackingSample(): TrackingSample
    fun measurements(): Measurements
    /** Opens the phone's share sheet; nothing is sent unless the user picks where. */
    fun shareText(text: String)
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
    AppInfo(AppType.SPACES, R.string.app_spaces, R.drawable.ic_ui_spaces, colours(0xFFB08CFF, 0xFF5A3FC0)),
    AppInfo(AppType.FIT, R.string.app_fit, R.drawable.ic_ui_fit, colours(0xFF7EE08A, 0xFF1F8A4C)),
    AppInfo(AppType.PRACTICE, R.string.app_practice, R.drawable.ic_ui_practice, colours(0xFFFFA86B, 0xFFC2452F)),
    AppInfo(AppType.CHECKUP, R.string.app_checkup, R.drawable.ic_ui_checkup, colours(0xFF63E6B5, 0xFF14705F)),
    AppInfo(AppType.TASKS, R.string.app_tasks, R.drawable.ic_ui_tasks, colours(0xFF7FB2FF, 0xFF3B4FD0)),
    AppInfo(AppType.CALENDAR, R.string.app_calendar, R.drawable.ic_ui_calendar, colours(0xFFFF8A80, 0xFFC62828)),
    AppInfo(AppType.PAINT, R.string.app_paint, R.drawable.ic_ui_paint, colours(0xFFFF9ECF, 0xFF8E3CC9)),
    AppInfo(AppType.CONVERTER, R.string.app_converter, R.drawable.ic_ui_converter, colours(0xFF6EE7C8, 0xFF12806A)),
    AppInfo(AppType.GAMES, R.string.app_games, R.drawable.ic_ui_games, colours(0xFFFFC857, 0xFFD9661F)),
    AppInfo(AppType.WEATHER, R.string.app_weather, R.drawable.ic_ui_weather, colours(0xFF82D8FF, 0xFF2368C4)),
    AppInfo(AppType.VOICE, R.string.app_voice, R.drawable.ic_ui_mic, colours(0xFFB69CFF, 0xFF5A35B5))
).associateBy { it.type }

fun appTitle(context: Context, app: AppType): String = context.getString(APP_INFO.getValue(app).title)
