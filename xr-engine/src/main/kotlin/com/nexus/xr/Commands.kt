package com.nexus.xr

/** Actions without a parameter. [phrases] are the complete sentences that trigger them, English then Arabic. */
enum class Act(val group: String, vararg val phrases: String) {
    // Windows
    CLOSE_THIS("windows", "close this", "close window", "close this window", "اقفل دي", "اقفل النافذه", "اقفل الشباك"),
    MINIMIZE("windows", "minimize", "minimize window", "hide window", "صغر النافذه خالص", "خبي النافذه"),
    RESTORE_ALL("windows", "restore all", "show all windows", "رجع الكل", "اظهر كل النوافذ"),
    BIGGER("windows", "bigger", "make it bigger", "enlarge", "كبر", "كبر النافذه"),
    SMALLER("windows", "smaller", "make it smaller", "shrink", "صغر", "صغر النافذه"),
    WIDER("windows", "wider", "make it wider", "وسع", "عرض اكتر"),
    NARROWER("windows", "narrower", "make it narrower", "ضيق", "عرض اقل"),
    TALLER("windows", "taller", "make it taller", "طول", "طول اكتر"),
    SHORTER("windows", "shorter", "make it shorter", "قصر", "طول اقل"),
    CLOSER("windows", "closer", "bring it closer", "come closer", "قرب", "قربها"),
    FARTHER("windows", "farther", "further", "move it away", "ابعد", "ابعدها"),
    MOVE_LEFT("windows", "move left", "move window left", "حرك شمال", "شمال"),
    MOVE_RIGHT("windows", "move right", "move window right", "حرك يمين", "يمين"),
    MOVE_UP("windows", "move up", "move window up", "حرك فوق", "ارفعها"),
    MOVE_DOWN("windows", "move down", "move window down", "حرك تحت", "نزلها"),
    BRING_HERE("windows", "bring here", "bring it here", "come here", "هاتها هنا", "تعالي هنا"),
    NEXT_WINDOW("windows", "next window", "switch window", "النافذه التاليه", "بدل النافذه"),
    SAVE_LAYOUT("windows", "save layout", "احفظ الترتيب"),
    RESTORE_LAYOUT("windows", "restore layout", "load layout", "رجع الترتيب"),

    // Pointing
    CLICK("pointer", "click", "select", "tap", "press", "اضغط", "دوس", "اختار"),
    LONG_CLICK("pointer", "long press", "long click", "hold", "ضغطه طويله", "اضغط مطول"),
    SCROLL_DOWN("pointer", "scroll down", "down", "انزل", "انزل تحت", "اسكرول تحت"),
    SCROLL_UP("pointer", "scroll up", "up", "اطلع", "اطلع فوق", "اسكرول فوق"),
    PAGE_TOP("pointer", "go to top", "top of page", "scroll to top", "اول الصفحه", "فوق خالص"),
    PAGE_BOTTOM("pointer", "go to bottom", "bottom of page", "scroll to bottom", "اخر الصفحه", "تحت خالص"),

    // Typing
    DELETE("typing", "delete", "backspace", "امسح", "امسح حرف"),
    DELETE_WORD("typing", "delete word", "امسح كلمه", "امسح الكلمه"),
    CLEAR_TEXT("typing", "clear text", "clear all text", "delete all text", "امسح كل الكلام", "فضي الخانه"),
    ENTER("typing", "enter", "press enter", "submit", "new line", "انتر", "سطر جديد", "ابعت البحث"),
    SPACE("typing", "space", "add space", "مسافه"),
    SELECT_ALL("typing", "select all", "حدد الكل"),
    COPY("typing", "copy", "انسخ"),
    CUT("typing", "cut", "قص"),
    PASTE("typing", "paste", "الصق"),
    UNDO("typing", "undo", "تراجع"),
    SHOW_KEYBOARD("typing", "show keyboard", "اظهر الكيبورد"),
    HIDE_KEYBOARD("typing", "hide keyboard", "اخفي الكيبورد"),

