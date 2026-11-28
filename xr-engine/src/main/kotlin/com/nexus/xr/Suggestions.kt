package com.nexus.xr

/**
 * Suggestions from a small built-in list plus the words this user types most: completions,
 * a likely next word, and fixes for one-letter slips. Learned words stay on the device.
 */
class WordSuggester(learnedState: String = "", private val limit: Int = 1500) {
    private val learned = LinkedHashMap<String, Int>()
    /** "previous next" to how often the pair was typed. */
    private val pairs = LinkedHashMap<String, Int>()

    init {
        learnedState.lineSequence().forEach { line ->
            val parts = line.split('\t')
            val count = parts.last().toIntOrNull()
            if (count == null || count <= 0) return@forEach
            if (parts.size == 2) learned[parts[0]] = count
            if (parts.size == 3) pairs[parts[0] + " " + parts[1]] = count
        }
    }

    /** The unfinished word at the end of [textBeforeCursor]. */
    fun currentWord(textBeforeCursor: String) = textBeforeCursor.takeLastWhile(::inWord)

    /** The finished word just before the current one, or nothing after punctuation. */
    fun previousWord(textBeforeCursor: String): String {
        val before = textBeforeCursor.dropLast(currentWord(textBeforeCursor).length)
        if (!before.endsWith(' ')) return ""
        return before.trimEnd(' ').takeLastWhile(::inWord)
    }

    fun suggest(prefix: String, count: Int = 3, previous: String = ""): List<String> {
        val before = previous.lowercase()
        if (prefix.isEmpty()) return if (before.isEmpty()) emptyList() else following(before).take(count)
        val key = prefix.lowercase()
        val likely = if (before.isEmpty()) emptyList() else following(before).filter { it.startsWith(key) && it != key }
        val mine = learned.entries.filter { it.key.startsWith(key) && it.key != key }
            .sortedByDescending { it.value }.map { it.key }
        val common = vocabulary(prefix).filter { it.startsWith(key) && it != key }
        var found = (likely + mine + common).distinct().take(count)
        // A word that is already spelled correctly is never "fixed".
        if (found.size < count && key.length >= 3 && key !in learned && key !in vocabulary(prefix)) {
            val fixes = (learned.keys.asSequence() + vocabulary(prefix).asSequence()).filter { oneSlip(key, it) }
            found = (found + fixes).distinct().take(count)
        }
        return found.map { if (prefix.first().isUpperCase()) it.replaceFirstChar(Char::uppercase) else it }
    }

    private fun following(word: String): List<String> = pairs.entries.filter { it.key.startsWith("$word ") }
        .sortedByDescending { it.value }.map { it.key.substring(word.length + 1) }

    fun learn(word: String, previous: String = "") {
        val key = word.lowercase()
        if (!learnable(key) || key.length < 2) return
        if (key.length >= 3) count(learned, key, limit)
        val before = previous.lowercase()
        if (before.length >= 2 && learnable(before)) count(pairs, "$before $key", limit)
    }

    private fun learnable(word: String) = word.length <= 24 && word.isNotEmpty() && word.all(::inWord)

    private fun count(map: LinkedHashMap<String, Int>, key: String, most: Int) {
        map[key] = (map.remove(key) ?: 0) + 1
        // The entries used longest ago are forgotten first.
        while (map.size > most) map.remove(map.keys.first())
    }

    fun clear() { learned.clear(); pairs.clear() }

    fun state() = (learned.entries.map { "${it.key}\t${it.value}" } +
        pairs.entries.map { it.key.replace(' ', '\t') + "\t" + it.value }).joinToString("\n")

    private fun inWord(c: Char) = c.isLetter() || c == '\''

    private fun vocabulary(text: String) = if (text.any { it in '؀'..'ۿ' }) ARABIC else ENGLISH

    companion object {
        /** True when [a] becomes [b] by one wrong, missing, extra or swapped letter. */
        fun oneSlip(a: String, b: String): Boolean {
            if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            while (i < a.length && i < b.length && a[i] == b[i]) i++
            if (a.length == b.length) {
                if (a.substring(i + 1) == b.substring(i + 1)) return true
                return i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
            }
            val (long, short) = if (a.length > b.length) a to b else b to a
            return long.substring(i + 1) == short.substring(i)
        }

        private val ENGLISH = ("the and that have for not with you this but from they say her she will one all would there their what " +
            "about out who get which when make can like time just know take people into year your good some could them see " +
            "other than then now look only come over think also back after use two how our work first well way even new want " +
            "because any these give day most please thanks thank hello today tomorrow tonight morning evening meeting message " +
            "phone video photo music search website address password settings weather news something anything everything").split(' ')
        private val ARABIC = ("في من على إلى عن مع هذا هذه ذلك التي الذي كان كانت يكون ليس لكن أو ثم حتى إذا عندما بعد قبل كل بعض غير " +
            "عندي عندك عايز عايزة ممكن دلوقتي النهارده بكرة امبارح ازيك ازاي فين امتى ليه علشان كده خلاص تمام ماشي طيب " +
            "شكرا صباح مساء الخير مرحبا السلام عليكم اهلا حبيبي صاحبي الشغل البيت الموبايل التطبيق الفيديو الصور الموقع " +
            "البحث الأخبار الجو الوقت اليوم الأسبوع الشهر السنة الله الحمد إن شاء").split(' ')
    }
}
