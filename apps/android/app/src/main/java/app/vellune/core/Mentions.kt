package app.vellune.core

private val WEEKDAYS = java.time.DayOfWeek.entries

/** Journal dates an `@` query can mean, port of inline.ts dateMentions: (label, YYYY-MM-DD). */
fun dateMentions(query: String, today: java.time.LocalDate = java.time.LocalDate.now()): List<Pair<String, String>> {
    val q = query.lowercase()
    val out = mutableListOf<Pair<String, String>>()
    listOf("Today" to 0L, "Tomorrow" to 1L, "Yesterday" to -1L).forEach { (l, d) -> if (l.lowercase().startsWith(q)) out += l to today.plusDays(d).toString() }
    if (q.length >= 2) WEEKDAYS.forEach { w ->
        val name = w.name.lowercase()
        if (!name.startsWith(q)) return@forEach
        val ahead = ((w.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }
        out += "Next ${name.replaceFirstChar { it.uppercase() }}" to today.plusDays(ahead.toLong()).toString()
    }
    if (Regex("""\d{4}-\d{2}-\d{2}""").matches(q) && runCatching { java.time.LocalDate.parse(q) }.isSuccess) out += q to q
    return out
}