    // Browser
    BACK("browser", "go back", "back", "ارجع", "رجوع"),
    FORWARD("browser", "go forward", "forward", "قدام", "للامام"),
    RELOAD("browser", "reload", "refresh", "حدث", "اعمل تحديث"),
    STOP_LOADING("browser", "stop loading", "وقف التحميل"),
    NEW_TAB("browser", "new tab", "تبويب جديد", "افتح تبويب"),
    CLOSE_TAB("browser", "close tab", "اقفل التبويب"),
    NEXT_TAB("browser", "next tab", "التبويب التالي"),
    PREVIOUS_TAB("browser", "previous tab", "last tab", "التبويب اللي فات"),
    BOOKMARK("browser", "bookmark", "bookmark this page", "save page", "احفظ الصفحه", "ضيفها للمفضله"),
    SHOW_BOOKMARKS("browser", "show bookmarks", "bookmarks", "المفضله", "اعرض المفضله"),
    SHOW_HISTORY("browser", "show history", "history", "السجل", "اعرض السجل"),
    ZOOM_IN("browser", "zoom in", "كبر الصفحه", "زوم ان"),
    ZOOM_OUT("browser", "zoom out", "صغر الصفحه", "زوم اوت"),
    HOME_PAGE("browser", "home page", "الصفحه الرئيسيه"),

    // Media and sound
    PLAY("media", "play", "resume", "شغل الفيديو", "كمل"),
    PAUSE("media", "pause", "stop video", "وقف الفيديو", "ايقاف مؤقت"),
    SKIP("media", "skip", "skip forward", "قدم", "قدم الفيديو"),
    REWIND("media", "rewind", "skip back", "رجع الفيديو", "اخر الفيديو"),
    VOLUME_UP("media", "volume up", "louder", "علي الصوت", "ارفع الصوت"),
    VOLUME_DOWN("media", "volume down", "quieter", "وطي الصوت", "اخفض الصوت"),
    MUTE("media", "mute", "اكتم الصوت", "صامت"),
    UNMUTE("media", "unmute", "رجع الصوت"),

    // View
    BRIGHTER("view", "brighter", "increase brightness", "نور اكتر", "زود الاضاءه"),
    DIMMER("view", "dimmer", "decrease brightness", "نور اقل", "قلل الاضاءه"),
    DIM_MORE("view", "darker background", "dim more", "عتم الخلفيه"),
    DIM_LESS("view", "lighter background", "dim less", "فتح الخلفيه"),
    GAP_WIDER("view", "wider gap", "screens apart", "وسع المسافه", "بعد الشاشتين"),
    GAP_NARROWER("view", "smaller gap", "screens closer", "ضيق المسافه", "قرب الشاشتين"),
    SCREENS_BIGGER("view", "bigger screens", "كبر الشاشتين"),
    SCREENS_SMALLER("view", "smaller screens", "صغر الشاشتين"),
    HEADSET_ON("view", "headset mode", "headset on", "وضع النضاره"),
    HEADSET_OFF("view", "flat mode", "headset off", "وضع الموبايل"),
    CAMERA_VIEW("view", "camera view", "show real world", "اعرض الحقيقه", "وضع الكاميرا"),
    VIRTUAL_VIEW("view", "virtual world", "virtual environment", "العالم الافتراضي"),
    NEXT_SCENE("view", "next scene", "change scene", "غير المنظر"),

    // Capture and system
    START_RECORDING("system", "start recording", "record video", "ابدا التسجيل", "سجل فيديو"),
    STOP_RECORDING("system", "stop recording", "وقف التسجيل"),
    TIME("system", "what time is it", "the time", "الساعه كام", "الوقت"),
    BATTERY("system", "battery", "battery level", "البطاريه", "الشحن كام"),
    HELP("system", "what can i say", "voice commands", "commands", "اقول ايه", "الاوامر"),
    STOP_LISTENING("system", "stop listening", "voice off", "بطل تسمع", "وقف الصوت");
}

/** The only things speech or the AI model can make the shell do. There is no free-form execution. */
sealed interface VoiceCommand {
    data class Open(val app: AppType) : VoiceCommand
    data class Close(val app: AppType) : VoiceCommand
    data object CloseAll : VoiceCommand
    data object Exit : VoiceCommand
    data object Recenter : VoiceCommand
    data object Arrange : VoiceCommand
    data object Cinema : VoiceCommand
    data object Photo : VoiceCommand
    data class Do(val act: Act) : VoiceCommand
    /** Text goes into the focused field, exactly as spoken. */
    data class Type(val text: String) : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    data class GoTo(val address: String) : VoiceCommand
    data class ClickLabel(val label: String) : VoiceCommand
    data class Timer(val minutes: Int) : VoiceCommand
    data class AddTask(val text: String) : VoiceCommand
    /** Shows [text] again after [minutes], even if spatial mode is closed by then. */
    data class Remind(val minutes: Int, val text: String) : VoiceCommand
    /** Switches to a saved set of windows, or saves the current ones into it. */
    data class Workspace(val space: Space, val save: Boolean) : VoiceCommand
}

