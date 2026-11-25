package com.nexus.visionos.xr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.nexus.visionos.R
import com.nexus.xr.AppType
import com.nexus.xr.Calculator
import java.io.File

private fun Context.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

private fun pill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

private fun Context.vertical(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(px(10), px(10), px(10), px(10))
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    build()
}

private fun LinearLayout.row(weight: Float = 0f, build: LinearLayout.() -> Unit) = LinearLayout(context).also {
    it.orientation = LinearLayout.HORIZONTAL
    it.build()
    addView(it, LinearLayout.LayoutParams(-1, if (weight > 0) 0 else -2, weight))
}

private fun LinearLayout.key(text: String, weight: Float = 1f, click: () -> Unit) = Button(context).also {
    it.text = text
    it.isAllCaps = false
    it.textSize = 15f
    it.setTextColor(Color.WHITE)
    it.background = pill(0x38FFFFFF, context.px(14).toFloat())
    it.stateListAnimator = null
    it.isFocusable = false
    it.minWidth = 0
    it.minimumWidth = 0
    it.setPadding(0, 0, 0, 0)
    it.setOnClickListener { _ -> click() }
    val horizontal = orientation == LinearLayout.HORIZONTAL
    val params = if (horizontal) LinearLayout.LayoutParams(0, -1, weight) else LinearLayout.LayoutParams(-1, -2)
    params.setMargins(context.px(3), context.px(3), context.px(3), context.px(3))
    addView(it, params)
}

private fun LinearLayout.text(value: String = "", size: Float = 15f) = TextView(context).also {
    it.text = value
    it.textSize = size
    it.setTextColor(Color.WHITE)
    it.setPadding(context.px(4), context.px(4), context.px(4), context.px(4))
    addView(it, LinearLayout.LayoutParams(-1, -2))
}

private fun editor(context: Context) = EditText(context).apply {
    showSoftInputOnFocus = false // typing comes from the spatial keyboard
    setTextColor(Color.WHITE)
    setHintTextColor(0x99FFFFFF.toInt())
    background = pill(0x55000000, context.px(10).toFloat())
    setPadding(context.px(10), context.px(8), context.px(10), context.px(8))
}

/** Re-runs [update] every [periodMs] until the returned stop function is called. */
private fun ticker(periodMs: Long, update: () -> Unit): () -> Unit {
    val handler = Handler(Looper.getMainLooper())
    val task = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, periodMs) }
    }
    handler.post(task)
    return { handler.removeCallbacks(task) }
}

/** What built-in apps may ask of the spatial shell. */
interface Shell {
    fun open(app: AppType)
    fun openUrl(url: String)
    fun typeText(text: String)
    fun sendKey(code: Int)
    fun editAction(action: Int)
    fun pickVideo()
    fun videoUri(): Uri?
    fun pendingUrl(): String?
    fun statusLine(): String
    fun controls(): List<Pair<String, () -> Unit>>
    fun assistant(context: Context): SpatialApp
    fun exit()
}

fun appTitle(context: Context, app: AppType): String = context.getString(
    when (app) {
        AppType.HOME -> R.string.app_home
        AppType.BROWSER -> R.string.app_browser
        AppType.NOTES -> R.string.app_notes
        AppType.CALCULATOR -> R.string.app_calculator
        AppType.CLOCK -> R.string.app_clock
        AppType.MEDIA -> R.string.app_media
        AppType.SETTINGS -> R.string.app_controls
        AppType.ASSISTANT -> R.string.app_assistant
        AppType.KEYBOARD -> R.string.app_keyboard
    }
)

fun buildApp(context: Context, type: AppType, shell: Shell): SpatialApp = when (type) {
    AppType.HOME -> home(context, shell)
    AppType.BROWSER -> browser(context, shell)
    AppType.NOTES -> notes(context, shell)
    AppType.CALCULATOR -> calculator(context)
    AppType.CLOCK -> clock(context)
    AppType.MEDIA -> media(context, shell)
    AppType.SETTINGS -> controls(context, shell)
    AppType.ASSISTANT -> shell.assistant(context)
    AppType.KEYBOARD -> keyboard(context, shell)
}

private fun home(context: Context, shell: Shell): SpatialApp {
    lateinit var status: TextView
    val view = context.vertical {
        status = text(size = 14f).also { it.gravity = Gravity.CENTER }
        val items = listOf(
            "🌐" to AppType.BROWSER, "📝" to AppType.NOTES, "🧮" to AppType.CALCULATOR, "⏱" to AppType.CLOCK,
            "🎬" to AppType.MEDIA, "✨" to AppType.ASSISTANT, "⌨" to AppType.KEYBOARD, "⚙" to AppType.SETTINGS
        ).map { (icon, type) -> "$icon\n${appTitle(context, type)}" to { shell.open(type) } } +
            ("▶\nYouTube" to { shell.openUrl("https://m.youtube.com") }) +
            ("⏏\n${context.getString(R.string.exit_spatial)}" to { shell.exit() })
        items.chunked(5).forEach { chunk -> row(1f) { chunk.forEach { (label, action) -> key(label, 1f, action) } } }
    }
    val stop = ticker(2000) { status.text = shell.statusLine() }
    return SpatialApp(view, stop)
}

