package com.nexus.visionos.xr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
import com.nexus.xr.WordSuggester
import java.io.File

fun buildApp(context: Context, type: AppType, shell: Shell): SpatialApp = when (type) {
    AppType.HOME -> home(context, shell)
    AppType.BROWSER -> browser(context, shell)
    AppType.NOTES -> notes(context, shell)
    AppType.CALCULATOR -> calculator(context)
    AppType.CLOCK -> clock(context, shell)
    AppType.MEDIA -> playerApp(context, shell)
    AppType.SETTINGS -> settingsApp(context, shell)
    AppType.ASSISTANT -> shell.assistant(context)
    AppType.KEYBOARD -> keyboard(context, shell)
    AppType.CENTER -> controlCenter(context, shell)
    AppType.TUTORIAL -> tutorial(context, shell)
    AppType.PHOTOS -> photos(context, shell)
    AppType.FILES -> files(context, shell)
    AppType.CAMERA -> captureApp(context, shell)
    AppType.FIT -> headsetFitApp(context, shell)
    AppType.SPACES -> spacesApp(context, shell)
    AppType.PRACTICE -> practiceApp(context, shell)
    AppType.CHECKUP -> checkupApp(context, shell)
    AppType.TASKS -> tasksApp(context, shell)
    AppType.CALENDAR -> calendarApp(context, shell)
    AppType.PAINT -> paintApp(context)
    AppType.CONVERTER -> converterApp(context, shell)
    AppType.GAMES -> gamesApp(context)
    AppType.WEATHER -> weatherApp(context, shell)
    AppType.VOICE -> voiceApp(context, shell)
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
        row(height = context.px(52)) { iconKey(R.drawable.ic_ui_keyboard) { input.requestFocus(); shell.open(AppType.KEYBOARD) } }
    }
    input.requestFocus()
    return SpatialApp(view) { handler.removeCallbacks(autosave); save() }.also { it.flush = ::save }
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
                    val tint = when (symbol) {
                        "=" -> 0x5AB0FF
                        "÷", "×", "-", "+" -> 0xFFB066
                        else -> 0xFFFFFF
                    }
                    key(symbol.removeSuffix("(").let { if (it.isEmpty()) "(" else it }, tint = tint) {
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

private fun clock(context: Context, shell: Shell): SpatialApp {
    var startedAt = 0L
    var elapsed = 0L
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
            for (minutes in listOf(1, 5, 10, 25)) key("$minutes′") { shell.setTimer(minutes) }
            iconKey(R.drawable.ic_ui_close) { shell.setTimer(0) }
        }
    }
    val stop = ticker(100) {
        val now = SystemClock.elapsedRealtime()
        val total = elapsed + if (startedAt > 0) now - startedAt else 0
        watch.text = context.getString(R.string.stopwatch) + "  %02d:%02d.%d".format(total / 60000, total / 1000 % 60, total / 100 % 10)
        val timerEnd = shell.timerEndsAt()
        val left = (timerEnd - now).coerceAtLeast(0)
        timer.text = context.getString(R.string.timer) + "  " + when {
            timerEnd == 0L -> "--:--"
            left == 0L -> context.getString(R.string.time_up)
            else -> "%02d:%02d".format((left + 999) / 60000, (left + 999) / 1000 % 60)
        }
    }
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
    val store = context.applicationContext.getSharedPreferences("words", Context.MODE_PRIVATE)
    val suggester = WordSuggester(store.getString("learned", "").orEmpty())
    fun remember(word: String, previous: String) {
        if (word.isEmpty() || !shell.canSuggest()) return
        suggester.learn(word, previous)
        store.edit().putString("learned", suggester.state()).apply()
    }
    lateinit var suggestions: LinearLayout
    var offered = emptyList<String>()
    fun suggest() {
        val before = if (shell.canSuggest()) shell.textBeforeCursor() else ""
        // With a word half typed these finish or fix it; after a space they guess the next one.
        val next = suggester.suggest(suggester.currentWord(before), previous = suggester.previousWord(before))
        if (next == offered && suggestions.childCount > 0) return
        offered = next
        suggestions.removeAllViews()
        suggestions.iconKey(R.drawable.ic_ui_mic) { shell.dictate() }
        for (i in 0..2) {
            val choice = next.getOrNull(i)
            val button = suggestions.key(choice.orEmpty(), 3f, 0x9CD2FF) {
                if (choice != null && shell.canSuggest()) {
                    val before = shell.textBeforeCursor()
                    shell.replaceBeforeCursor(suggester.currentWord(before).length, "$choice ")
                    remember(choice, suggester.previousWord(before))
                }
            }
            button.visibility = if (choice == null) View.INVISIBLE else View.VISIBLE
        }
    }
    /** A space ends a word, which is when it is worth remembering. */
    fun space() {
        val before = shell.textBeforeCursor()
        remember(suggester.currentWord(before), suggester.previousWord(before))
        shell.typeText(" ")
    }
    val view = context.vertical {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(context.px(6), context.px(4), context.px(6), context.px(4))
    }
    fun render() {
        view.removeAllViews()
        view.row(0.9f) { suggestions = this }
        offered = emptyList()
        layouts[layout].forEachIndexed { index, letters ->
            view.row(1f) {
                // Arabic rows are listed in right-to-left keyboard order.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (index == 2 && layout == 0) iconKey(R.drawable.ic_ui_shift, weight = 1.5f, active = shift) { shift = !shift; render() }
                letters.forEach { c ->
                    val value = if (shift && layout == 0) c.uppercase() else c.toString()
                    key(value) { shell.typeText(value) }
                }
                if (index == 2) iconKey(R.drawable.ic_ui_delete, weight = 1.5f) { shell.sendKey(KeyEvent.KEYCODE_DEL) }
            }
        }
        view.row(1f) {
            names.forEachIndexed { i, name -> if (i != layout) key(name, 1.2f) { layout = i; render() } }
            key(context.getString(R.string.space), 2.6f) { space() }
            iconKey(R.drawable.ic_ui_back) { shell.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            iconKey(R.drawable.ic_ui_forward) { shell.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            iconKey(R.drawable.ic_ui_enter, weight = 1.5f) { shell.sendKey(KeyEvent.KEYCODE_ENTER) }
            key(context.getString(R.string.select_all), 1.2f) { shell.editAction(android.R.id.selectAll) }
            key(context.getString(R.string.copy)) { shell.editAction(android.R.id.copy) }
            key(context.getString(R.string.paste)) { shell.editAction(android.R.id.paste) }
        }
        suggest()
    }
    render()
    val stop = ticker(400) { suggest() }
    return SpatialApp(view, stop)
}