enum class Space { WORK, STUDY, CINEMA }

sealed interface CommandResult {
    data class Run(val command: VoiceCommand) : CommandResult
    /** The command loses work or acts for the user, so they must confirm it first. */
    data class AskToConfirm(val command: VoiceCommand) : CommandResult
    data object Cancelled : CommandResult
}

/**
 * Matches a whole English or Arabic sentence, or a sentence starting with a command that takes
 * text ("type ...", "search for ..."). Anything else is left for the AI model.
 */
class VoiceCommands(private val confirmWindowMs: Long = 20_000) {
    private var pending: VoiceCommand? = null
    private var pendingSince = 0L

    fun handle(sentence: String, nowMs: Long): CommandResult? {
        val text = normalize(sentence)
        val waiting = pending?.takeIf { nowMs - pendingSince <= confirmWindowMs }
        pending = null
        if (waiting != null) {
            if (text in CONFIRM) return CommandResult.Run(waiting)
            if (text in CANCEL) return CommandResult.Cancelled
        }
        val command = parse(sentence) ?: return null
        if (command is VoiceCommand.CloseAll || command is VoiceCommand.Exit) return askFirst(command, nowMs)
        return CommandResult.Run(command)
    }

    /** Drops a command that was waiting to be confirmed. */
    fun forget() { pending = null }

    /** Holds [command] until the user says "confirm". */
    fun askFirst(command: VoiceCommand, nowMs: Long): CommandResult.AskToConfirm {
        pending = command
        pendingSince = nowMs
        return CommandResult.AskToConfirm(command)
    }

    fun parse(sentence: String): VoiceCommand? {
        val original = sentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val exact = normalize(sentence)
        // Whole sentences are matched first with every word, then without politeness words.
        fixed(exact)?.let { return it }
        val words = exact.split(' ').filter { it.isNotEmpty() && it !in FILLER }
        fixed(words.joinToString(" "))?.let { return it }
        appCommand(words)?.let { return it }
        return withText(original)
    }

    private fun fixed(text: String): VoiceCommand? =
        FIXED[text] ?: ACTS[text]?.let { VoiceCommand.Do(it) }

    private fun appCommand(words: List<String>): VoiceCommand? {
        if (words.size < 2 || words.size > 5) return null
        val name = words.drop(1).joinToString(" ")
        val app = NAMES.entries.firstOrNull { name in it.value }?.key ?: return null
        return when (words.first()) {
            in OPEN -> VoiceCommand.Open(app)
            in CLOSE -> VoiceCommand.Close(app)
            else -> null
        }
    }

    /** Commands that carry the rest of the sentence. The user's own spelling is kept. */
    private fun withText(original: List<String>): VoiceCommand? {
        // One entry per original word that has any letters, so positions stay aligned.
        val spoken = original.filter { normalize(it).isNotEmpty() }
        var start = 0
        while (start < spoken.size && normalize(spoken[start]) in POLITE) start++
        val clean = spoken.drop(start).map { normalize(it) }
        fun rest(prefix: List<String>) = spoken.drop(start + prefix.size).joinToString(" ").trim().trimEnd('.', '،')
        timer(clean)?.let { return it }
        reminder(clean, spoken.drop(start))?.let { return it }
        for ((prefix, build) in PREFIXES) {
            if (clean.size > prefix.size && clean.subList(0, prefix.size) == prefix) {
                val value = rest(prefix)
                if (value.isNotEmpty()) return build(value)
            }
        }
        return null
    }

    /** "remind me in 10 minutes to call home" and the same with the time at the end. */
    private fun reminder(words: List<String>, spoken: List<String>): VoiceCommand? {
        val lead = REMIND_LEADS.firstOrNull { words.size > it.size && words.subList(0, it.size) == it }?.size ?: return null
        /** A duration starting at [at]: its minutes and how many words it took. */
        fun duration(at: Int): Pair<Int, Int>? {
            if (words.getOrNull(at) !in AFTER) return null
            val first = words.getOrNull(at + 1) ?: return null
            ONE_UNIT[first]?.let { return it to 2 }
            val count = number(first) ?: return null
            val unit = UNITS[words.getOrNull(at + 2)] ?: return null
            return count * unit to 3
        }
        fun result(minutes: Int, from: Int, to: Int): VoiceCommand? {
            var begin = from
            if (begin < to && words[begin] in LINKS) begin++
            val text = spoken.subList(begin, to).joinToString(" ").trim().trimEnd('.', '،')
            return if (minutes in 1..MAX_REMIND_MINUTES && text.isNotEmpty()) VoiceCommand.Remind(minutes, text) else null
        }
        duration(lead)?.let { (minutes, used) -> return result(minutes, lead + used, words.size) }
        for (at in words.size - 2 downTo lead + 1) {
            val (minutes, used) = duration(at) ?: continue
            if (at + used == words.size) return result(minutes, lead, at)
        }
        return null
    }

