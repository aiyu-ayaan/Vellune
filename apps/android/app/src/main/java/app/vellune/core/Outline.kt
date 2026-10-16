package app.vellune.core

// Block nesting, port of packages/ui/src/outline.ts. Blocks stay a flat list; `indent` makes a
// block the child of the nearest block above it with a smaller indent.

const val MAX_INDENT = 6

/** Indent a block may take at index `i`: at most one deeper than the block above it. */
fun clampIndent(blocks: List<Block>, i: Int, want: Int): Int =
    minOf(want, if (i == 0) 0 else blocks[i - 1].depth + 1, MAX_INDENT).coerceAtLeast(0)

/** Index one past the last descendant of block `i`. */
fun subtreeEnd(blocks: List<Block>, i: Int): Int {
    val d = blocks[i].depth
    var j = i + 1
    while (j < blocks.size && blocks[j].depth > d) j++
    return j
}

/** Blocks shown on screen: descendants of a folded toggle are hidden. */
fun visibleBlocks(blocks: List<Block>, folded: Set<String>): List<Block> {
    val out = ArrayList<Block>(blocks.size)
    var i = 0
    while (i < blocks.size) {
        out += blocks[i]
        i = if (blocks[i].kind == "toggle" && blocks[i].id in folded) subtreeEnd(blocks, i) else i + 1
    }
    return out
}

/** Shift block `i` and its descendants by `delta` levels, keeping the tree valid. */
fun shiftIndent(blocks: List<Block>, i: Int, delta: Int): List<Block> {
    if (i !in blocks.indices) return blocks
    val end = subtreeEnd(blocks, i)
    val by = clampIndent(blocks, i, blocks[i].depth + delta) - blocks[i].depth
    if (by == 0) return blocks
    return blocks.mapIndexed { j, b ->
        if (j < i || j >= end) b else (b.depth + by).coerceIn(0, MAX_INDENT).let { b.copy(indent = it.takeIf { d -> d > 0 }) }
    }
}

/** 1-based position of a numbered block among numbered siblings at its depth. */
fun listNumber(blocks: List<Block>, i: Int): Int {
    val d = blocks[i].depth
    var n = 1
    for (j in i - 1 downTo 0) {
        val dj = blocks[j].depth
        if (dj > d) continue
        if (dj < d || blocks[j].kind != "numbered") break
        n++
    }
    return n
}
