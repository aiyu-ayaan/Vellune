package app.vellune.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OutlineTest {
    private val bs = listOf(
        Block(kind = "toggle", text = "t"),
        Block(kind = "numbered", text = "a", indent = 1),
        Block(kind = "numbered", text = "a1", indent = 2),
        Block(kind = "numbered", text = "b", indent = 1),
        Block(text = "out"),
    )

    @Test fun foldsToggleChildren() = assertEquals(listOf("t", "out"), visibleBlocks(bs, setOf(bs[0].id)).map { it.text })

    @Test fun numbersPerLevel() {
        assertEquals(2, listNumber(bs, 3))
        assertEquals(1, listNumber(bs, 2))
    }

    @Test fun outdentCarriesChildren() = assertEquals(listOf(0, 0, 1, 1, 0), shiftIndent(bs, 1, -1).map { it.depth })

    @Test fun cannotSkipLevels() {
        assertEquals(1, shiftIndent(bs, 4, 1)[4].depth)
        assertEquals(0, clampIndent(bs, 0, 3))
    }
}
