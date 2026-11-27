package com.nexus.visionos.xr

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.xr.Act
import com.nexus.xr.AppType
import com.nexus.xr.Game2048
import com.nexus.xr.Slide
import com.nexus.xr.Task
import com.nexus.xr.TaskCodec
import com.nexus.xr.TicTacToe
import com.nexus.xr.Units
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.concurrent.Executors

/** The to-do list, shared by its window and by voice commands. */
object TaskStore {
    @Volatile var version = 0
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    fun all(context: Context) = TaskCodec.decode(prefs(context).getString("list", "").orEmpty())
    fun save(context: Context, tasks: List<Task>) {
        prefs(context).edit().putString("list", TaskCodec.encode(tasks.take(200))).apply()
        version++
    }
    fun add(context: Context, text: String) = save(context, listOf(Task(text.trim().take(200))) + all(context))
}

internal fun tasksApp(context: Context, shell: Shell): SpatialApp {
    lateinit var list: LinearLayout
    val input = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.task_hint) }
    fun add() {
        if (input.text.isBlank()) return
        TaskStore.add(context, input.text.toString())
        input.text.clear()
    }
    fun draw() {
        list.removeAllViews()
        val tasks = TaskStore.all(context)
        if (tasks.isEmpty()) list.text(context.getString(R.string.tasks_empty), 13f)
        tasks.forEachIndexed { index, task ->
            list.row(height = context.px(48)) {
                val button = key((if (task.done) "✓  " else "○  ") + task.text, 5f) {
                    TaskStore.save(context, tasks.mapIndexed { i, t -> if (i == index) Task(t.text, !t.done) else t })
                }
                button.alpha = if (task.done) 0.6f else 1f
                button.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                button.setPadding(context.px(14), 0, context.px(8), 0)
                iconKey(R.drawable.ic_ui_close) { TaskStore.save(context, tasks.filterIndexed { i, _ -> i != index }) }
            }
        }
    }
    input.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) add()
        code == KeyEvent.KEYCODE_ENTER
    }
    val view = context.vertical {
        row(height = context.px(52)) {
            addView(input, LinearLayout.LayoutParams(0, -1, 5f))
            iconKey(R.drawable.ic_ui_nearer) { add() }
            iconKey(R.drawable.ic_ui_keyboard) { input.requestFocus(); shell.open(AppType.KEYBOARD) }
        }
        val scroll = context.scrolling { list = this }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    var version = -1
    val stop = ticker(500) { if (version != TaskStore.version) { version = TaskStore.version; draw() } }
    return SpatialApp(view, stop)
}

