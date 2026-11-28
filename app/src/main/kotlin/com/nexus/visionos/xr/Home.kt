package com.nexus.visionos.xr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.xr.AppType
import com.nexus.xr.Lesson
import com.nexus.xr.TutorialProgress

private class Entry(val label: String, val icon: Int, val colours: IntArray, val action: () -> Unit)

/** A staggered grid of round app icons, in pages. */
internal fun home(context: Context, shell: Shell): SpatialApp {
    fun app(type: AppType) = APP_INFO.getValue(type).let { Entry(context.getString(it.title), it.icon, it.colours) { shell.open(type) } }
    val exit = Entry(context.getString(R.string.exit_spatial), R.drawable.ic_ui_exit, intArrayOf(0xFF8A93A6.toInt(), 0xFF3B414F.toInt())) {
        shell.exit()
    }
    // The calculator sits in the very middle of the first page.
    val pages = listOf(
        listOf(
            listOf(app(AppType.BROWSER), app(AppType.PHOTOS), app(AppType.FILES)),
            listOf(app(AppType.NOTES), app(AppType.CLOCK), app(AppType.CALCULATOR), app(AppType.MEDIA), app(AppType.ASSISTANT)),
            listOf(app(AppType.CAMERA), app(AppType.FIT), app(AppType.CENTER), app(AppType.SETTINGS))
        ),
        listOf(
            listOf(app(AppType.TASKS), app(AppType.CALENDAR), app(AppType.WEATHER), app(AppType.PAINT), app(AppType.NATIVE_APPS)),
            listOf(app(AppType.CONVERTER), app(AppType.GAMES), app(AppType.VOICE), app(AppType.KEYBOARD)),
            listOf(app(AppType.TUTORIAL), app(AppType.PRACTICE), app(AppType.SPACES), app(AppType.CHECKUP), exit)
        )
    )
    var page = 0
    lateinit var status: TextView
    lateinit var grid: LinearLayout
    lateinit var indicator: TextView
    fun show() {
        grid.removeAllViews()
        for (items in pages[page]) {
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER; clipChildren = false; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (entry in items) {
                line.addView(
                    context.appIcon(entry.icon, entry.label, entry.colours, context.px(60), entry.action),
                    LinearLayout.LayoutParams(context.px(94), -2).apply { setMargins(context.px(3), 0, context.px(3), 0) }
                )
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        indicator.text = (pages.indices).joinToString("  ") { if (it == page) "●" else "○" }
    }
    val view = context.vertical {
        status = text(size = 12f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        status.layoutParams = LinearLayout.LayoutParams(-1, context.px(40))
        grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        // As tall as the status line, so the middle row is centred in the window.
        row(height = context.px(40)) {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            val back = iconKey(R.drawable.ic_ui_back) { page = (page + pages.size - 1) % pages.size; show() }
            indicator = TextView(context).apply { setTextColor(-1); gravity = Gravity.CENTER; textSize = 12f }
            addView(indicator, LinearLayout.LayoutParams(context.px(90), -1))
            val next = iconKey(R.drawable.ic_ui_forward) { page = (page + 1) % pages.size; show() }
            for (button in listOf(back, next)) button.layoutParams = LinearLayout.LayoutParams(context.px(54), context.px(30))
            removeView(next)
            addView(next)
        }
    }
    show()
    Log.i("NexusXR", "Home built")
    val stop = ticker(2000) { status.text = if (shell.settings.showStatus) shell.statusLine() else "" }
    return SpatialApp(view, stop)
}

private fun networkName(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    return context.getString(
        when {
            capabilities == null -> R.string.net_offline
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.net_wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.net_mobile
            else -> R.string.net_other
        }
    )
}

/** Quick settings. */
internal fun controlCenter(context: Context, shell: Shell): SpatialApp {
    val settings = shell.settings
    lateinit var header: TextView
    lateinit var tiles: LinearLayout
    fun refresh() {
        val battery = context.applicationContext.getSystemService(BatteryManager::class.java)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
        header.text = "$time    ${context.getString(R.string.battery, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))}" +
            (if (battery.isCharging) " +" else "") + "    ${networkName(context)}"
    }
    fun build() {
        tiles.removeAllViews()
        fun line(add: LinearLayout.() -> Unit) = tiles.row(1f) { add() }
        fun LinearLayout.tile(icon: Int, label: Int, active: Boolean = false, action: () -> Unit) =
            iconKey(icon, context.getString(label), active = active) { action(); build() }
        line {
            tile(R.drawable.ic_ui_volume_down, R.string.volume_less) { shell.volume(-1) }
            tile(R.drawable.ic_ui_volume_up, R.string.volume_more) { shell.volume(1) }
            tile(R.drawable.ic_ui_dim, R.string.bright_less) { shell.brightness(-0.15f) }
            tile(R.drawable.ic_ui_bright, R.string.bright_more) { shell.brightness(0.15f) }
        }
        line {
            tile(R.drawable.ic_ui_camera, if (settings.passthrough) R.string.env_camera else R.string.env_virtual, settings.passthrough) {
                settings.passthrough = !settings.passthrough; shell.settingsChanged()
            }
            tile(R.drawable.ic_ui_photos, R.string.next_scene) {
                settings.scene = Places.next(context, settings.scene); settings.passthrough = false; shell.settingsChanged()
            }
            tile(R.drawable.ic_ui_farther, R.string.dim_less) { settings.dim -= 0.2f }
            tile(R.drawable.ic_ui_nearer, R.string.dim_more) { settings.dim += 0.2f }
        }
        line {
            tile(R.drawable.ic_ui_reload, R.string.ctl_recenter) { shell.recenter() }
            tile(R.drawable.ic_ui_launcher, R.string.ctl_arrange) { shell.arrange() }
            tile(R.drawable.ic_ui_media, R.string.cinema) { shell.toggleCinema() }
            tile(R.drawable.ic_ui_tabs, R.string.ctl_headset, shell.isHeadset()) { shell.toggleHeadset() }
            tile(R.drawable.ic_ui_mic, R.string.app_voice, shell.isListening()) { shell.toggleVoiceControl() }
        }
    }
    val view = context.vertical {
        header = text(size = 15f).apply { gravity = Gravity.CENTER }
        tiles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(tiles, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    build()
    val stop = ticker(2000) { refresh() }
    return SpatialApp(view, stop)
}

/** All spatial settings, grouped, in one scrolling list. */
internal fun settingsApp(context: Context, shell: Shell): SpatialApp {
    val settings = shell.settings
    val refreshers = mutableListOf<() -> Unit>()
    lateinit var status: TextView
    fun refresh() = refreshers.forEach { it() }
    fun LinearLayout.heading(id: Int) = text(context.getString(id), 13f).also { it.alpha = 0.75f }
    /** A button whose label always shows the current value. */
    fun LinearLayout.option(label: () -> String, action: () -> Unit) {
        val button = key(label()) { action(); shell.settingsChanged(); refresh() }
        refreshers += { button.text = label() }
    }
    fun onOff(value: Boolean) = context.getString(if (value) R.string.on else R.string.off)
    fun named(id: Int, value: String) = context.getString(id) + ": " + value
    fun choice(id: Int, index: Int) = context.resources.getStringArray(id)[index]
    val view = context.scrolling {
        status = text(size = 12f)
        heading(R.string.section_view)
        row(height = context.px(56)) {
            option({ context.getString(if (settings.passthrough) R.string.env_camera else R.string.env_virtual) }) { settings.passthrough = !settings.passthrough }
            option({ named(R.string.scene, choice(R.array.scenes, settings.scene)) }) { settings.scene = Places.next(context, settings.scene) }
            option({ named(R.string.ctl_glass, choice(R.array.glass_levels, settings.quality)) }) { settings.quality = (settings.quality + 1) % 3 }
        }
        row(height = context.px(52)) { key(context.getString(R.string.photo_place)) { shell.pickPhotoPlace() } }
        row(height = context.px(56)) {
            option({ named(R.string.walk_mode, onOff(settings.walkTracking)) }) { settings.walkTracking = !settings.walkTracking }
            option({ named(R.string.heat_adapt, onOff(settings.adaptiveHeat)) }) { settings.adaptiveHeat = !settings.adaptiveHeat }
        }
        row(height = context.px(56)) {
            option({ named(R.string.ctl_hands, onOff(settings.showHands)) }) { settings.showHands = !settings.showHands }
            option({ named(R.string.occlusion, onOff(settings.occlusion)) }) { settings.occlusion = !settings.occlusion }
            option({ context.getString(R.string.cinema) }) { shell.toggleCinema() }
        }
        heading(R.string.section_hands)
        row(height = context.px(56)) {
            option({ named(R.string.click_style, context.getString(if (settings.poke) R.string.click_poke else R.string.click_pinch)) }) { settings.poke = !settings.poke }
            option({ named(R.string.aim, context.getString(if (settings.lookAndPinch) R.string.aim_head else R.string.aim_hand)) }) { settings.lookAndPinch = !settings.lookAndPinch }
            option({ named(R.string.which_hand, choice(R.array.hand_choices, settings.handChoice)) }) { settings.handChoice = (settings.handChoice + 1) % 3 }
        }
        row(height = context.px(56)) {
            option({ named(R.string.smoothing, choice(R.array.smoothing_levels, settings.smoothing)) }) { settings.smoothing = (settings.smoothing + 1) % 3 }
            option({ context.getString(R.string.calibrate) }) { shell.startCalibration() }
            option({ named(R.string.swap_sides, onOff(settings.swapHandSides)) }) { settings.swapHandSides = !settings.swapHandSides }
        }
        row(height = context.px(52)) {
            option({ context.getString(R.string.app_practice) }) { shell.open(AppType.PRACTICE) }
            option({ named(R.string.air_input, onOff(settings.airInput)) }) { settings.airInput = !settings.airInput }
        }
        text(context.getString(R.string.air_input_help), 11f).alpha = 0.7f
        heading(R.string.section_headset)
        row(height = context.px(52)) { option({ context.getString(R.string.app_fit) }) { shell.open(AppType.FIT) } }
        row(height = context.px(46)) {
            option({ context.getString(R.string.ctl_headset) }) { shell.toggleHeadset() }
            option({ context.getString(R.string.lens_grid) }) { shell.toggleLensGrid() }
            option({ context.getString(R.string.ctl_rotate_camera) }) { settings.cameraTurns = (settings.cameraTurns + 1) % 4 }
        }
        fun LinearLayout.stepper(label: () -> String, less: () -> Unit, more: () -> Unit) = row(height = context.px(46)) {
            iconKey(R.drawable.ic_ui_farther) { less(); shell.settingsChanged(); refresh() }
            val title = text(label(), 13f)
            removeView(title)
            addView(title, LinearLayout.LayoutParams(0, -1, 3f))
            title.gravity = Gravity.CENTER
            refreshers += { title.text = label() }
            iconKey(R.drawable.ic_ui_nearer) { more(); shell.settingsChanged(); refresh() }
        }
        stepper({ "IPD %.0f mm".format(settings.ipd * 1000) }, { settings.ipd -= 0.002f }, { settings.ipd += 0.002f })
        stepper({ named(R.string.lens_curve, "%.2f".format(settings.distortion)) }, { settings.distortion -= 0.03f }, { settings.distortion += 0.03f })
        stepper({ named(R.string.lens_zoom, "%.2f".format(settings.lensZoom)) }, { settings.lensZoom -= 0.03f }, { settings.lensZoom += 0.03f })
        heading(R.string.app_voice)
        row(height = context.px(56)) {
            option({ context.getString(if (shell.isListening()) R.string.voice_stop else R.string.voice_start) }) { shell.toggleVoiceControl() }
            option({ named(R.string.voice_auto, onOff(settings.voiceAutoStart)) }) { settings.voiceAutoStart = !settings.voiceAutoStart }
            option({ named(R.string.voice_language, choice(R.array.voice_languages, settings.voiceLanguage)) }) { settings.voiceLanguage = (settings.voiceLanguage + 1) % 3 }
        }
        row(height = context.px(56)) {
            option({ named(R.string.voice_offline_only, onOff(settings.voiceOfflineOnly)) }) { settings.voiceOfflineOnly = !settings.voiceOfflineOnly }
            option({ named(R.string.voice_wake, onOff(settings.wakeWord)) }) { settings.wakeWord = !settings.wakeWord }
            option({ named(R.string.voice_ai, onOff(settings.voiceAi)) }) { settings.voiceAi = !settings.voiceAi }
        }
        text(context.getString(R.string.voice_privacy), 11f).alpha = 0.7f
        row(height = context.px(46)) { option({ context.getString(R.string.voice_command_list) }) { shell.open(AppType.VOICE) } }
        heading(R.string.section_windows)
        row(height = context.px(56)) {
            option({ named(R.string.free_resize, onOff(settings.freeResize)) }) { settings.freeResize = !settings.freeResize }
            option({ named(R.string.palm_shortcuts, onOff(settings.palmShortcuts)) }) { settings.palmShortcuts = !settings.palmShortcuts }
            option({ named(R.string.status_line, onOff(settings.showStatus)) }) { settings.showStatus = !settings.showStatus }
        }
        stepper({ named(R.string.window_distance, "%.2f m".format(settings.windowDistance)) }, { settings.windowDistance -= 0.15f }, { settings.windowDistance += 0.15f })
        stepper({ named(R.string.text_size, "%.0f%%".format(settings.textScale * 100)) }, { settings.textScale -= 0.05f }, { settings.textScale += 0.05f })
        stepper({ named(R.string.cursor_size, "%.1f".format(settings.cursorSize)) }, { settings.cursorSize -= 0.2f }, { settings.cursorSize += 0.2f })
        stepper({ named(R.string.long_press_time, "%.2f s".format(settings.longPressMs / 1000f)) }, { settings.longPressMs -= 250 }, { settings.longPressMs += 250 })
        text(context.getString(R.string.size_buttons_note), 11f).alpha = 0.7f
        row(height = context.px(56)) {
            option({ named(R.string.gpu_tracking, onOff(settings.gpuTracking)) }) {
                settings.gpuTracking = !settings.gpuTracking
                status.text = context.getString(R.string.restart_needed)
            }
        }
        heading(R.string.app_browser)
        row(height = context.px(56)) {
            option({ named(R.string.search_engine, searchEngineName(settings.searchEngine)) }) { settings.searchEngine = (settings.searchEngine + 1) % 4 }
            option({ named(R.string.restore_tabs, onOff(settings.restoreTabs)) }) { settings.restoreTabs = !settings.restoreTabs }
            option({ named(R.string.save_history, onOff(settings.saveHistory)) }) { settings.saveHistory = !settings.saveHistory }
        }
        row(height = context.px(56)) {
            option({ named(R.string.desktop_sites, onOff(settings.desktopSites)) }) { settings.desktopSites = !settings.desktopSites }
            option({ named(R.string.third_party_cookies, onOff(settings.thirdPartyCookies)) }) { settings.thirdPartyCookies = !settings.thirdPartyCookies }
        }
        stepper({ named(R.string.web_text_size, "${settings.webTextZoom}%") }, { settings.webTextZoom -= 10 }, { settings.webTextZoom += 10 })
        text(context.getString(R.string.browser_data_note), 11f).alpha = 0.7f
        heading(R.string.section_data)
        /** Destructive choices ask a second time on the same button. */
        fun LinearLayout.careful(label: Int, action: () -> Unit) {
            var armedAt = 0L
            lateinit var button: android.widget.Button
            button = key(context.getString(label), tint = 0xFF9A9A) {
                val now = android.os.SystemClock.uptimeMillis()
                if (armedAt == 0L || now - armedAt > 6000) {
                    armedAt = now
                    button.text = context.getString(R.string.press_again)
                } else {
                    armedAt = 0
                    button.text = context.getString(label)
                    action()
                    status.text = context.getString(R.string.cleared)
                }
            }
        }
        row(height = context.px(56)) {
            careful(R.string.clear_history) { BrowserData.clearHistory(context) }
            careful(R.string.sign_out_all) { BrowserData.clearAll(context) }
            careful(R.string.reset_settings) { settings.reset(); shell.settingsChanged(); refresh() }
        }
        heading(R.string.section_feedback)
        row(height = context.px(52)) {
            option({ named(R.string.spatial_sound, onOff(settings.spatialSound)) }) { settings.spatialSound = !settings.spatialSound }
        }
        row(height = context.px(56)) {
            option({ named(R.string.sounds, onOff(settings.sounds)) }) { settings.sounds = !settings.sounds }
            option({ named(R.string.vibration, onOff(settings.haptics)) }) { settings.haptics = !settings.haptics }
            option({ named(R.string.restore_layout, onOff(settings.restoreLayout)) }) { settings.restoreLayout = !settings.restoreLayout }
        }
        row(height = context.px(46)) {
            option({ context.getString(R.string.ctl_recenter) }) { shell.recenter() }
            option({ context.getString(R.string.ctl_arrange) }) { shell.arrange() }
            option({ context.getString(R.string.app_tutorial) }) { shell.open(AppType.TUTORIAL) }
        }
        row(height = context.px(46)) {
            option({ context.getString(R.string.clear_words) }) {
                context.applicationContext.getSharedPreferences("words", Context.MODE_PRIVATE).edit().clear().apply()
                status.text = context.getString(R.string.words_cleared)
            }
            option({ context.getString(R.string.exit_spatial) }) { shell.exit() }
        }
    }
    val stop = ticker(1000) { status.text = shell.statusLine() }
    return SpatialApp(view, stop)
}

/** Walks through the gestures; a lesson is practised only by doing its own action. */
internal fun tutorial(context: Context, shell: Shell): SpatialApp {
    val progress = TutorialProgress(shell.settings.tutorialMastered)
    var targetTaps = 0
    lateinit var title: TextView
    lateinit var target: android.widget.Button
    lateinit var practice: ScrollView
    lateinit var button: android.widget.Button
    val stats = shell.stats
    val steps = context.resources.getStringArray(R.array.tutorial_steps)
    fun counter() = when (progress.current) {
        Lesson.TAP -> targetTaps
        // Counts as one action once the list has really been scrolled.
        Lesson.SCROLL -> if (practice.scrollY > context.px(60)) 1 else 0
        Lesson.MOVE -> stats.moves
        Lesson.RESIZE -> stats.resizes
        Lesson.PALM -> stats.palms
        null -> 0
    }
    fun show() {
        val lesson = progress.current
        title.text = if (lesson != null) "${progress.number}/${steps.size}  ${steps[lesson.ordinal]}"
        else context.getString(R.string.tutorial_summary, progress.practised.size, steps.size)
        target.visibility = if (lesson == Lesson.TAP) View.VISIBLE else View.GONE
        practice.visibility = if (lesson == Lesson.SCROLL) View.VISIBLE else View.INVISIBLE
        practice.scrollTo(0, 0)
        progress.begin(counter())
        button.setText(if (lesson != null) R.string.skip_step else R.string.start_over)
    }
    val view = context.vertical {
        title = text(size = 17f)
        row(height = context.px(56)) { target = key(context.getString(R.string.tutorial_target), tint = 0x5AB0FF) { targetTaps++ } }
        practice = context.scrolling { for (i in 1..30) text("•  $i", 18f) }
        addView(FrameLayout(context).apply { addView(practice) }, LinearLayout.LayoutParams(-1, 0, 1f))
        row(height = context.px(56)) {
            button = key("") {
                // Having used the tutorial stops it opening by itself; it is not the same as practising.
                shell.settings.tutorialSeen = true
                if (progress.current != null) progress.skip() else progress.restart()
                show()
            }
        }
    }
    show()
    val stop = ticker(250) {
        if (progress.observe(counter())) {
            shell.settings.tutorialMastered = progress.encode()
            if (progress.current == null) shell.settings.tutorialSeen = true
            show()
        }
    }
    return SpatialApp(view, stop)
}
