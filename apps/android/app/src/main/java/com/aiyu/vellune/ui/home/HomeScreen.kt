package com.aiyu.vellune.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiyu.vellune.R
import com.aiyu.vellune.container
import com.aiyu.vellune.core.input.InputMode
import com.aiyu.vellune.data.CanvasDoc
import com.aiyu.vellune.ui.components.MenuIcon
import com.aiyu.vellune.ui.components.MorphingShapeIcon
import com.aiyu.vellune.ui.components.SectionHeader
import com.aiyu.vellune.ui.components.ShapeIcon
import com.aiyu.vellune.ui.components.readableGutter
import com.aiyu.vellune.ui.components.rememberStaggerState
import com.aiyu.vellune.ui.components.staggeredEnter
import com.aiyu.vellune.ui.theme.Dimens

@Composable
fun HomeScreen(
    onOpenDrawer: (() -> Unit)?,
    drawerProgress: () -> Float,
    onOpenCanvas: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel { HomeViewModel(context.container.canvases, context.container.settings) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val stagger = rememberStaggerState()
    var actionsFor by remember { mutableStateOf<CanvasDoc?>(null) }
    var renaming by remember { mutableStateOf<CanvasDoc?>(null) }
    var deleting by remember { mutableStateOf<CanvasDoc?>(null) }
    val newTitle = stringResource(R.string.canvas_default_title)
    val createAndOpen = { onOpenCanvas(viewModel.create(newTitle)) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = { Text(stringResource(R.string.home_subtitle)) },
                navigationIcon = {
                    if (onOpenDrawer != null) IconButton(onClick = onOpenDrawer) {
                        val cd = stringResource(R.string.cd_open_drawer)
                        MenuIcon(drawerProgress, Modifier.semantics { contentDescription = cd })
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = createAndOpen,
                icon = { Icon(Icons.Rounded.Draw, contentDescription = null) },
                text = { Text(stringResource(R.string.action_new_canvas)) },
            )
        },
    ) { padding ->
        val gutter = readableGutter()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(
                start = gutter, end = gutter,
                top = padding.calculateTopPadding() + Dimens.sm,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.md),
            verticalArrangement = Arrangement.spacedBy(Dimens.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                PenLabCard(ui, onStart = createAndOpen, modifier = Modifier.staggeredEnter(stagger, "hero", 0))
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                val count = ui.canvases?.size ?: 0
                SectionHeader(
                    title = stringResource(R.string.home_section_canvases),
                    action = {
                        if (count > 0) Text(
                            pluralStringResource(R.plurals.canvas_count, count, count),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Dimens.xs),
                        )
                    },
                )
            }
            val docs = ui.canvases
            when {
                docs == null -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(Dimens.xxl), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                docs.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyCanvases(onCreate = createAndOpen)
                }
                else -> itemsIndexed(docs, key = { _, d -> d.id }) { index, doc ->
                    CanvasCard(
                        doc = doc,
                        onOpen = { onOpenCanvas(doc.id) },
                        onLongPress = { actionsFor = doc },
                        modifier = Modifier.animateItem().staggeredEnter(stagger, doc.id, index + 2),
                    )
                }
            }
        }
    }

    actionsFor?.let { doc ->
        CanvasActionsSheet(
            doc = doc,
            onDismiss = { actionsFor = null },
            onOpen = { actionsFor = null; onOpenCanvas(doc.id) },
            onRename = { actionsFor = null; renaming = doc },
            onDelete = { actionsFor = null; deleting = doc },
        )
    }
    renaming?.let { doc ->
        RenameDialog(initial = doc.title, onDismiss = { renaming = null }, onConfirm = { viewModel.rename(doc.id, it); renaming = null })
    }
    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_canvas_title)) },
            text = { Text(stringResource(R.string.delete_canvas_message, doc.title)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(doc.id); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Hero card: what the input system detected, and the one primary action. */
@Composable
private fun PenLabCard(ui: HomeUi, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onStart,
        shape = MaterialTheme.shapes.extraLargeIncreased,
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            // Decorative blob in the corner, as on the storage hero card in Findex.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp, y = (-48).dp)
                    .size(220.dp)
                    .graphicsLayer { rotationZ = 18f }
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(scheme.onPrimaryContainer.copy(alpha = 0.08f))
            )
            Column(Modifier.padding(Dimens.xl)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ui.stylus) Icons.Rounded.Gesture else Icons.Rounded.TouchApp, contentDescription = null)
                    Spacer(Modifier.width(Dimens.md))
                    Text(stringResource(R.string.home_hero_label), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(Dimens.lg))
                Text(
                    stringResource(if (ui.stylus) R.string.home_hero_pen_ready else R.string.home_hero_touch_mode),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Spacer(Modifier.height(Dimens.sm))
                Text(
                    stringResource(
                        R.string.home_hero_summary,
                        stringResource(ui.mode.labelRes),
                        stringResource(if (ui.prediction) R.string.state_on else R.string.state_off),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(Dimens.lg))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_hero_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = onStart,
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.action_new_canvas))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CanvasCard(doc: CanvasDoc, onOpen: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .clip(MaterialTheme.shapes.largeIncreased)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
                onLongClickLabel = stringResource(R.string.cd_canvas_actions),
            ),
    ) {
        Column {
            CanvasThumbnail(
                doc = doc,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .padding(Dimens.sm)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            Column(Modifier.padding(start = Dimens.lg, end = Dimens.lg, bottom = Dimens.lg, top = Dimens.xs)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        R.string.canvas_meta,
                        DateUtils.getRelativeTimeSpanString(doc.updatedAt).toString(),
                        pluralStringResource(R.plurals.stroke_count, doc.strokes.size, doc.strokes.size),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyCanvases(onCreate: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xxl),
    ) {
        MorphingShapeIcon(
            icon = Icons.Rounded.Draw,
            from = MaterialShapes.Cookie9Sided,
            to = MaterialShapes.Clover4Leaf,
            morphed = pressed,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 96.dp,
            iconSize = 40.dp,
            modifier = Modifier.combinedClickable(onClick = { pressed = !pressed; onCreate() }),
        )
        Spacer(Modifier.height(Dimens.lg))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Dimens.xs))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CanvasActionsSheet(doc: CanvasDoc, onDismiss: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.sm)) {
            ShapeIcon(
                icon = Icons.Rounded.Gesture,
                shape = MaterialShapes.Cookie9Sided,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(Dimens.lg))
            Text(doc.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val transparent = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f))
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_open)) },
            leadingContent = { Icon(Icons.Rounded.Edit, null) },
            colors = transparent,
            modifier = Modifier.combinedClickable(onClick = onOpen),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_rename)) },
            leadingContent = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
            colors = transparent,
            modifier = Modifier.combinedClickable(onClick = onRename),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
            leadingContent = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            colors = transparent,
            modifier = Modifier.combinedClickable(onClick = onDelete),
        )
        Spacer(Modifier.height(Dimens.xl))
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.rename_canvas_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.rename_canvas_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

val InputMode.labelRes: Int
    get() = when (this) {
        InputMode.PenOnly -> R.string.mode_pen_only
        InputMode.PenAndFinger -> R.string.mode_pen_and_finger
        InputMode.FingerOnly -> R.string.mode_finger_only
    }
