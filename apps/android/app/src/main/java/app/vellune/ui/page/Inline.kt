package app.vellune.ui.page

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

// Inline Markdown, same rules and precedence as packages/ui/src/inline.ts. Markers stay in the text
// (dimmed), so a tap offset in the rendered text is the caret offset in the source.

sealed interface Token {
    val range: IntRange
    data class Styled(override val range: IntRange, val type: String, val inner: IntRange) : Token
    data class Wiki(override val range: IntRange, val target: String) : Token
    data class Url(override val range: IntRange, val href: String, val inner: IntRange) : Token
    data class Tag(override val range: IntRange, val tag: String) : Token
}

private val RULES: List<Pair<String, Regex>> = listOf(
    "code" to Regex("`([^`\\n]+)`"),
    "wikilink" to Regex("\\[\\[([^\\]|\\n]+?)(?:\\|([^\\]\\n]+))?]]"),
    "link" to Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)"),
    "bold" to Regex("\\*\\*([^*\\n]+)\\*\\*"),
    "strike" to Regex("~~([^~\\n]+)~~"),
    "italic" to Regex("\\*([^*\\s][^*\\n]*)\\*|_([^_\\s][^_\\n]*)_"),
    "url" to Regex("https?://[^\\s<>()]+[^\\s<>().,;:!?'\"]"),
    "tag" to Regex("#([\\p{L}\\p{N}_\\-/]*[\\p{L}_\\-/][\\p{L}\\p{N}_\\-/]*)"),
)

fun parseInline(src: String): List<Token> {
    val out = mutableListOf<Token>()
    var i = 0
    while (i < src.length) {
        var matched = false
        for ((type, re) in RULES) {
            // Tags only at a word start, like Obsidian; `_` italics only at word boundaries.
            if (type == "tag" && i > 0 && !src[i - 1].isWhitespace() && src[i - 1] != ZWSP) continue
            val m = re.matchAt(src, i) ?: continue
            if (type == "italic" && m.groups[2] != null &&
                (src.getOrNull(i + m.value.length)?.isLetterOrDigit() == true || src.getOrNull(i - 1)?.isLetterOrDigit() == true)
            ) continue
            val r = i until i + m.value.length
            val g = m.groups[1] ?: m.groups[2]
            out += when (type) {
                "wikilink" -> Token.Wiki(r, m.groupValues[1].trim())
                "link" -> Token.Url(r, m.groupValues[2], m.groups[1]!!.range)
                "url" -> Token.Url(r, m.value, r)
                "tag" -> Token.Tag(r, m.groupValues[1])
                else -> Token.Styled(r, type, g?.range ?: r)
            }
            i += m.value.length
            matched = true
            break
        }
        if (!matched) i++
    }
    return out
}

class InlineColors(val link: Color, val tag: Color, val code: Color, val codeBg: Color, val marker: Color)

/** Invisible first character of every editable block, so a backspace at its start is seen. */
const val ZWSP = '\u200B'

const val LINK_TAG = "wikilink"
const val URL_TAG = "url"
const val HASHTAG = "tag"

fun styleInline(text: String, c: InlineColors): AnnotatedString = buildAnnotatedString {
    append(text)
    for (t in parseInline(text)) {
        val r = t.range
        when (t) {
            is Token.Styled -> {
                val style = when (t.type) {
                    "bold" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "italic" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else -> SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBg, color = c.code)
                }
                addStyle(style, t.inner.first, t.inner.last + 1)
                if (t.inner.first > r.first) addStyle(SpanStyle(color = c.marker), r.first, t.inner.first)
                if (t.inner.last < r.last) addStyle(SpanStyle(color = c.marker), t.inner.last + 1, r.last + 1)
            }
            is Token.Wiki -> {
                addStyle(SpanStyle(color = c.link, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline), r.first + 2, r.last - 1)
                addStyle(SpanStyle(color = c.marker), r.first, r.first + 2)
                addStyle(SpanStyle(color = c.marker), r.last - 1, r.last + 1)
                addStringAnnotation(LINK_TAG, t.target, r.first, r.last + 1)
            }
            is Token.Url -> {
                addStyle(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline), t.inner.first, t.inner.last + 1)
                addStringAnnotation(URL_TAG, t.href, r.first, r.last + 1)
            }
            is Token.Tag -> {
                addStyle(SpanStyle(color = c.tag, fontWeight = FontWeight.Medium), r.first, r.last + 1)
                addStringAnnotation(HASHTAG, t.tag, r.first, r.last + 1)
            }
        }
    }
}

/** Live styling while typing; the text itself is unchanged. */
class InlineTransformation(private val c: InlineColors) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(styleInline(text.text, c), OffsetMapping.Identity)
}
