package com.nexus.xr

/**
 * Prefix suggestions from a small built-in list plus the words this user types most.
 * Learned words stay on the device.
 */
class WordSuggester(learnedState: String = "", private val limit: Int = 1500) {
    private val learned = LinkedHashMap<String, Int>()

    init {
        learnedState.lineSequence().forEach { line ->
            val parts = line.split('\t')
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && count != null && count > 0) learned[parts[0]] = count
        }
    }

    /** The unfinished word at the end of [textBeforeCursor]. */
    fun currentWord(textBeforeCursor: String) = textBeforeCursor.takeLastWhile { it.isLetter() || it == '\'' }

    fun suggest(prefix: String, count: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val key = prefix.lowercase()
        val mine = learned.entries.filter { it.key.startsWith(key) && it.key != key }
            .sortedByDescending { it.value }.map { it.key }
        val common = (if (isArabic(prefix)) ARABIC else ENGLISH).filter { it.startsWith(key) && it != key }
        return (mine + common).distinct().take(count)
            .map { if (prefix.first().isUpperCase()) it.replaceFirstChar(Char::uppercase) else it }
    }

    fun learn(word: String) {
        val key = word.lowercase()
        if (key.length !in 3..24 || !key.all { it.isLetter() || it == '\'' }) return
        learned[key] = (learned.remove(key) ?: 0) + 1
        // The words used longest ago are forgotten first.
        while (learned.size > limit) learned.remove(learned.keys.first())
    }

    fun clear() = learned.clear()

    fun state() = learned.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    private fun isArabic(text: String) = text.any { it in '؀'..'ۿ' }

    private companion object {
        val ENGLISH = ("the and that have for not with you this but from they say her she will one all would there their what " +
            "about out who get which when make can like time just know take people into year your good some could them see " +
            "other than then now look only come over think also back after use two how our work first well way even new want " +
            "because any these give day most please thanks thank hello today tomorrow tonight morning evening meeting message " +
            "phone video photo music search website address password settings weather news something anything everything").split(' ')
        val ARABIC = ("في من على إلى عن مع هذا هذه ذلك التي الذي كان كانت يكون ليس لكن أو ثم حتى إذا عندما بعد قبل كل بعض غير " +
            "عندي عندك عايز عايزة ممكن دلوقتي النهارده بكرة امبارح ازيك ازاي فين امتى ليه علشان كده خلاص تمام ماشي طيب " +
            "شكرا صباح مساء الخير مرحبا السلام عليكم اهلا حبيبي صاحبي الشغل البيت الموبايل التطبيق الفيديو الصور الموقع " +
            "البحث الأخبار الجو الوقت اليوم الأسبوع الشهر السنة الله الحمد إن شاء").split(' ')
    }
}
