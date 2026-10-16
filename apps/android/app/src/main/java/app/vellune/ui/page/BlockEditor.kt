package app.vellune.ui.page

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import app.vellune.Vellune
import app.vellune.core.Block
import app.vellune.core.LinkIndex
import app.vellune.core.Page
import app.vellune.core.dateMentions
import app.vellune.core.emptyTable
import app.vellune.core.instantiateTemplate
import app.vellune.core.isTemplate
import app.vellune.core.listNumber
import app.vellune.core.shiftIndent
import app.vellune.core.subtreeEnd
import app.vellune.core.todayKey
import app.vellune.core.visibleBlocks
import app.vellune.ui.AppState
import app.vellune.ui.Dest
import kotlinx.coroutines.launch

private data class Kind(val label: String, val kind: String, val level: Int? = null, val hint: String, val keywords: String)

private val TURN_INTO = listOf(
    Kind("Text", "paragraph", null, "", "paragraph plain"),
    Kind("Heading 1", "heading", 1, "#", "title h1 big"),
    Kind("Heading 2", "heading", 2, "##", "subtitle h2"),
    Kind("Heading 3", "heading", 3, "###", "h3"),
    Kind("Heading 4", "heading", 4, "####", "h4"),
    Kind("To-do list", "todo", null, "[]", "todo task checkbox check"),
    Kind("Bulleted list", "bullet", null, "-", "bullet unordered ul"),
    Kind("Numbered list", "numbered", null, "1.", "number ordered ol"),
    Kind("Quote", "quote", null, ">", "blockquote citation"),
    Kind("Callout", "callout", null, "!>", "note info tip warning highlight"),
    Kind("Code block", "code", null, "```", "code snippet pre"),
    Kind("Toggle list", "toggle", null, "+", "toggle collapse fold details expand"),
)

/** Markdown typed at the start of a paragraph turns it into another block, as on the web. */
private val SHORTCUTS: List<Pair<String, (Block) -> Block>> = listOf(
    "#### " to { b -> b.copy(kind = "heading", level = 4) },
    "### " to { b -> b.copy(kind = "heading", level = 3) },
    "## " to { b -> b.copy(kind = "heading", level = 2) },
    "# " to { b -> b.copy(kind = "heading", level = 1) },
    "[] " to { b -> b.copy(kind = "todo", checked = false) },
    "[ ] " to { b -> b.copy(kind = "todo", checked = false) },
    "[x] " to { b -> b.copy(kind = "todo", checked = true) },
    "+ " to { b -> b.copy(kind = "toggle") },
    "- " to { b -> b.copy(kind = "bullet") },
    "* " to { b -> b.copy(kind = "bullet") },
    "1. " to { b -> b.copy(kind = "numbered") },
    "!> " to { b -> b.copy(kind = "callout") },
    "> " to { b -> b.copy(kind = "quote") },
    "```" to { b -> b.copy(kind = "code") },
)

private sealed interface Suggest {
    val start: Int
    val query: String
    data class Slash(override val start: Int, override val query: String) : Suggest
    data class Link(override val start: Int, override val query: String) : Suggest
    data class Tag(override val start: Int, override val query: String) : Suggest
    data class Mention(override val start: Int, override val query: String) : Suggest
}

/** Which suggestion menu the text before the caret opens (port of inline.ts findTrigger). */
private fun findTrigger(text: String, caret: Int): Suggest? {
    val before = text.substring(0, caret.coerceIn(0, text.length))
    val link = before.lastIndexOf("[[")
    if (link >= 0) {
        val q = before.substring(link + 2)
        if (']' !in q && '\n' !in q && q.length <= 60) return Suggest.Link(link, q)
    }
    Regex("""(?:^|[\s​])/([\p{L}\p{N} ]{0,24})$""").find(before)?.let { return Suggest.Slash(it.range.last - it.groupValues[1].length, it.groupValues[1]) }
    Regex("""(?:^|[\s​])@([\p{L}\p{N}\-]{0,30})$""").find(before)?.let { return Suggest.Mention(it.range.last - it.groupValues[1].length, it.groupValues[1]) }
    Regex("""(?:^|[\s​])#([\p{L}\p{N}_\-/]{0,40})$""").find(before)?.let { return Suggest.Tag(it.range.last - it.groupValues[1].length, it.groupValues[1]) }
    return null
}

