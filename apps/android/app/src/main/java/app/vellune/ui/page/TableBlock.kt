package app.vellune.ui.page

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vellune.core.parseTable
import app.vellune.core.serializeTable
import app.vellune.core.withCell
import app.vellune.core.withCol
import app.vellune.core.withRow
import app.vellune.core.withoutCol
import app.vellune.core.withoutRow

/** Editable grid for a table block; scrolls sideways when wider than the screen. */
@Composable
fun TableBlock(text: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val grid = parseTable(text)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, colors.outlineVariant)) {
                grid.forEachIndexed { r, row ->
                    Row(if (r == 0) Modifier.background(colors.surfaceContainerHigh) else Modifier) {
                        row.forEachIndexed { c, cell ->
                            BasicTextField(
                                cell, { onChange(serializeTable(grid.withCell(r, c, it))) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = colors.onSurface, fontWeight = if (r == 0) FontWeight.SemiBold else null,
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                modifier = Modifier.width(140.dp).border(0.5.dp, colors.outlineVariant).padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        Row {
            TextButton(onClick = { onChange(serializeTable(grid.withRow())) }) {
                Icon(Icons.Outlined.Add, null); Text("Row", Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = { onChange(serializeTable(grid.withCol())) }) {
                Icon(Icons.Outlined.Add, null); Text("Column", Modifier.padding(start = 4.dp))
            }
            Box {
                TextButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More table actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Delete last row") }, { menu = false; onChange(serializeTable(grid.withoutRow(grid.size - 1))) }, enabled = grid.size > 1)
                    DropdownMenuItem({ Text("Delete last column") }, { menu = false; onChange(serializeTable(grid.withoutCol(grid[0].size - 1))) }, enabled = grid[0].size > 1)
                }
            }
        }
    }
}