    private fun number(word: String): Int? =
        NUMBERS[word] ?: word.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("").toIntOrNull()

    private fun timer(words: List<String>): VoiceCommand? {
        val joined = words.joinToString(" ")
        val match = TIMER.firstNotNullOfOrNull { it.matchEntire(joined) } ?: return null
        val raw = match.groupValues[1]
        val minutes = number(raw)
        return minutes?.takeIf { it in 1..600 }?.let { VoiceCommand.Timer(it) }
    }

    companion object {
        /** A reply to a question the shell asked. */
        fun isAnswer(text: String) = normalize(text).let { it in CONFIRM || it in CANCEL }

        const val MAX_REMIND_MINUTES = 7 * 24 * 60

        /** Lower case, no punctuation, and Arabic spelling variants folded together. */
        fun normalize(text: String): String = text.lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
            .replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        private fun set(vararg values: String) = values.map { normalize(it) }.toSet()

        private val FILLER = set("please", "the", "app", "nexus", "لو", "سمحت", "من", "فضلك", "تطبيق", "برنامج", "يا", "نكسس")
        private val POLITE = set("please", "nexus", "لو", "سمحت", "من", "فضلك", "يا", "نكسس")
        private val OPEN = set("open", "show", "start", "launch", "افتح", "شغل", "اعرض", "وريني")
        private val CLOSE = set("close", "hide", "اقفل", "قفل", "اغلق", "اخفي")
        private val CONFIRM = set("confirm", "yes confirm", "تأكيد", "أكد", "ايوه اكد", "نعم تأكيد")
        private val CANCEL = set("cancel", "no", "الغي", "الغاء", "لا")

        private val ACTS: Map<String, Act> = buildMap {
            for (act in Act.entries) for (phrase in act.phrases) put(normalize(phrase), act)
        }

        private val REMIND_LEADS = listOf("remind me", "فكرني", "ذكرني").map { normalize(it).split(' ') }
        private val AFTER = set("in", "after", "بعد", "كمان")
        private val LINKS = set("to", "that", "about", "ان", "اني", "انا")
        private val UNITS = buildMap {
            for (word in set("minute", "minutes", "دقيقه", "دقايق", "دقائق")) put(word, 1)
            for (word in set("hour", "hours", "ساعه", "ساعات")) put(word, 60)
        }
        /** Durations said as a single word after "after". */
        private val ONE_UNIT = mapOf(normalize("ساعة") to 60, normalize("ساعتين") to 120, normalize("دقيقة") to 1, normalize("دقيقتين") to 2)

        private val FIXED: Map<String, VoiceCommand> = buildMap {
            fun add(command: VoiceCommand, vararg phrases: String) = phrases.forEach { put(normalize(it), command) }
            add(VoiceCommand.CloseAll, "close all", "close all windows", "close everything", "اقفل كل حاجه", "اقفل الكل", "اغلق الكل", "اقفل كل النوافذ")
            add(VoiceCommand.Exit, "exit", "exit spatial mode", "quit", "اخرج", "خروج", "اطلع بره")
            add(VoiceCommand.Recenter, "recenter", "re center", "center view", "وسط العرض", "ظبط المنتصف")
            add(VoiceCommand.Arrange, "arrange", "arrange windows", "رتب", "رتب النوافذ", "رتب الشبابيك")
            add(VoiceCommand.Cinema, "cinema", "cinema mode", "وضع السينما", "سينما")
            add(VoiceCommand.Photo, "take photo", "take a photo", "screenshot", "take screenshot", "صور", "خد صوره", "صور الشاشه")
            add(VoiceCommand.Open(AppType.HOME), "go home", "home", "الرئيسيه")
            add(VoiceCommand.Workspace(Space.WORK, false), "work space", "work mode", "مساحه الشغل", "وضع الشغل")
            add(VoiceCommand.Workspace(Space.STUDY, false), "study space", "study mode", "مساحه المذاكره", "وضع المذاكره")
            add(VoiceCommand.Workspace(Space.CINEMA, false), "cinema space", "movie mode", "مساحه الافلام", "وضع الافلام")
            add(VoiceCommand.Workspace(Space.WORK, true), "save work space", "احفظ مساحه الشغل")
            add(VoiceCommand.Workspace(Space.STUDY, true), "save study space", "احفظ مساحه المذاكره")
            add(VoiceCommand.Workspace(Space.CINEMA, true), "save cinema space", "احفظ مساحه الافلام")
        }

        private val PREFIXES: List<Pair<List<String>, (String) -> VoiceCommand>> = buildList<Pair<List<String>, (String) -> VoiceCommand>> {
            fun add(build: (String) -> VoiceCommand, vararg prefixes: String) =
                prefixes.forEach { add(normalize(it).split(' ') to build) }
            add({ VoiceCommand.Type(it) }, "type", "write", "اكتب")
            add({ VoiceCommand.Search(it) }, "search for", "search", "google", "ابحث عن", "ابحث علي", "دور علي", "ابحث", "سيرش علي")
            add({ VoiceCommand.GoTo(it) }, "go to", "visit", "open website", "افتح موقع", "روح علي موقع", "روح لموقع", "ادخل علي")
            add({ VoiceCommand.ClickLabel(it) }, "click on", "click", "tap on", "press on", "اضغط علي", "دوس علي")
            add({ VoiceCommand.AddTask(it) }, "add task", "new task", "ضيف مهمه", "مهمه جديده")
        }.sortedByDescending { it.first.size }

        private val TIMER = listOf(
            Regex("(?:set (?:a )?)?timer (?:for )?(\\S+) minutes?"), Regex("(\\S+) minutes? timer"),
            Regex("(?:اعمل |شغل )?(?:مؤقت|تايمر) (\\S+) (?:دقيقه|دقايق|دقائق)")
        )
        private val NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20,
            "thirty" to 30, "واحد" to 1, "خمس" to 5, "خمسه" to 5, "عشر" to 10, "عشره" to 10
        )