private fun Block.continues() = kind in setOf("bullet", "numbered", "todo", "toggle")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocEditor(app: Vellune, nav: AppState, page: Page, index: LinkIndex, modifier: Modifier = Modifier) {
    val ws = app.workspace
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val inline = remember(colors) {
        InlineColors(link = colors.primary, tag = colors.tertiary, code = colors.onSurface, codeBg = colors.surfaceContainerHighest, marker = colors.outline)
    }
    val fields = remember(page.id) { mutableStateMapOf<String, TextFieldValue>() }
    val requesters = remember(page.id) { HashMap<String, FocusRequester>() }
    var focused by remember(page.id) { mutableStateOf<String?>(null) }
    var focusTarget by remember(page.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var suggest by remember(page.id) { mutableStateOf<Pair<String, Suggest>?>(null) }
    val titleFocus = remember { FocusRequester() }
    // Folded toggles are a view choice on this screen, not part of the shared page.
    var folded by remember(page.id) { mutableStateOf(emptySet<String>()) }
    var commentFor by remember(page.id) { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val blocks = page.blocks

    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun edit(f: (List<Block>) -> List<Block>) = ws.updatePage(page.id) { it.copy(blocks = f(it.blocks)) }
    fun replace(b: Block) = edit { bs -> bs.map { if (it.id == b.id) b else it } }
    fun focusAt(id: String, caret: Int) {
        // Compose the block's text field first; the effect below focuses it on the next frame.
        focused = id
        focusTarget = id to caret
    }

    // Remote edits to blocks you aren't typing in show up immediately.
    for (b in blocks) {
        val f = fields[b.id]
        if (f == null || (b.id != focused && f.text.drop(1) != b.text)) fields[b.id] = TextFieldValue(ZWSP + b.text, TextRange(b.text.length + 1))
    }

    LaunchedEffect(focusTarget) {
        val (id, caret) = focusTarget ?: return@LaunchedEffect
        val i = blocks.indexOfFirst { it.id == id }
        if (i >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == id }) list.animateScrollToItem(i + 1)
        fields[id]?.let { fields[id] = it.copy(selection = TextRange(caret + 1)) }
        withFrameNanos { }
        runCatching { requester(id).requestFocus() }
        focusTarget = null
    }

    LaunchedEffect(nav.jumpTo) {
        val i = blocks.indexOfFirst { it.id == nav.jumpTo }
        if (i >= 0) list.animateScrollToItem(i + 1)
        nav.jumpTo = null
    }

    fun indentBy(b: Block, delta: Int) = edit { bs -> shiftIndent(bs, bs.indexOfFirst { it.id == b.id }, delta) }

    fun split(b: Block, before: String, afterLines: List<String>) {
        if (afterLines.size == 1 && before.isEmpty() && afterLines[0].isEmpty() && b.continues()) {
            // Enter on an empty list item steps out one level, then leaves the list.
            if (b.depth > 0) indentBy(b, -1) else replace(b.copy(kind = "paragraph", checked = null))
            fields[b.id] = TextFieldValue(ZWSP.toString(), TextRange(1))
            return
        }
        // An open toggle's Enter writes its first child; a folded one adds a sibling after its subtree.
        val intoToggle = b.kind == "toggle" && b.id !in folded
        val depth = (b.depth + if (intoToggle) 1 else 0).takeIf { it > 0 }
        val next = afterLines.map { line ->
            if (b.continues() && !intoToggle) Block(kind = b.kind, text = line, checked = if (b.kind == "todo") false else null, indent = depth)
            else Block(text = line, indent = depth)
        }
        edit { bs ->
            val at = bs.indexOfFirst { it.id == b.id }
            val to = if (b.kind == "toggle" && !intoToggle) subtreeEnd(bs, at) else at + 1
            bs.take(at) + b.copy(text = before) + bs.subList(at + 1, to) + next + bs.drop(to)
        }
        fields[b.id] = TextFieldValue(ZWSP + before, TextRange(before.length + 1))
        val last = next.last()
        focusAt(last.id, if (next.size == 1) 0 else last.text.length)
    }

    fun backspaceAtStart(b: Block) {
        val i = blocks.indexOfFirst { it.id == b.id }
        if (b.kind != "paragraph") {
            replace(b.copy(kind = "paragraph", level = null, checked = null, language = null))
            focusAt(b.id, 0)
            return
        }
        if (b.depth > 0) {
            indentBy(b, -1)
            focusAt(b.id, 0)
            return
        }
        if (i <= 0) {
            fields[b.id] = TextFieldValue(ZWSP + b.text, TextRange(1))
            return
        }
        val shown = visibleBlocks(blocks, folded)
        val prev = shown.getOrNull(shown.indexOfFirst { it.id == b.id } - 1) ?: return
        if (prev.kind == "table") {
            if (b.text.isEmpty()) edit { bs -> bs.filterNot { it.id == b.id } }
            else fields[b.id] = TextFieldValue(ZWSP + b.text, TextRange(1))
            return
        }
        if (prev.kind == "divider" || prev.kind == "image" || prev.kind == "file") {
            edit { bs -> bs.filterNot { it.id == prev.id } }
            fields[b.id] = TextFieldValue(ZWSP + b.text, TextRange(1))
            return
        }
        edit { bs -> bs.filterNot { it.id == b.id }.map { if (it.id == prev.id) it.copy(text = prev.text + b.text) else it } }
        fields[prev.id] = TextFieldValue(ZWSP + prev.text + b.text)
        focusAt(prev.id, prev.text.length)
    }

    fun onChange(b: Block, v: TextFieldValue) {
        if (!v.text.startsWith(ZWSP)) return backspaceAtStart(b)
        val text = v.text.drop(1).replace(ZWSP.toString(), "")
        val caret = (v.selection.end - 1).coerceIn(0, text.length)
        if (b.kind != "code" && '\n' in text) {
            val lines = text.split('\n')
            return split(b, lines.first(), lines.drop(1))
        }
        if (b.kind == "paragraph") {
            if (text == "---" || text == "***") {
                val after = Block()
                edit { bs -> bs.flatMap { if (it.id == b.id) listOf(Block(id = b.id, kind = "divider"), after) else listOf(it) } }
                return focusAt(after.id, 0)
            }
            SHORTCUTS.firstOrNull { (prefix, _) -> text.startsWith(prefix) && caret == prefix.length }?.let { (prefix, turn) ->
                val nb = turn(b).copy(text = text.removePrefix(prefix))
                replace(nb)
                fields[b.id] = TextFieldValue(ZWSP + nb.text, TextRange(1))
                return
            }
        }
        fields[b.id] = v.copy(text = ZWSP + text, selection = TextRange(v.selection.start.coerceAtLeast(1), v.selection.end.coerceAtLeast(1)))
        if (text != b.text) replace(b.copy(text = text))
        suggest = findTrigger(text, caret)?.let { b.id to it }
    }

    fun applySuggestion(b: Block, s: Suggest, insert: String, turnInto: Kind? = null) {
        val f = fields[b.id] ?: return
        val text = f.text.drop(1)
        val caret = (f.selection.end - 1).coerceIn(0, text.length)
        val cut = if (s is Suggest.Link) s.start else s.start - 1
        val next = text.substring(0, cut.coerceAtLeast(0)) + insert + text.substring(caret)
        var nb = b.copy(text = next)
        if (turnInto != null) nb = nb.copy(kind = turnInto.kind, level = turnInto.level, checked = if (turnInto.kind == "todo") false else null)
        replace(nb)
        val at = cut.coerceAtLeast(0) + insert.length
        fields[b.id] = TextFieldValue(ZWSP + next, TextRange(at + 1))
        suggest = null
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val anchor = focused
        scope.launch {
            val (name, src) = Images.importFile(app, page.id, uri) ?: run {
                android.widget.Toast.makeText(context, "File skipped: over 6 MB, or over 1.5 MB until this page syncs.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val f = Block(kind = "file", text = name, src = src)
            edit { bs ->
                val at = bs.indexOfFirst { it.id == anchor }.let { if (it < 0) bs.size - 1 else it }
                bs.take(at + 1) + f + bs.drop(at + 1)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val anchor = focused
        scope.launch {
            val src = Images.importImage(app, page.id, uri) ?: return@launch
            val img = Block(kind = "image", src = src)
            edit { bs ->
                val at = bs.indexOfFirst { it.id == anchor }.let { if (it < 0) bs.size - 1 else it }
                bs.take(at + 1) + img + bs.drop(at + 1)
            }
        }
    }

    fun openLink(target: String) {
        nav.open(ws.openOrCreate(target, index).id)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "title") {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
                    PageIconHeader(page.icon) { icon -> ws.updatePage(page.id) { it.copy(icon = icon) } }
                    var title by remember(page.id) { mutableStateOf(TextFieldValue(page.title, TextRange(page.title.length))) }
                    if (title.text != page.title && !title.text.contains('\n')) LaunchedEffect(page.title) { title = title.copy(text = page.title) }
                    BasicTextField(
                        value = title,
                        onValueChange = { v ->
                            if ('\n' in v.text) {
                                blocks.firstOrNull()?.let { focusAt(it.id, 0) }
                                return@BasicTextField
                            }
                            title = v
                            ws.updatePage(page.id) { it.copy(title = v.text) }
                        },
                        textStyle = MaterialTheme.typography.displaySmall.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { blocks.firstOrNull()?.let { focusAt(it.id, 0) } }),
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                        decorationBox = { inner ->
                            if (title.text.isEmpty()) Text("Untitled", style = MaterialTheme.typography.displaySmall, color = colors.outline)
                            inner()
                        },
                    )
                    InfoSection(app, nav, page, index)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
                }
            }
            itemsIndexed(visibleBlocks(blocks, folded), key = { _, b -> b.id }) { _, b ->
                val i = blocks.indexOfFirst { it.id == b.id }
                val number = remember(blocks, i) { if (b.kind != "numbered") 0 else listNumber(blocks, i) }
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 24.dp + 24.dp * b.depth, end = 24.dp).animateItem()) {
                    BlockRow(
                        b = b, number = number, field = fields[b.id] ?: TextFieldValue(ZWSP + b.text),
                        folded = b.id in folded, hasKids = subtreeEnd(blocks, i) > i + 1,
                        comments = page.comments.firstOrNull { it.blockId == b.id && it.parentId == null && it.resolved != true }
                            ?.let { page.comments.count { c -> c.blockId == b.id } } ?: 0,
                        onComments = { commentFor = b.id },
                        onFold = { folded = if (b.id in folded) folded - b.id else folded + b.id },
                        focused = focused == b.id, inline = inline, requester = requester(b.id), pageId = page.id, app = app,
                        onFocus = { has -> if (has) focused = b.id else if (focused == b.id) { focused = null; suggest = null } },
                        onChange = { onChange(b, it) },
                        onCheck = { replace(b.copy(checked = it)) },
                        onTapText = { offset -> focusAt(b.id, offset) },
                        onLink = ::openLink,
                        onUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        onTag = { nav.navigate(Dest.Database(it)) },
                        onDeleteBlock = { edit { bs -> bs.filterNot { it.id == b.id } } },
                        onText = { t -> replace(b.copy(text = t)) },
                    )
                    val s = suggest
                    if (s != null && s.first == b.id) SuggestMenu(
                        s.second, ws.pages.value, index,
                        onDismiss = { suggest = null },
                        onPick = { insert, kind -> applySuggestion(b, s.second, insert, kind) },
                        onImage = { applySuggestion(b, s.second, ""); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onFile = { applySuggestion(b, s.second, ""); filePicker.launch(arrayOf("*/*")) },
                        onTable = {
                            applySuggestion(b, s.second, "")
                            val table = Block(kind = "table", text = emptyTable())
                            val after = Block()
                            edit { bs ->
                                val at = bs.indexOfFirst { it.id == b.id }
                                if (bs[at].text.isBlank()) bs.take(at) + table + after + bs.drop(at + 1)
                                else bs.take(at + 1) + table + after + bs.drop(at + 1)
                            }
                            focusAt(after.id, 0)
                        },
                        onTemplate = { t ->
                            applySuggestion(b, s.second, "")
                            val copies = instantiateTemplate(t, page.title)
                            if (copies.isNotEmpty()) {
                                edit { bs ->
                                    val at = bs.indexOfFirst { it.id == b.id }
                                    val keep = bs[at].text.isNotBlank()
                                    bs.take(if (keep) at + 1 else at) + copies + bs.drop(at + 1)
                                }
                                focusAt(copies.last().id, copies.last().text.length)
                            }
                        },
                        onDivider = {
                            applySuggestion(b, s.second, "")
                            val after = Block()
                            edit { bs -> bs.flatMap { if (it.id == b.id) listOf(it, Block(kind = "divider"), after) else listOf(it) } }
                            focusAt(after.id, 0)
                        },
                    )
                }
            }
            item(key = "tail") {
                // Tap below the last block to keep writing.
                Box(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth().height(120.dp).clickable(indication = null, interactionSource = null) {
                        val last = blocks.lastOrNull()
                        if (last != null && last.kind == "paragraph" && last.text.isEmpty()) focusAt(last.id, 0)
                        else {
                            val nb = Block()
                            edit { it + nb }
                            focusAt(nb.id, 0)
                        }
                    },
                )
            }
            item(key = "backlinks") { Backlinks(page, index, ws.pages.value, nav) }
        }

        // Block toolbar docked on the keyboard while editing, like the web's block menu.
        val current = blocks.firstOrNull { it.id == focused }
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding(),
        ) {
            current?.let { b ->
                BlockToolbar(
                    b,
                    onTurn = { k ->
                        replace(b.copy(kind = k.kind, level = k.level, checked = if (k.kind == "todo") b.checked ?: false else null))
                        focusAt(b.id, (fields[b.id]?.selection?.end ?: 1) - 1)
                    },
                    onLink = {
                        val f = fields[b.id] ?: return@BlockToolbar
                        val caret = f.selection.end
                        val t = f.text.substring(0, caret) + "[[]]" + f.text.substring(caret)
                        onChange(b, TextFieldValue(t, TextRange(caret + 2)))
                    },
                    onImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onMove = { d ->
                        edit { bs ->
                            val at = bs.indexOfFirst { it.id == b.id }
                            val to = (at + d).coerceIn(0, bs.size - 1)
                            bs.toMutableList().apply { add(to, removeAt(at)) }
                        }
                        focusAt(b.id, (fields[b.id]?.selection?.end ?: 1) - 1)
                    },
                    onIndent = { d -> indentBy(b, d); focusAt(b.id, (fields[b.id]?.selection?.end ?: 1) - 1) },
                    onComment = { commentFor = b.id },
                    onDelete = {
                        val i = blocks.indexOfFirst { it.id == b.id }
                        edit { bs -> bs.filterNot { it.id == b.id } }
                        blocks.getOrNull(i - 1)?.let { focusAt(it.id, it.text.length) }
                    },
                    onDone = { focusManager.clearFocus() },
                )
            }
        }
        commentFor?.let { id ->
            val me = app.session.state.value.user
            CommentSheet(
                page.comments, id, meId = me?.id ?: "local", meName = me?.name?.ifBlank { null } ?: me?.email ?: "You", readOnly = false,
                onChange = { cs -> ws.updatePage(page.id) { it.copy(comments = cs) } },
                onDismiss = { commentFor = null },
            )
        }
    }
}

