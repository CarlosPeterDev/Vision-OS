package com.nexus.visionos.xr

import android.content.Context
import com.nexus.xr.ClickStyle
import com.nexus.xr.GestureConfig
import com.nexus.xr.HandChoice
import com.nexus.xr.HandPose
import com.nexus.xr.HeadsetFit
import com.nexus.xr.PoseAction
import kotlin.reflect.KProperty

/** Everything adjustable in spatial mode, kept between launches. */
class XrSettings(context: Context) {
    private val prefs = context.getSharedPreferences("xr", Context.MODE_PRIVATE)

    private inner class Bool(private val default: Boolean) {
        operator fun getValue(owner: Any?, p: KProperty<*>) = prefs.getBoolean(p.name, default)
        operator fun setValue(owner: Any?, p: KProperty<*>, v: Boolean) = prefs.edit().putBoolean(p.name, v).apply()
    }
    private inner class Number(private val default: Int, private val range: IntRange) {
        operator fun getValue(owner: Any?, p: KProperty<*>) = prefs.getInt(p.name, default).coerceIn(range)
        operator fun setValue(owner: Any?, p: KProperty<*>, v: Int) = prefs.edit().putInt(p.name, v.coerceIn(range)).apply()
    }
    private inner class Text(private val default: String) {
        operator fun getValue(owner: Any?, p: KProperty<*>) = prefs.getString(p.name, default) ?: default
        operator fun setValue(owner: Any?, p: KProperty<*>, v: String) = prefs.edit().putString(p.name, v).apply()
    }
    /** Returns every setting to its starting value. Files, notes and sign-ins are not touched. */
    fun reset() {
        // The user's own shortcuts and saved workspaces are their content, not settings.
        val keep = listOf("routines", "workWindows", "studyWindows", "cinemaWindows").associateWith { prefs.getString(it, null) }
        val edit = prefs.edit().clear()
        keep.forEach { (name, value) -> if (value != null) edit.putString(name, value) }
        edit.apply()
    }
    private inner class Decimal(private val default: Float, private val low: Float, private val high: Float) {
        operator fun getValue(owner: Any?, p: KProperty<*>) = prefs.getFloat(p.name, default).coerceIn(low, high)
        operator fun setValue(owner: Any?, p: KProperty<*>, v: Float) = prefs.edit().putFloat(p.name, v.coerceIn(low, high)).apply()
    }

    var passthrough by Bool(true)
    /** 0 flat tint, 1 frosted, 2 frosted with colour fringes. */
    var quality by Number(1, 0..2)
    /** Virtual place; see [Places]. */
    var scene by Number(0, 0..7)
    var spatialSound by Bool(true)
    /** The user's voice shortcuts, one per line; empty until they save their own. */
    var routines by Text("")
    var workWindows by Text("")
    var studyWindows by Text("")
    var cinemaWindows by Text("")
    var dim by Decimal(0f, 0f, 0.85f)
    var showHands by Bool(true)
    /** A Bluetooth or USB mouse, keyboard or game controller works inside the view. */
    var airInput by Bool(true)
    fun poseAction(pose: HandPose): PoseAction =
        PoseAction.entries.firstOrNull { it.name == prefs.getString("pose_" + pose.name, null) } ?: PoseAction.NOTHING
    fun setPoseAction(pose: HandPose, action: PoseAction) = prefs.edit().putString("pose_" + pose.name, action.name).apply()
    var occlusion by Bool(false)
    var cameraTurns by Number(0, 0..3)

    var ipd by Decimal(0.063f, 0.052f, 0.074f)
    var distortion by Decimal(0.22f, 0f, 0.6f)
    private var fitGap by Decimal(0f, -HeadsetFit.MAX_GAP, HeadsetFit.MAX_GAP)
    private var fitWidth by Decimal(1f, HeadsetFit.MIN_SIZE, HeadsetFit.MAX_SIZE)
    private var fitHeight by Decimal(1f, HeadsetFit.MIN_SIZE, HeadsetFit.MAX_SIZE)
    private var fitLeftX by Decimal(0f, -HeadsetFit.MAX_MOVE, HeadsetFit.MAX_MOVE)
    private var fitLeftY by Decimal(0f, -HeadsetFit.MAX_MOVE, HeadsetFit.MAX_MOVE)
    private var fitRightX by Decimal(0f, -HeadsetFit.MAX_MOVE, HeadsetFit.MAX_MOVE)
    private var fitRightY by Decimal(0f, -HeadsetFit.MAX_MOVE, HeadsetFit.MAX_MOVE)
    /** Where the two headset pictures sit on the screen. */
    var fit: HeadsetFit
        get() = HeadsetFit(fitGap, fitWidth, fitHeight, fitLeftX, fitLeftY, fitRightX, fitRightY)
        set(value) = value.clamped().let {
            fitGap = it.gap; fitWidth = it.width; fitHeight = it.height
            fitLeftX = it.leftX; fitLeftY = it.leftY; fitRightX = it.rightX; fitRightY = it.rightY
        }
    var lensZoom by Decimal(1f, 0.7f, 1.3f)

    var lookAndPinch by Bool(false)
    var poke by Bool(false)
    var handChoice by Number(0, 0..2)
    var smoothing by Number(1, 0..2)
    var pressRatio by Decimal(0.27f, 0.1f, 0.5f)
    var releaseRatio by Decimal(0.42f, 0.2f, 0.75f)
    var swapHandSides by Bool(false)

    var longPressMs by Number(2000, 1500..4000)
    var freeResize by Bool(true)
    /** Text size inside windows. Takes effect for windows opened afterwards. */
    var textScale by Decimal(1f, 0.85f, 1.35f)
    var windowDistance by Decimal(1.75f, 1.1f, 2.8f)
    var palmShortcuts by Bool(true)
    var cursorSize by Decimal(1f, 0.6f, 1.8f)
    var showStatus by Bool(true)
    var gpuTracking by Bool(false)

    /** Hands-free voice control starts together with spatial mode. */
    var voiceAutoStart by Bool(false)
    /** Refuse any recognizer that may send audio off the phone. */
    var voiceOfflineOnly by Bool(true)
    /** 0 the phone's language, 1 English, 2 Arabic. */
    var voiceLanguage by Number(0, 0..2)
    /** Only react to sentences that start with "Nexus". */
    var wakeWord by Bool(false)
    /** Sentences that are not commands go to the configured AI model as text. */
    var voiceAi by Bool(true)

    var searchEngine by Number(0, 0..3)
    var homePage by Text("")
    var thirdPartyCookies by Bool(false)
    var desktopSites by Bool(false)
    var saveHistory by Bool(true)
    var restoreTabs by Bool(true)
    var webTextZoom by Number(100, 60..200)
    var weatherCity by Text("")

    var sounds by Bool(true)
    var haptics by Bool(true)
    var restoreLayout by Bool(true)
    var tutorialSeen by Bool(false)
    var folder: String
        get() = prefs.getString("folder", "") ?: ""
        set(v) = prefs.edit().putString("folder", v).apply()
    var savedLayout: String
        get() = prefs.getString("savedLayout", "") ?: ""
        set(v) = prefs.edit().putString("savedLayout", v).apply()
    var layout: String
        get() = prefs.getString("layout", "") ?: ""
        set(v) = prefs.edit().putString("layout", v).apply()

    fun gestures() = GestureConfig(
        if (poke) ClickStyle.POKE else ClickStyle.PINCH, pressRatio, releaseRatio, smoothing, HandChoice.entries[handChoice]
    )
}
