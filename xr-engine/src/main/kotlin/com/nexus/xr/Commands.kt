package com.nexus.xr

/** The only things speech can make the shell do. There is no free-form execution. */
sealed interface VoiceCommand {
    data class Open(val app: AppType) : VoiceCommand
    data class Close(val app: AppType) : VoiceCommand
    data object CloseAll : VoiceCommand
    data object Exit : VoiceCommand
    data object Recenter : VoiceCommand
    data object Arrange : VoiceCommand
    data object Cinema : VoiceCommand
    data object Photo : VoiceCommand
}

sealed interface CommandResult {
    data class Run(val command: VoiceCommand) : CommandResult
    /** The command loses work, so the user must confirm it first. */
    data class AskToConfirm(val command: VoiceCommand) : CommandResult
    data object Cancelled : CommandResult
}

/**
 * Matches a whole short English or Arabic sentence. Anything else, including longer sentences
 * that merely mention a command, is left for the AI model.
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
        val command = parse(text) ?: return null
        if (command is VoiceCommand.CloseAll || command is VoiceCommand.Exit) {
            pending = command
            pendingSince = nowMs
            return CommandResult.AskToConfirm(command)
        }
        return CommandResult.Run(command)
    }

    fun parse(sentence: String): VoiceCommand? {
        var words = normalize(sentence).split(' ').filter { it.isNotEmpty() && it !in FILLER }
        val text = words.joinToString(" ")
        FIXED.entries.firstOrNull { text in it.value }?.let { return it.key }
        if (words.size < 2 || words.size > 5) return null
        val verb = words.first()
        words = words.drop(1)
        val name = words.joinToString(" ")
        val app = NAMES.entries.firstOrNull { name in it.value }?.key ?: return null
        return when (verb) {
            in OPEN -> VoiceCommand.Open(app)
            in CLOSE -> VoiceCommand.Close(app)
            else -> null
        }
    }

    companion object {
        /** Lower case, no punctuation, and Arabic spelling variants folded together. */
        fun normalize(text: String): String = text.lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        private fun set(vararg values: String) = values.map { normalize(it) }.toSet()

        private val FILLER = set("please", "the", "app", "nexus", "لو", "سمحت", "من", "فضلك", "تطبيق", "برنامج", "يا", "نكسس")
        private val OPEN = set("open", "show", "start", "launch", "افتح", "شغل", "اعرض", "وريني")
        private val CLOSE = set("close", "hide", "اقفل", "قفل", "اغلق", "اخفي")
        private val CONFIRM = set("confirm", "yes confirm", "تأكيد", "أكد", "ايوه اكد", "نعم تأكيد")
        private val CANCEL = set("cancel", "no", "الغي", "الغاء", "لا")

        private val FIXED: Map<VoiceCommand, Set<String>> = mapOf(
            VoiceCommand.CloseAll to set("close all", "close all windows", "close everything", "اقفل كل حاجه", "اقفل الكل", "اغلق الكل", "اقفل كل النوافذ"),
            VoiceCommand.Exit to set("exit", "exit spatial mode", "quit", "اخرج", "خروج", "اطلع بره"),
            VoiceCommand.Recenter to set("recenter", "re center", "center view", "وسط العرض", "ظبط المنتصف"),
            VoiceCommand.Arrange to set("arrange", "arrange windows", "رتب", "رتب النوافذ", "رتب الشبابيك"),
            VoiceCommand.Cinema to set("cinema", "cinema mode", "وضع السينما", "سينما"),
            VoiceCommand.Photo to set("take photo", "take a photo", "screenshot", "take screenshot", "صور", "خد صوره", "صور الشاشه"),
            VoiceCommand.Open(AppType.HOME) to set("go home", "home", "الرئيسيه")
        )

        private val NAMES: Map<AppType, Set<String>> = mapOf(
            AppType.HOME to set("home", "الرئيسيه", "هوم"),
            AppType.BROWSER to set("browser", "web browser", "المتصفح", "متصفح"),
            AppType.NOTES to set("notes", "note", "الملاحظات", "ملاحظات", "النوتس"),
            AppType.CALCULATOR to set("calculator", "الاله الحاسبه", "اله حاسبه", "الحاسبه"),
            AppType.CLOCK to set("clock", "timer", "stopwatch", "الساعه", "المؤقت"),
            AppType.MEDIA to set("player", "video player", "media", "المشغل", "مشغل الفيديو"),
            AppType.SETTINGS to set("settings", "controls", "الاعدادات", "اعدادات"),
            AppType.CENTER to set("control center", "مركز التحكم"),
            AppType.KEYBOARD to set("keyboard", "الكيبورد", "لوحه المفاتيح"),
            AppType.TUTORIAL to set("tutorial", "help", "التدريب", "المساعده"),
            AppType.PHOTOS to set("photos", "gallery", "الصور", "المعرض"),
            AppType.FILES to set("files", "الملفات", "ملفات"),
            AppType.CAMERA to set("camera", "capture", "الكاميرا", "التصوير"),
            AppType.LAUNCHER to set("my apps", "apps", "تطبيقاتي", "التطبيقات")
        )
    }
}
