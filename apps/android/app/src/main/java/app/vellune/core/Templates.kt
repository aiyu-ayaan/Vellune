package app.vellune.core

import java.time.LocalDateTime

// Templates, port of packages/ui/src/templates.ts: any page tagged #template.

val Page.isTemplate get() = deletedAt == null && !isFolder && tags.any { it.equals("template", ignoreCase = true) }

fun fillTemplate(text: String, title: String, now: LocalDateTime = LocalDateTime.now()): String = text
    .replace("{{date}}", now.toLocalDate().toString())
    .replace("{{time}}", "%02d:%02d".format(now.hour, now.minute))
    .replace("{{title}}", title)

/** The template's blocks as new blocks (fresh ids, placeholders filled) for a page titled [title]. */
fun instantiateTemplate(t: Page, title: String, now: LocalDateTime = LocalDateTime.now()): List<Block> =
    t.blocks.map { it.copy(id = newId(), text = fillTemplate(it.text, title, now)) }
