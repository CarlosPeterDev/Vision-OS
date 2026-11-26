package com.nexus.visionos.xr

import android.content.Context
import com.nexus.xr.ClickStyle
import com.nexus.xr.GestureConfig
import com.nexus.xr.HandChoice
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
    private inner class Decimal(private val default: Float, private val low: Float, private val high: Float) {
        operator fun getValue(owner: Any?, p: KProperty<*>) = prefs.getFloat(p.name, default).coerceIn(low, high)
        operator fun setValue(owner: Any?, p: KProperty<*>, v: Float) = prefs.edit().putFloat(p.name, v.coerceIn(low, high)).apply()
    }

    var passthrough by Bool(true)
    /** 0 flat tint, 1 frosted, 2 frosted with colour fringes. */
    var quality by Number(1, 0..2)
    /** Virtual scene: dusk, night sky, mountains, ocean. */
    var scene by Number(0, 0..3)
    var dim by Decimal(0f, 0f, 0.85f)
    var showHands by Bool(true)
    var occlusion by Bool(false)
    var cameraTurns by Number(0, 0..3)

    var ipd by Decimal(0.063f, 0.052f, 0.074f)
    var distortion by Decimal(0.22f, 0f, 0.6f)
    var lensShift by Decimal(0f, -0.2f, 0.2f)
    var lensHeight by Decimal(0f, -0.2f, 0.2f)
    var lensZoom by Decimal(1f, 0.7f, 1.3f)

    var lookAndPinch by Bool(false)
    var poke by Bool(false)
    var handChoice by Number(0, 0..2)
    var smoothing by Number(1, 0..2)
    var pressRatio by Decimal(0.27f, 0.1f, 0.5f)
    var releaseRatio by Decimal(0.42f, 0.2f, 0.75f)
    var swapHandSides by Bool(false)

    var sounds by Bool(true)
    var haptics by Bool(true)
    var restoreLayout by Bool(true)
    var tutorialSeen by Bool(false)
    var folder: String
        get() = prefs.getString("folder", "") ?: ""
        set(v) = prefs.edit().putString("folder", v).apply()
    var layout: String
        get() = prefs.getString("layout", "") ?: ""
        set(v) = prefs.edit().putString("layout", v).apply()

    fun gestures() = GestureConfig(
        if (poke) ClickStyle.POKE else ClickStyle.PINCH, pressRatio, releaseRatio, smoothing, HandChoice.entries[handChoice]
    )
}