        /** App names, English first. */
        val NAMES: Map<AppType, Set<String>> = mapOf(
            AppType.HOME to set("home", "الرئيسيه", "هوم"),
            AppType.BROWSER to set("browser", "web browser", "المتصفح", "متصفح"),
            AppType.NOTES to set("notes", "note", "الملاحظات", "ملاحظات", "النوتس"),
            AppType.CALCULATOR to set("calculator", "الاله الحاسبه", "اله حاسبه", "الحاسبه"),
            AppType.CLOCK to set("clock", "timer", "stopwatch", "الساعه", "المؤقت"),
            AppType.MEDIA to set("player", "video player", "media", "المشغل", "مشغل الفيديو"),
            AppType.SETTINGS to set("settings", "الاعدادات", "اعدادات"),
            AppType.CENTER to set("control center", "controls", "مركز التحكم"),
            AppType.KEYBOARD to set("keyboard", "الكيبورد", "لوحه المفاتيح"),
            AppType.TUTORIAL to set("tutorial", "help", "التدريب", "المساعده"),
            AppType.PHOTOS to set("photos", "gallery", "الصور", "المعرض"),
            AppType.FILES to set("files", "my files", "الملفات", "ملفات", "ملفاتي"),
            AppType.CAMERA to set("camera", "capture", "الكاميرا", "التصوير"),
            AppType.SPACES to set("shortcuts", "my shortcuts", "spaces", "اختصاراتي", "المساحات"),
            AppType.FIT to set("screen fit", "headset fit", "fit screens", "lens settings", "ضبط الشاشتين", "ضبط النضاره"),
            AppType.TASKS to set("tasks", "to do", "todo list", "to do list", "المهام", "مهامي"),
            AppType.CALENDAR to set("calendar", "التقويم", "النتيجه"),
            AppType.PAINT to set("paint", "drawing", "draw", "الرسم", "الرسام"),
            AppType.CONVERTER to set("converter", "unit converter", "المحول", "محول الوحدات"),
            AppType.GAMES to set("games", "game", "الالعاب", "العاب"),
            AppType.WEATHER to set("weather", "الطقس", "الجو"),
            AppType.VOICE to set("voice", "voice control", "التحكم الصوتي", "الصوت"),
            AppType.ASSISTANT to set("assistant", "المساعد")
        )
    }
}