@SuppressLint("SetJavaScriptEnabled")
private fun browser(context: Context, shell: Shell): SpatialApp {
    val web = WebView(context)
    val address = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.address_hint) }
    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("https://") -> text
            text.startsWith("http://") -> "https://" + text.removePrefix("http://")
            '.' in text && ' ' !in text -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
        web.loadUrl(url)
        web.requestFocus()
    }
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)
    }
    web.webViewClient = object : WebViewClient() {
        // Only web pages stay inside the browser; app links and other schemes are ignored.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"
        override fun onPageFinished(view: WebView, url: String) { if (!address.hasFocus()) address.setText(url) }
    }
    web.webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }
    address.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) go(address.text.toString())
        code == KeyEvent.KEYCODE_ENTER
    }
    val bar = LinearLayout(context).apply {
        key("‹", 1f) { if (web.canGoBack()) web.goBack() }
        key("›", 1f) { if (web.canGoForward()) web.goForward() }
        key("⟳", 1f) { web.reload() }
        addView(address, LinearLayout.LayoutParams(0, -1, 6f))
        key("➜", 1f) { go(address.text.toString()) }
        key("⌨", 1f) { shell.open(AppType.KEYBOARD) }
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(bar, LinearLayout.LayoutParams(-1, context.px(46)))
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    go(shell.pendingUrl() ?: "https://www.google.com")
    // The shell reaches an already open browser through the root view's tag.
    view.tag = { url: String -> go(url) }
    return SpatialApp(view) { web.stopLoading(); web.destroy() }
}

private fun notes(context: Context, shell: Shell): SpatialApp {
    val file = File(context.applicationContext.filesDir, "note.txt")
    val input = editor(context).apply {
        gravity = Gravity.TOP or Gravity.START
        textSize = 17f
        hint = context.getString(R.string.notes_hint)
        setText(runCatching { file.readText() }.getOrDefault(""))
        setSelection(text.length)
    }
    fun save() { runCatching { file.writeText(input.text.toString()) } }
    val handler = Handler(Looper.getMainLooper())
    val autosave = Runnable { save() }
    input.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(autosave); handler.postDelayed(autosave, 600) }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    val view = context.vertical {
        addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
        key("⌨ " + context.getString(R.string.app_keyboard)) { input.requestFocus(); shell.open(AppType.KEYBOARD) }
    }
    input.requestFocus()
    return SpatialApp(view) { handler.removeCallbacks(autosave); save() }
}

private fun calculator(context: Context): SpatialApp {
    val expression = StringBuilder()
    val history = ArrayDeque<String>()
    lateinit var past: TextView
    lateinit var display: TextView
    val view = context.vertical {
        past = text(size = 12f)
        display = text("0", 28f).also { it.gravity = Gravity.END; it.textDirection = View.TEXT_DIRECTION_LTR }
        fun show() { display.text = expression.ifEmpty { "0" } }
        val rows = listOf(
            listOf("sin(", "cos(", "tan(", "sqrt(", "^"), listOf("ln(", "log(", "(", ")", "π"),
            listOf("7", "8", "9", "÷", "C"), listOf("4", "5", "6", "×", "⌫"),
            listOf("1", "2", "3", "-", "%"), listOf("0", ".", "e", "+", "=")
        )
        rows.forEach { keys ->
            row(1f) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                keys.forEach { symbol ->
                    key(symbol.removeSuffix("(").let { if (it.isEmpty()) "(" else it }) {
                        when (symbol) {
                            "C" -> { expression.clear(); show() }
                            "⌫" -> { if (expression.isNotEmpty()) expression.deleteCharAt(expression.length - 1); show() }
                            "=" -> try {
                                val value = Calculator.evaluate(expression.toString())
                                val result = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
                                history.addLast("$expression = $result")
                                if (history.size > 3) history.removeFirst()
                                past.text = history.joinToString("\n")
                                expression.clear().append(result)
                                show()
                            } catch (e: IllegalArgumentException) {
                                display.text = context.getString(R.string.calc_error)
                            }
                            else -> { if (expression.length < 200) expression.append(symbol); show() }
                        }
                    }
                }
            }
        }
    }
    return SpatialApp(view)
}

