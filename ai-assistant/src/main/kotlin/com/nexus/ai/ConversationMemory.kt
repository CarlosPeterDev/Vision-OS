package com.nexus.ai

/** One thing the user said and what was answered. */
data class ChatTurn(val user: String, val assistant: String)

/**
 * The last few turns of a spoken conversation, so follow-up questions make sense. Kept in memory
 * only, and forgotten after a quiet spell.
 */
class ConversationMemory(
    private val maxTurns: Int = 6,
    private val forgetAfterMs: Long = 10 * 60_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val turns = ArrayDeque<ChatTurn>()
    private var touched = 0L

    @Synchronized fun history(): List<ChatTurn> {
        if (turns.isNotEmpty() && now() - touched > forgetAfterMs) turns.clear()
        return turns.toList()
    }

    /** [assistant] may be cut short when the user interrupted. Empty answers are not kept. */
    @Synchronized fun add(user: String, assistant: String) {
        if (user.isBlank() || assistant.isBlank()) return
        turns.addLast(ChatTurn(user.take(1000), assistant.take(2000)))
        while (turns.size > maxTurns) turns.removeFirst()
        touched = now()
    }

    @Synchronized fun clear() = turns.clear()
}