/** A month at a time; touching a day lists its events and adds new ones with a reminder. */
internal fun calendarApp(context: Context, shell: Shell): SpatialApp {
    var month = YearMonth.now()
    var open: LocalDate? = null
    val locale = context.resources.configuration.locales[0]
    var hour = java.time.LocalTime.now().plusHours(1).hour
    var minute = 0
    val name = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.event_hint) }
    lateinit var body: LinearLayout
    var draw: () -> Unit = {}
    fun zone() = java.time.ZoneId.systemDefault()
    fun dayOf(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    fun monthView() {
        body.row(height = context.px(48)) {
            iconKey(R.drawable.ic_ui_back) { month = month.minusMonths(1); draw() }
            val title = TextView(context).apply { setTextColor(-1); textSize = 17f; gravity = Gravity.CENTER }
            title.text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.year
            title.setOnClickListener { month = YearMonth.now(); draw() }
            addView(title, LinearLayout.LayoutParams(0, -1, 4f))
            iconKey(R.drawable.ic_ui_forward) { month = month.plusMonths(1); draw() }
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        fun cell(line: LinearLayout, value: String, strong: Boolean = false, faded: Boolean = false, click: (() -> Unit)? = null) {
            line.addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(-1)
                alpha = if (faded) 0.6f else 1f
                gravity = Gravity.CENTER
                if (strong) background = LiquidGlass(0x5AB0FF, 0x90, 0xFF)
                if (click != null) setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(2, 2, 2, 2) })
        }
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        grid.row(0.7f) { for (i in 0..6) cell(this, firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale), faded = true) }
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val today = LocalDate.now()
        val busy = EventStore.all(context).map { dayOf(it.atMillis) }.toSet()
        for (week in 0..5) grid.row(1f) {
            for (i in 0..6) {
                val day = week * 7 + i - lead + 1
                if (day in 1..month.lengthOfMonth()) {
                    val date = month.atDay(day)
                    cell(this, if (date in busy) "$day •" else "$day", strong = date == today) { open = date; draw() }
                } else cell(this, "")
            }
        }
    }

    fun dayView(date: LocalDate) {
        body.row(height = context.px(48)) {
            iconKey(R.drawable.ic_ui_back) { open = null; draw() }
            val title = TextView(context).apply { setTextColor(-1); textSize = 16f; gravity = Gravity.CENTER }
            title.text = date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale))
            addView(title, LinearLayout.LayoutParams(0, -1, 5f))
        }
        val list = context.scrolling {
            row(height = context.px(50)) {
                (name.parent as? android.view.ViewGroup)?.removeView(name)
                addView(name, LinearLayout.LayoutParams(0, -1, 4f))
                iconKey(R.drawable.ic_ui_keyboard) { name.requestFocus(); shell.open(AppType.KEYBOARD) }
            }
            row(height = context.px(48)) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                key("−") { hour = (hour + 23) % 24; draw() }
                val clock = text("%02d:%02d".format(hour, minute), 18f)
                removeView(clock)
                key("+") { hour = (hour + 1) % 24; draw() }
                addView(clock, LinearLayout.LayoutParams(0, -1, 1.4f))
                clock.gravity = Gravity.CENTER
                key("−5") { minute = (minute + 55) % 60; draw() }
                key("+5") { minute = (minute + 5) % 60; draw() }
            }
            row(height = context.px(48)) {
                key(context.getString(R.string.event_add)) {
                    if (name.text.isNotBlank()) {
                        shell.addEvent(date.atTime(hour, minute).atZone(zone()).toInstant().toEpochMilli(), name.text.toString())
                        name.text.clear()
                        draw()
                    }
                }
            }
            val events = EventStore.all(context).filter { dayOf(it.atMillis) == date }
            for (event in events) {
                row(height = context.px(48)) {
                    val time = java.time.Instant.ofEpochMilli(event.atMillis).atZone(zone()).toLocalTime()
                    val label = text("%02d:%02d  %s".format(time.hour, time.minute, event.title), 14f)
                    removeView(label)
                    addView(label, LinearLayout.LayoutParams(0, -1, 4f))
                    label.gravity = Gravity.CENTER_VERTICAL
                    iconKey(R.drawable.ic_ui_calendar) {
                        shell.addToPhoneCalendar(event.atMillis, event.title)
                    }.contentDescription = context.getString(R.string.event_to_phone)
                    iconKey(R.drawable.ic_ui_close) { EventStore.remove(context, event.id); draw() }
                }
            }
            text(context.getString(R.string.event_note) + " " + context.getString(R.string.event_icons), 12f).alpha = 0.75f
        }
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    val view = context.vertical { body = this }
    draw = {
        body.removeAllViews()
        open?.let { dayView(it) } ?: monthView()
    }
    var version = -1
    val stop = ticker(1000) { if (version != EventStore.version) { version = EventStore.version; draw() } }
    return SpatialApp(view, stop)
}

private class Stroke(val path: Path, val colour: Int, val width: Float)

@SuppressLint("ViewConstructor")
private class Sketch(context: Context) : View(context) {
    private val strokes = mutableListOf<Stroke>()
    var colour = Color.BLACK
    var thickness = 6f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var x = 0f
    private var y = 0f

