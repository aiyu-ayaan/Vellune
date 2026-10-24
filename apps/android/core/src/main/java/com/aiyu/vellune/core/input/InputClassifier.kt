package com.aiyu.vellune.core.input

/**
 * Maps Android tool types and button state to [ToolKind]. Constants mirror `MotionEvent` so this
 * stays pure Kotlin and unit-testable.
 */
object InputClassifier {
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4

    /** `MotionEvent.BUTTON_STYLUS_PRIMARY`: the barrel button. */
    const val BUTTON_STYLUS_PRIMARY = 32

    fun classify(toolType: Int, buttonState: Int): ToolKind = when (toolType) {
        TOOL_TYPE_ERASER -> ToolKind.Eraser
        // Barrel button held: erase with the pen tip (docs/05 "Tools").
        TOOL_TYPE_STYLUS -> if (buttonState and BUTTON_STYLUS_PRIMARY != 0) ToolKind.Eraser else ToolKind.Stylus
        TOOL_TYPE_FINGER -> ToolKind.Finger
        TOOL_TYPE_MOUSE -> ToolKind.Mouse
        else -> ToolKind.Unknown
    }
}

val ToolKind.isPen: Boolean get() = this == ToolKind.Stylus || this == ToolKind.Eraser
