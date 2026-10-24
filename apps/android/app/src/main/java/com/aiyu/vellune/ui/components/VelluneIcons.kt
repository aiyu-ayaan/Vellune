package com.aiyu.vellune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Glyphs missing from Material Icons Extended, drawn on the same 24-unit grid. */
object VelluneIcons {
    val Eraser: ImageVector by lazy {
        ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                // Tilted eraser block with its soft tip, resting on a baseline.
                moveTo(15.1f, 3.4f)
                lineTo(20.6f, 8.9f)
                curveTo(21.4f, 9.7f, 21.4f, 10.9f, 20.6f, 11.7f)
                lineTo(13.8f, 18.5f)
                horizontalLineTo(20f)
                verticalLineTo(20.5f)
                horizontalLineTo(8.6f)
                lineTo(3.4f, 15.3f)
                curveTo(2.6f, 14.5f, 2.6f, 13.3f, 3.4f, 12.5f)
                lineTo(12.3f, 3.4f)
                curveTo(13.1f, 2.6f, 14.3f, 2.6f, 15.1f, 3.4f)
                close()
                moveTo(9.4f, 18.5f)
                horizontalLineTo(11f)
                lineTo(13.3f, 16.2f)
                lineTo(8.2f, 11.1f)
                lineTo(4.8f, 13.9f)
                close()
            }
        }.build()
    }
}
