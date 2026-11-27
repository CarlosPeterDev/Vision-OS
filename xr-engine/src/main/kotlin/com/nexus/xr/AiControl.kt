package com.nexus.xr

class AiPlan(val steps: List<VoiceCommand>, val say: String, val rejected: List<String>)

/**
 * Lets an AI model drive the shell using only the commands a user could speak. Its answer is
 * parsed with the same parser, so it can never do anything outside that list.
 */
object AiControl {
    const val MAX_STEPS = 8

    private val parser = VoiceCommands()

    fun systemPrompt(focused: AppType?): String = buildString {
        appendLine("You control a spatial computing shell on a phone. Turn the user's request into steps.")
        appendLine("Reply ONLY with lines in these two forms:")
        appendLine("DO: <one command>")
        appendLine("SAY: <short answer in the user's language>")
        appendLine("At most $MAX_STEPS DO lines, in order. Use SAY alone for questions or when no command fits.")
        appendLine("Never claim to have done something that no DO line does.")
        appendLine("Commands with text: type <text> | search for <query> | go to <website> | click <button label> |")
        appendLine("set timer for <n> minutes | add task <text> | remind me in <n> minutes to <text> |")
        appendLine("remind me in <n> hours to <text>")
        append("Open or close an app: open <app> | close <app>. Apps: ")
        appendLine(VoiceCommands.NAMES.values.joinToString(", ") { it.first() })
        append("Other commands: close all, exit, recenter, arrange, cinema, take photo, work space, study space, cinema space, ")
        appendLine(Act.entries.joinToString(", ") { it.phrases.first() })
        append("The window in use is: ${focused?.name?.lowercase() ?: "none"}.")
    }

    fun parse(reply: String): AiPlan {
        val steps = mutableListOf<VoiceCommand>()
        val said = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        var marked = false
        for (raw in reply.lines()) {
            val line = raw.trim().removePrefix("- ").trim('`', '*', ' ')
            when {
                line.startsWith("DO:", ignoreCase = true) -> {
                    marked = true
                    val text = line.substring(3).trim()
                    val command = parser.parse(text)
                    if (command == null || steps.size >= MAX_STEPS) rejected += text else steps += command
                }
                line.startsWith("SAY:", ignoreCase = true) -> { marked = true; said += line.substring(4).trim() }
            }
        }
        // A model that ignores the format is only ever heard, never obeyed.
        return AiPlan(steps, if (marked) said.joinToString(" ") else reply.trim(), rejected)
    }

    private val RISKY = listOf(
        "delete", "remove", "buy", "purchase", "pay", "order", "send", "confirm", "transfer", "sign out", "log out",
        "post", "publish", "subscribe", "submit", "accept", "agree", "install",
        "حذف", "امسح", "مسح", "شراء", "اشتري", "ادفع", "دفع", "ارسال", "ارسل", "ابعت", "تاكيد", "تحويل", "نشر", "موافق", "اشترك", "خروج"
    ).map { VoiceCommands.normalize(it) }

    /** Steps chosen by the AI that the user must approve. Its aim cannot be verified, so presses count. */
    fun needsConfirmation(command: VoiceCommand): Boolean = when (command) {
        VoiceCommand.CloseAll, VoiceCommand.Exit -> true
        is VoiceCommand.Do -> command.act in setOf(Act.CLICK, Act.LONG_CLICK, Act.ENTER, Act.CLEAR_TEXT, Act.CUT, Act.PASTE, Act.CLOSE_TAB)
        is VoiceCommand.Workspace -> command.save
        is VoiceCommand.ClickLabel -> VoiceCommands.normalize(command.label).let { label -> RISKY.any { it in label } }
        else -> false
    }
}
