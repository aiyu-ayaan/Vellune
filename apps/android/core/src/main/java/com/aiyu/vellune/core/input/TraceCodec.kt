package com.aiyu.vellune.core.input

import kotlinx.serialization.json.Json

/**
 * Input traces as JSON Lines: one [InputEvent] per line, `tMs` relative to the first event.
 * The device recorder writes this format and the JVM tests replay it (docs/05 "Testing").
 */
object TraceCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encodeLine(event: InputEvent): String = json.encodeToString(InputEvent.serializer(), event)

    fun decode(text: String): List<InputEvent> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { json.decodeFromString(InputEvent.serializer(), it) }
        .toList()
}