    fun undo() { strokes.removeLastOrNull(); invalidate() }
    fun clear() { strokes.clear(); invalidate() }
    val isBlank get() = strokes.isEmpty()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for (stroke in strokes) {
            paint.color = stroke.colour
            paint.strokeWidth = stroke.width
            canvas.drawPath(stroke.path, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.x; y = event.y
                // The tiny segment makes a single tap leave a dot.
                strokes += Stroke(Path().apply { moveTo(x, y); lineTo(x + 0.1f, y) }, colour, thickness)
            }
            MotionEvent.ACTION_MOVE -> {
                strokes.lastOrNull()?.path?.quadTo(x, y, (x + event.x) / 2, (y + event.y) / 2)
                x = event.x; y = event.y
            }
            MotionEvent.ACTION_UP -> strokes.lastOrNull()?.path?.lineTo(event.x, event.y)
        }
        invalidate()
        return true
    }

    fun picture(): Bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        .also { draw(Canvas(it)) }
}

private fun saveDrawing(context: Context, picture: Bitmap): Boolean {
    var uri: Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Nexus-drawing-${System.currentTimeMillis()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NexusVision")
        }
        uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)!!.use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        true
    } catch (e: Exception) {
        uri?.let { context.contentResolver.delete(it, null, null) }
        false
    }
}