private fun clock(context: Context): SpatialApp {
    var startedAt = 0L
    var elapsed = 0L
    var timerEnd = 0L
    lateinit var watch: TextView
    lateinit var timer: TextView
    val view = context.vertical {
        addView(TextClock(context).apply {
            format12Hour = "h:mm:ss a"; format24Hour = "HH:mm:ss"
            textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        watch = text(size = 26f)
        row(1f) {
            key(context.getString(R.string.stopwatch_toggle)) {
                if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
                else { elapsed += SystemClock.elapsedRealtime() - startedAt; startedAt = 0 }
            }
            key(context.getString(R.string.reset)) { startedAt = 0; elapsed = 0 }
        }
        timer = text(size = 26f)
        row(1f) {
            for (minutes in listOf(1, 5, 10, 25)) key("$minutes′") { timerEnd = SystemClock.elapsedRealtime() + minutes * 60_000L }
            key("✕") { timerEnd = 0 }
        }
    }
    val stop = ticker(100) {
        val now = SystemClock.elapsedRealtime()
        val total = elapsed + if (startedAt > 0) now - startedAt else 0
        watch.text = "⏱ %02d:%02d.%d".format(total / 60000, total / 1000 % 60, total / 100 % 10)
        val left = (timerEnd - now).coerceAtLeast(0)
        timer.text = when {
            timerEnd == 0L -> "⏳ --:--"
            left == 0L -> "⏳ " + context.getString(R.string.time_up)
            else -> "⏳ %02d:%02d".format((left + 999) / 60000, (left + 999) / 1000 % 60)
        }
    }
    return SpatialApp(view, stop)
}

private fun media(context: Context, shell: Shell): SpatialApp {
    val video = VideoView(context)
    lateinit var status: TextView
    var current: Uri? = null
    val view = context.vertical {
        status = text(context.getString(R.string.media_hint))
        addView(FrameLayout(context).apply { addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            key(context.getString(R.string.choose_video), 2f) { shell.pickVideo() }
            key("⏪") { video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0)) }
            key("⏯") { if (video.isPlaying) video.pause() else video.start() }
            key("⏩") { video.seekTo(video.currentPosition + 10_000) }
        }, LinearLayout.LayoutParams(-1, context.px(50)))
    }
    video.setOnErrorListener { _, _, _ -> status.text = context.getString(R.string.media_error); true }
    val stop = ticker(500) {
        val chosen = shell.videoUri()
        if (chosen != null && chosen != current) {
            current = chosen
            video.setVideoURI(chosen)
            video.start()
            status.text = ""
        }
    }
    return SpatialApp(view) { stop(); video.stopPlayback() }
}

private fun controls(context: Context, shell: Shell): SpatialApp {
    lateinit var status: TextView
    val view = context.vertical {
        status = text(size = 13f)
        shell.controls().chunked(3).forEach { chunk ->
            row(1f) { chunk.forEach { (label, action) -> key(label, 1f) { action(); status.text = shell.statusLine() } } }
        }
    }
    val stop = ticker(1000) { status.text = shell.statusLine() }
    return SpatialApp(view, stop)
}

private fun keyboard(context: Context, shell: Shell): SpatialApp {
    val layouts = listOf(
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        listOf("ضصثقفغعهخحجد", "شسيبلاتنمكط", "ئءؤرىةوزظذ"),
        listOf("1234567890", "@#£_&-+()/", "*\"':;!?.,="),
        listOf("أإآًٌٍَُِّْ", "١٢٣٤٥٦٧٨٩٠", "،؛؟«»ـ")
    )
    val names = listOf("EN", "عربي", "123", "تشكيل")
    var layout = if (context.resources.configuration.locales[0].language == "ar") 1 else 0
    var shift = false
    val view = context.vertical { layoutDirection = View.LAYOUT_DIRECTION_LTR }
    fun render() {
        view.removeAllViews()
        layouts[layout].forEachIndexed { index, letters ->
            view.row(1f) {
                // Arabic rows are listed in right-to-left keyboard order.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (index == 2 && layout == 0) key("⇧", 1.5f) { shift = !shift; render() }
                letters.forEach { c ->
                    val value = if (shift && layout == 0) c.uppercase() else c.toString()
                    key(value) { shell.typeText(value) }
                }
                if (index == 2) key("⌫", 1.5f) { shell.sendKey(KeyEvent.KEYCODE_DEL) }
            }
        }
        view.row(1f) {
            names.forEachIndexed { i, name -> if (i != layout) key(name, 1.2f) { layout = i; render() } }
            key("␣", 3f) { shell.typeText(" ") }
            key("◀") { shell.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            key("▶") { shell.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            key("⏎", 1.5f) { shell.sendKey(KeyEvent.KEYCODE_ENTER) }
        }
        view.row(1f) {
            key(context.getString(R.string.select_all)) { shell.editAction(android.R.id.selectAll) }
            key(context.getString(R.string.copy)) { shell.editAction(android.R.id.copy) }
            key(context.getString(R.string.paste)) { shell.editAction(android.R.id.paste) }
        }
    }
    render()
    return SpatialApp(view)
}