@Composable
private fun BlockRow(
    b: Block,
    number: Int,
    field: TextFieldValue,
    folded: Boolean,
    hasKids: Boolean,
    onFold: () -> Unit,
    comments: Int,
    onComments: () -> Unit,
    focused: Boolean,
    inline: InlineColors,
    requester: FocusRequester,
    pageId: String,
    app: Vellune,
    onFocus: (Boolean) -> Unit,
    onChange: (TextFieldValue) -> Unit,
    onCheck: (Boolean) -> Unit,
    onTapText: (Int) -> Unit,
    onLink: (String) -> Unit,
    onUrl: (String) -> Unit,
    onTag: (String) -> Unit,
    onDeleteBlock: () -> Unit,
    onText: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    if (b.kind == "divider") {
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = colors.outlineVariant)
        return
    }
    if (b.kind == "image") {
        BlobImage(app, pageId, b, onDeleteBlock)
        return
    }
    if (b.kind == "table") {
        TableBlock(b.text, onText)
        return
    }
    if (b.kind == "file") {
        FileBlock(app, pageId, b, onDeleteBlock)
        return
    }
    val style: TextStyle = when (b.kind) {
        "heading" -> when (b.level ?: 1) {
            1 -> type.headlineLarge
            2 -> type.headlineMedium
            3 -> type.headlineSmall
            else -> type.titleLarge
        }
        "code" -> type.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)
        "quote" -> type.bodyLarge.copy(lineHeight = 26.sp, color = colors.onSurfaceVariant)
        else -> type.bodyLarge.copy(lineHeight = 26.sp)
    }.let { s ->
        val c = if (b.kind == "quote") colors.onSurfaceVariant else colors.onSurface
        if (b.kind == "todo" && b.checked == true) s.copy(color = colors.outline, textDecoration = TextDecoration.LineThrough) else s.copy(color = c)
    }
    val topPad = when {
        b.kind == "heading" && (b.level ?: 1) <= 2 -> 18.dp
        b.kind == "heading" -> 10.dp
        else -> 2.dp
    }
    val container = when (b.kind) {
        "code" -> Modifier.background(colors.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(14.dp)
        "callout" -> Modifier.background(colors.secondaryContainer, MaterialTheme.shapes.medium).padding(14.dp)
        else -> Modifier
    }
    Row(Modifier.fillMaxWidth().padding(top = topPad, bottom = 2.dp).then(container), verticalAlignment = Alignment.Top) {
        when (b.kind) {
            "todo" -> Checkbox(checked = b.checked == true, onCheckedChange = onCheck, modifier = Modifier.size(32.dp).padding(end = 4.dp))
            "bullet" -> Text(listOf("•", "◦", "▪")[b.depth % 3], style = style, modifier = Modifier.width(24.dp))
            "toggle" -> Icon(
                Icons.Outlined.ChevronRight, if (folded) "Expand" else "Collapse",
                Modifier.padding(top = 2.dp, end = 4.dp).size(22.dp).clip(CircleShape).clickable(onClick = onFold).rotate(if (folded) 0f else 90f),
                tint = if (hasKids) colors.onSurface else colors.outline,
            )
            "numbered" -> Text("$number.", style = style, modifier = Modifier.width(28.dp))
            "quote" -> Box(Modifier.padding(end = 12.dp).width(3.dp).height(26.dp).background(colors.primary, CircleShape))
            "callout" -> Text("💡", style = style, modifier = Modifier.padding(end = 10.dp))
        }
        val text = field.text.drop(1)
        if (focused || text.isEmpty()) {
            BasicTextField(
                value = field,
                onValueChange = onChange,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                visualTransformation = if (b.kind == "code") androidx.compose.ui.text.input.VisualTransformation.None else InlineTransformation(inline),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged { onFocus(it.isFocused) },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty() && focused) Text(
                            when (b.kind) {
                                "heading" -> "Heading ${b.level ?: 1}"
                                "todo" -> "To-do"
                                "code" -> "Code"
                                else -> "Type '/' for commands"
                            },
                            style = style.copy(color = colors.outline, textDecoration = null),
                        )
                        inner()
                    }
                },
            )
        } else {
            // Rendered: links, tags and URLs are tappable; a tap elsewhere edits at that spot.
            val annotated = remember(text, inline, b.kind) { if (b.kind == "code") androidx.compose.ui.text.AnnotatedString(text) else styleInline(text, inline) }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                annotated, style = style, onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).pointerInput(annotated) {
                    detectTapGestures { pos ->
                        val o = layout?.getOffsetForPosition(pos) ?: text.length
                        val a = annotated.getStringAnnotations(o, o).firstOrNull()
                        when (a?.tag) {
                            LINK_TAG -> onLink(a.item)
                            URL_TAG -> onUrl(a.item)
                            HASHTAG -> onTag(a.item)
                            else -> onTapText(o)
                        }
                    }
                },
            )
        }
        if (comments > 0) Text(
            "💬 $comments", style = MaterialTheme.typography.labelMedium, color = colors.primary,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp).clip(CircleShape).clickable(onClick = onComments).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SuggestMenu(
    s: Suggest,
    pages: Map<String, Page>,
    index: LinkIndex,
    onDismiss: () -> Unit,
    onPick: (String, Kind?) -> Unit,
    onImage: () -> Unit,
    onDivider: () -> Unit,
    onTemplate: (Page) -> Unit,
    onTable: () -> Unit,
    onFile: () -> Unit,
) {
    val q = s.query.trim()
    DropdownMenu(
        expanded = true, onDismissRequest = onDismiss, shape = MaterialTheme.shapes.large,
        properties = PopupProperties(focusable = false),
        modifier = Modifier.heightIn(max = 320.dp),
    ) {
        when (s) {
            is Suggest.Slash -> {
                val kinds = TURN_INTO.filter { q.isEmpty() || it.label.contains(q, true) || it.keywords.contains(q, true) }
                kinds.forEach { k ->
                    DropdownMenuItem({ Text(k.label) }, { onPick("", k) }, trailingIcon = { Text(k.hint, color = MaterialTheme.colorScheme.outline) })
                }
                if (q.isEmpty() || "image picture photo".contains(q, true)) DropdownMenuItem({ Text("Image") }, onImage, leadingIcon = { Icon(Icons.Outlined.Image, null) })
                if (q.isEmpty() || "file attachment pdf document attach".contains(q, true)) DropdownMenuItem({ Text("File") }, onFile, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) })
                if (q.isEmpty() || "table grid spreadsheet rows columns".contains(q, true)) DropdownMenuItem({ Text("Table") }, onTable, trailingIcon = { Text("|", color = MaterialTheme.colorScheme.outline) })
                if (q.isEmpty() || "divider rule line".contains(q, true)) DropdownMenuItem({ Text("Divider") }, onDivider, trailingIcon = { Text("---", color = MaterialTheme.colorScheme.outline) })
                if (q.isEmpty() || "today date journal".contains(q, true)) DropdownMenuItem({ Text("Today") }, { onPick("[[${todayKey()}]]", null) })
                pages.values.filter { it.isTemplate && (q.isEmpty() || it.title.contains(q, true) || "template".contains(q, true)) }
                    .sortedBy { it.title.lowercase() }
                    .forEach { t ->
                        DropdownMenuItem({ Text(t.displayTitle) }, { onTemplate(t) },
                            leadingIcon = { Text(t.icon ?: "📄") }, trailingIcon = { Text("Template", color = MaterialTheme.colorScheme.outline) })
                    }
            }
            is Suggest.Link -> {
                val hits = pages.values.filter { it.deletedAt == null && !it.isFolder && (q.isEmpty() || it.title.contains(q, true) || it.aliases.any { a -> a.contains(q, true) }) }
                    .sortedByDescending { it.updatedAt }.take(8)
                hits.forEach { p -> DropdownMenuItem({ Text(p.displayTitle) }, { onPick("[[${p.title}]]", null) }) }
                if (q.isNotEmpty() && index.resolve(q) == null) DropdownMenuItem({ Text("New page “$q”") }, { onPick("[[$q]]", null) }, leadingIcon = { Icon(Icons.Outlined.Add, null) })
            }
            is Suggest.Mention -> {
                val dates = dateMentions(q)
                dates.forEach { (label, key) ->
                    DropdownMenuItem({ Text(label) }, { onPick("[[$key]]", null) }, leadingIcon = { Icon(Icons.Outlined.CalendarToday, null) },
                        trailingIcon = { Text(key, color = MaterialTheme.colorScheme.outline) })
                }
                val hits = pages.values.filter { it.deletedAt == null && !it.isFolder && it.title.isNotBlank() && it.title.contains(q, true) }
                    .sortedByDescending { it.updatedAt }.take(6)
                hits.forEach { p -> DropdownMenuItem({ Text(p.displayTitle) }, { onPick("[[${p.title}]]", null) }, leadingIcon = { Text(p.icon ?: "📄") }) }
                if (dates.isEmpty() && hits.isEmpty()) onDismiss()
            }
            is Suggest.Tag -> {
                val tags = index.tags.keys.filter { q.isEmpty() || it.startsWith(q, true) }.sorted().take(8)
                tags.forEach { t -> DropdownMenuItem({ Text("#$t") }, { onPick("#$t ", null) }) }
                if (tags.isEmpty()) onDismiss()
            }
        }
    }
}

