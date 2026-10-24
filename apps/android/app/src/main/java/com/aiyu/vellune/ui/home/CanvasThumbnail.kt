package com.aiyu.vellune.ui.home

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.aiyu.vellune.core.ink.StrokeGeometry
import com.aiyu.vellune.data.CanvasDoc
import com.aiyu.vellune.ink.toStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fits all strokes of [doc] into the box. Ink geometry is built off the main thread. */
@Composable
fun CanvasThumbnail(doc: CanvasDoc, modifier: Modifier = Modifier) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val content by produceState<Pair<List<Stroke>, FloatArray>?>(null, doc.id, doc.updatedAt) {
        value = withContext(Dispatchers.Default) {
            // shortcut: thumbnails use at most the first 400 strokes; enough to recognise a page.
            val strokes = doc.strokes.take(400)
            if (strokes.isEmpty()) null
            else strokes.map { it.toStroke() } to StrokeGeometry.bounds(strokes.flatMap { it.xy.asIterable() }.toFloatArray())
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val c = content
        if (c == null) {
            Icon(Icons.Rounded.Gesture, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
        } else {
            Canvas(Modifier.matchParentSize()) {
                val (strokes, b) = c
                val pad = size.minDimension * 0.12f
                val w = (b[2] - b[0]).coerceAtLeast(1f)
                val h = (b[3] - b[1]).coerceAtLeast(1f)
                val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h, 1.5f)
                val m = Matrix().apply {
                    setTranslate(-b[0] - w / 2, -b[1] - h / 2)
                    postScale(scale, scale)
                    postTranslate(size.width / 2, size.height / 2)
                }
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    // The renderer expects the canvas to already hold the transform.
                    val save = native.save()
                    native.concat(m)
                    strokes.forEach { renderer.draw(native, it, m) }
                    native.restoreToCount(save)
                }
            }
        }
    }
}
