package app.vellune.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TemplatesTest {
    private val tpl = Page(title = "Meeting", tags = listOf("Template"), blocks = listOf(Block(kind = "heading", text = "{{title}} — {{date}} {{time}}", indent = 1)))

    @Test fun recognisesTemplates() {
        assertTrue(tpl.isTemplate)
        assertFalse(tpl.copy(tags = listOf("templates")).isTemplate)
    }

    @Test fun copiesWithFreshIdsAndFilledText() {
        val b = instantiateTemplate(tpl, "Standup", LocalDateTime.of(2026, 10, 17, 9, 5)).single()
        assertEquals("Standup — 2026-10-17 09:05", b.text)
        assertEquals(1, b.depth)
        assertNotEquals(tpl.blocks[0].id, b.id)
    }
}

class DateMentionsTest {
    private val fri = java.time.LocalDate.of(2026, 10, 16)

    @Test fun relativeDays() = assertEquals(
        listOf("Today" to "2026-10-16", "Tomorrow" to "2026-10-17", "Yesterday" to "2026-10-15"), dateMentions("", fri))

    @Test fun weekdaysAreTheNextOne() {
        assertEquals(listOf("Next Monday" to "2026-10-19"), dateMentions("mon", fri))
        assertEquals(listOf("Next Friday" to "2026-10-23"), dateMentions("fr", fri))
    }
}