internal fun paintApp(context: Context): SpatialApp {
    val sketch = Sketch(context)
    lateinit var status: TextView
    val view = context.vertical {
        row(height = context.px(40)) {
            listOf(0xFF111111, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFFFFFFF).forEach { value ->
                val colour = value.toInt()
                key("●", tint = colour) { sketch.colour = colour }.apply { setTextColor(colour); textSize = 20f }
            }
        }
        row(height = context.px(46)) {
            for ((label, width) in listOf("S" to 3f, "M" to 8f, "L" to 22f)) key(label) { sketch.thickness = width }
            iconKey(R.drawable.ic_ui_back) { sketch.undo() }
            iconKey(R.drawable.ic_ui_delete) { sketch.clear() }
            iconKey(R.drawable.ic_ui_download) {
                if (!sketch.isBlank) {
                    val saved = saveDrawing(context.applicationContext, sketch.picture())
                    status.text = context.getString(if (saved) R.string.drawing_saved else R.string.capture_failed)
                }
            }
        }
        status = text("", 11f)
        addView(sketch, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    return SpatialApp(view)
}

internal fun converterApp(context: Context, shell: Shell): SpatialApp {
    val names = context.resources.getStringArray(R.array.converter_categories)
    val keys = Units.categories.keys.toList()
    var category = 0
    var from = 0
    var to = 1
    val input = editor(context).apply { isSingleLine = true; setText("1"); textSize = 22f }
    lateinit var result: TextView
    lateinit var categoryButton: android.widget.Button
    lateinit var fromButton: android.widget.Button
    lateinit var toButton: android.widget.Button
    fun units() = Units.categories.getValue(keys[category])
    fun update() {
        categoryButton.text = names[category]
        fromButton.text = units()[from].symbol
        toButton.text = units()[to].symbol
        val value = input.text.toString().replace('،', '.').replace(',', '.').toDoubleOrNull()
        result.text = if (value == null) "—" else {
            val converted = Units.convert(value, units()[from], units()[to])
            java.math.BigDecimal(converted).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString() + " " + units()[to].symbol
        }
    }
    val view = context.vertical {
        row(height = context.px(48)) { categoryButton = key("", tint = 0x5AB0FF) { category = (category + 1) % keys.size; from = 0; to = 1; update() } }
        row(height = context.px(52)) { addView(input, LinearLayout.LayoutParams(0, -1, 1f)) }
        row(height = context.px(48)) {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            fromButton = key("") { from = (from + 1) % units().size; update() }
            iconKey(R.drawable.ic_ui_converter) { from = to.also { to = from }; update() }
            toButton = key("") { to = (to + 1) % units().size; update() }
        }
        result = text("", 24f).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        for (digits in listOf("123456", "7890.")) row(1f) {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            for (c in digits) key(c.toString()) { input.append(c.toString()) }
            if ('.' in digits) iconKey(R.drawable.ic_ui_delete) { val t = input.text; if (t.isNotEmpty()) t.delete(t.length - 1, t.length) }
        }
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = update()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    update()
    return SpatialApp(view)
}

/** 2048 and noughts and crosses against the phone. */
internal fun gamesApp(context: Context): SpatialApp {
    val game = Game2048()
    val board = CharArray(9) { ' ' }
    val prefs = context.applicationContext.getSharedPreferences("games", Context.MODE_PRIVATE)
    var chosen = 0
    lateinit var body: LinearLayout
    lateinit var draw: () -> Unit
    fun slide(direction: Slide) { if (game.move(direction)) draw() }
    fun cell(line: LinearLayout, value: String, size: Float, tint: Int, click: () -> Unit) {
        line.key(value, 1f, tint, click).textSize = size
    }
    draw = {
        body.removeAllViews()
        if (chosen == 0) {
            val best = maxOf(prefs.getInt("best", 0), game.score)
            prefs.edit().putInt("best", best).apply()
            body.text(context.getString(R.string.score, game.score, best) + if (game.over()) "  ·  " + context.getString(R.string.game_over) else "", 14f)
            for (y in 0..3) body.row(1f) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                for (x in 0..3) {
                    val value = game.cells[y * 4 + x]
                    val tint = if (value == 0) 0xFFFFFF else Color.HSVToColor(floatArrayOf((50f - Integer.numberOfTrailingZeros(value) * 14f + 360f) % 360f, 0.8f, 1f))
                    cell(this, if (value == 0) "" else value.toString(), 20f, tint) {}
                }
            }
            body.row(height = context.px(46)) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                iconKey(R.drawable.ic_ui_back) { slide(Slide.LEFT) }
                iconKey(R.drawable.ic_ui_up) { slide(Slide.UP) }
                key("↓") { slide(Slide.DOWN) }
                iconKey(R.drawable.ic_ui_forward) { slide(Slide.RIGHT) }
                iconKey(R.drawable.ic_ui_reload) { game.reset(); draw() }
            }
        } else {
            val winner = TicTacToe.winner(board)
            val finished = winner != null || board.none { it == ' ' }
            body.text(
                context.getString(
                    when { winner == 'X' -> R.string.you_win; winner == 'O' -> R.string.phone_wins; finished -> R.string.draw_game; else -> R.string.your_turn }
                ), 15f
            ).gravity = Gravity.CENTER
            for (y in 0..2) body.row(1f) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                for (x in 0..2) {
                    val i = y * 3 + x
                    cell(this, board[i].toString(), 28f, if (board[i] == 'O') 0xFFB066 else 0xFFFFFF) {
                        if (board[i] == ' ' && TicTacToe.winner(board) == null) {
                            board[i] = 'X'
                            // Sometimes the phone plays any free square, so it can be beaten.
                            val free = board.indices.filter { board[it] == ' ' }
                            val reply = if (free.isNotEmpty() && Math.random() < 0.25) free.random() else TicTacToe.bestMove(board, 'O')
                            if (reply >= 0 && TicTacToe.winner(board) == null) board[reply] = 'O'
                            draw()
                        }
                    }
                }
            }
            body.row(height = context.px(46)) { iconKey(R.drawable.ic_ui_reload) { board.fill(' '); draw() } }
        }
    }
    val view = context.vertical {
        row(height = context.px(44)) {
            key("2048") { chosen = 0; draw() }
            key("X O") { chosen = 1; draw() }
        }
        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    draw()
    return SpatialApp(view).also {
        it.command = { act ->
            val direction = when (act) {
                Act.MOVE_LEFT -> Slide.LEFT; Act.MOVE_RIGHT -> Slide.RIGHT
                Act.SCROLL_UP -> Slide.UP; Act.SCROLL_DOWN -> Slide.DOWN
                else -> null
            }
            if (direction != null && chosen == 0) { slide(direction); true } else false
        }
    }
}

private fun fetch(url: String): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

/** Weather for a city the user types. Only that name and its coordinates are sent to open-meteo.com. */
internal fun weatherApp(context: Context, shell: Shell): SpatialApp {
    val executor = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val input = editor(context).apply { isSingleLine = true; hint = context.getString(R.string.city_hint); setText(shell.settings.weatherCity) }
    lateinit var result: TextView
    var request = 0
    fun load() {
        val city = input.text.toString().trim()
        if (city.isEmpty()) return
        shell.settings.weatherCity = city
        result.text = context.getString(R.string.working)
        val mine = ++request
        val language = context.resources.configuration.locales[0].language
        executor.execute {
            val answer = runCatching {
                val places = fetch("https://geocoding-api.open-meteo.com/v1/search?count=1&language=$language&name=" + Uri.encode(city))
                val place = places.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching context.getString(R.string.city_not_found)
                val data = fetch(
                    "https://api.open-meteo.com/v1/forecast?latitude=${place.getDouble("latitude")}&longitude=${place.getDouble("longitude")}" +
                        "&current=temperature_2m,relative_humidity_2m,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&forecast_days=5&timezone=auto"
                )
                val now = data.getJSONObject("current")
                val days = data.getJSONObject("daily")
                buildString {
                    appendLine(place.getString("name") + ", " + place.optString("country"))
                    appendLine(context.getString(R.string.weather_now, now.getDouble("temperature_2m"), now.getInt("relative_humidity_2m"), now.getDouble("wind_speed_10m")))
                    appendLine()
                    val times = days.getJSONArray("time")
                    for (i in 0 until times.length()) {
                        val rain = days.getJSONArray("precipitation_probability_max").optInt(i, 0)
                        appendLine(
                            "%s   %.0f° / %.0f°   ☂ %d%%".format(
                                times.getString(i).substring(5), days.getJSONArray("temperature_2m_max").getDouble(i),
                                days.getJSONArray("temperature_2m_min").getDouble(i), rain
                            )
                        )
                    }
                }
            }.getOrElse { context.getString(R.string.weather_failed) }
            main.post { if (mine == request) result.text = answer }
        }
    }
    input.setOnKeyListener { _, code, event ->
        if (code == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) load()
        code == KeyEvent.KEYCODE_ENTER
    }
    val view = context.vertical {
        row(height = context.px(52)) {
            addView(input, LinearLayout.LayoutParams(0, -1, 5f))
            iconKey(R.drawable.ic_ui_go) { load() }
            iconKey(R.drawable.ic_ui_keyboard) { input.requestFocus(); shell.open(AppType.KEYBOARD) }
        }
        val scroll = context.scrolling { result = text("", 16f) }
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        text(context.getString(R.string.weather_note), 10f).alpha = 0.7f
    }
    // Nothing is sent until the user has chosen a city.
    if (shell.settings.weatherCity.isNotBlank()) load()
    return SpatialApp(view) { request++; executor.shutdownNow() }
}

/** Turns voice control on and off, shows what it heard, and lists every offline command. */
internal fun voiceApp(context: Context, shell: Shell): SpatialApp {
    lateinit var toggle: android.widget.Button
    lateinit var state: TextView
    val groups = context.resources.getStringArray(R.array.command_groups)
    val order = listOf("windows", "pointer", "typing", "browser", "media", "view", "system")
    val arabic = context.resources.configuration.locales[0].language == "ar"
    val view = context.vertical {
        row(height = context.px(50)) { toggle = key("", tint = 0x7FC4FF) { shell.toggleVoiceControl() } }
        state = text("", 13f)
        val list = context.scrolling {
            text(context.getString(R.string.voice_text_commands), 13f)
            order.forEachIndexed { index, group ->
                text(groups[index], 15f).setPadding(0, context.px(8), 0, 0)
                val lines = Act.entries.filter { it.group == group }.joinToString("\n") { act ->
                    // The first phrase in the screen's language.
                    "• " + (act.phrases.firstOrNull { p -> p.any { it in '؀'..'ۿ' } == arabic } ?: act.phrases.first())
                }
                text(lines, 12f)
            }
            text(context.getString(R.string.voice_app_commands), 13f)
        }
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    val stop = ticker(300) {
        toggle.text = context.getString(if (shell.isListening()) R.string.voice_stop else R.string.voice_start)
        state.text = shell.voiceControlState()
    }
    return SpatialApp(view, stop)
}
