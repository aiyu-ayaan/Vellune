package com.aiyu.vellune.core.input

import kotlinx.serialization.Serializable

/** What touched the screen, after classification (docs/05 "InputClassifier"). */
@Serializable
enum class ToolKind { Stylus, Eraser, Finger, Mouse, Unknown }

@Serializable
enum class InputAction { Down, Move, Up, Cancel, HoverEnter, HoverMove, HoverExit }

/** One pointer at one instant, in view pixels. Plain data so traces replay on the JVM. */
@Serializable
data class PointerSample(
    val id: Int,
    val tool: ToolKind,
    val x: Float,
    val y: Float,
    val tMs: Long,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val touchMajor: Float = 0f,
)

/**
 * A single-pointer event. A batched Android `MotionEvent` is flattened into one event per
 * pointer per historical sample, so the arbiter never needs `MotionEvent` itself.
 * [canceled] mirrors `FLAG_CANCELED` on a pointer-up: the OS decided the touch was a palm.
 */
@Serializable
data class InputEvent(
    val action: InputAction,
    val sample: PointerSample,
    val canceled: Boolean = false,
)