@Composable
private fun BlockToolbar(
    b: Block,
    onTurn: (Kind) -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onMove: (Int) -> Unit,
    onIndent: (Int) -> Unit,
    onComment: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var turnMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 4.dp,
        shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { turnMenu = true }) { Icon(Icons.Outlined.Title, "Turn into") }
                DropdownMenu(turnMenu, { turnMenu = false }, shape = MaterialTheme.shapes.large, properties = PopupProperties(focusable = false)) {
                    TURN_INTO.forEach { k ->
                        DropdownMenuItem({ Text(k.label) }, { turnMenu = false; onTurn(k) },
                            trailingIcon = { if (b.kind == k.kind && (k.level == null || k.level == b.level)) Icon(Icons.Outlined.CheckBox, null) })
                    }
                }
            }
            IconButton(onClick = { onTurn(TURN_INTO[5]) }) { Icon(Icons.Outlined.CheckBox, "To-do") }
            IconButton(onClick = { onTurn(TURN_INTO[6]) }) { Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list") }
            IconButton(onClick = { onTurn(TURN_INTO[7]) }) { Icon(Icons.Outlined.FormatListNumbered, "Numbered list") }
            IconButton(onClick = { onTurn(TURN_INTO[8]) }) { Icon(Icons.Outlined.FormatQuote, "Quote") }
            IconButton(onClick = { onTurn(TURN_INTO[9]) }) { Icon(Icons.Outlined.Lightbulb, "Callout") }
            IconButton(onClick = { onTurn(TURN_INTO[10]) }) { Icon(Icons.Outlined.Code, "Code block") }
            IconButton(onClick = onLink) { Icon(Icons.Outlined.Link, "Link to page") }
            IconButton(onClick = onComment) { Icon(Icons.AutoMirrored.Outlined.Comment, "Comment") }
            IconButton(onClick = onImage) { Icon(Icons.Outlined.Image, "Image") }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            IconButton(onClick = { onIndent(-1) }, enabled = b.depth > 0) { Icon(Icons.AutoMirrored.Outlined.FormatIndentDecrease, "Outdent") }
            IconButton(onClick = { onIndent(1) }) { Icon(Icons.AutoMirrored.Outlined.FormatIndentIncrease, "Indent") }
            IconButton(onClick = { onMove(-1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
            IconButton(onClick = { onMove(1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete block") }
            IconButton(onClick = onDone) { Icon(Icons.Outlined.KeyboardHide, "Done") }
        }
    }
}

/** The web's Info panel: tags, dates, backlinks count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoSection(app: Vellune, nav: AppState, page: Page, index: LinkIndex) {
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "info")
    val ws = app.workspace
    val backlinks = index.backlinks[page.id].orEmpty()
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Info", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            if (!open && page.tags.isNotEmpty()) Text(
                page.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 12.dp), maxLines = 1,
            )
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Prop("Tags") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        page.tags.forEach { t ->
                            InputChip(
                                selected = false, onClick = { nav.navigate(Dest.Database(t)) }, label = { Text("#$t") },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, "Remove tag $t", Modifier.size(InputChipDefaults.IconSize).clickable {
                                        ws.updatePage(page.id) { it.copy(tags = it.tags - t) }
                                    })
                                },
                            )
                        }
                        if (adding) {
                            BasicTextField(
                                newTag, { newTag = it.replace(" ", "-").removePrefix("#") }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    val t = newTag.trim()
                                    if (t.isNotEmpty() && t !in page.tags) ws.updatePage(page.id) { it.copy(tags = it.tags + t) }
                                    newTag = ""; adding = false
                                }),
                                modifier = Modifier.widthIn(min = 80.dp).padding(8.dp),
                            )
                        } else {
                            Text("+ Add tag", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { adding = true }.padding(8.dp))
                        }
                    }
                }
                PropsEditor(page.props) { p -> ws.updatePage(page.id) { it.copy(props = p) } }
                Prop("Created") { Text(DateUtils.formatDateTime(ctx, page.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), style = MaterialTheme.typography.bodyMedium) }
                Prop("Updated") { Text(DateUtils.formatDateTime(ctx, page.updatedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), style = MaterialTheme.typography.bodyMedium) }
                val words = remember(page.blocks) { page.blocks.sumOf { b -> b.text.split(Regex("\\s+")).count { it.isNotBlank() } } }
                Prop("Backlinks") { Text("${backlinks.size} · $words words", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun Prop(label: String, value: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
    value()
}

@Composable
private fun Backlinks(page: Page, index: LinkIndex, pages: Map<String, Page>, nav: AppState) {
    val links = index.backlinks[page.id].orEmpty()
    if (links.isEmpty()) return
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("${links.size} linked mention${if (links.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
        links.forEach { l ->
            val src = pages[l.sourceId] ?: return@forEach
            Surface(
                onClick = { nav.open(src.id) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(src.displayTitle, style = MaterialTheme.typography.titleSmall)
                    if (l.context.isNotBlank()) Text(l.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}
