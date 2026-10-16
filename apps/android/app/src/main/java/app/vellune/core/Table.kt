package app.vellune.core

// Table block cells, port of packages/ui/src/table.ts: TSV in the block text, row 0 is the header.

private fun clean(s: String) = s.replace(Regex("[\t\n\r]+"), " ")

fun parseTable(text: String): List<List<String>> {
    val rows = text.split('\n').map { it.split('\t') }
    val cols = maxOf(1, rows.maxOf { it.size })
    return rows.map { r -> List(cols) { r.getOrElse(it) { "" } } }
}

fun serializeTable(g: List<List<String>>) = g.joinToString("\n") { r -> r.joinToString("\t") { clean(it) } }

fun emptyTable(rows: Int = 3, cols: Int = 3) = serializeTable(List(rows) { List(cols) { "" } })

fun List<List<String>>.withCell(r: Int, c: Int, v: String) = mapIndexed { i, row -> if (i == r) row.mapIndexed { j, x -> if (j == c) clean(v) else x } else row }
fun List<List<String>>.withRow() = this + listOf(List(first().size) { "" })
fun List<List<String>>.withCol() = map { it + "" }
fun List<List<String>>.withoutRow(at: Int) = if (size > 1) filterIndexed { i, _ -> i != at } else this
fun List<List<String>>.withoutCol(at: Int) = if (first().size > 1) map { r -> r.filterIndexed { i, _ -> i != at } } else this
