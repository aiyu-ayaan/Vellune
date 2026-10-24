package com.aiyu.vellune.ui.traces

import android.content.ClipData
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.vellune.R
import com.aiyu.vellune.container
import com.aiyu.vellune.ui.components.SectionHeader
import com.aiyu.vellune.ui.components.SettingItem
import com.aiyu.vellune.ui.components.ShapeIcon
import com.aiyu.vellune.ui.components.groupedShape
import com.aiyu.vellune.ui.components.readableGutter
import com.aiyu.vellune.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Recorded input traces: how to turn recording on, how to pull the files, and the list. */
@Composable
fun TracesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val files by app.traces.files.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pull = "adb pull ${app.traces.dir.absolutePath}"

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.nav_traces)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back)) } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = readableGutter(), end = readableGutter(),
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.xxs),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.md),
                ) {
                    Column(Modifier.padding(Dimens.xl)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.traces_record), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = settings.recordTraces,
                                onCheckedChange = { v -> app.settings.update { it.copy(recordTraces = v) } },
                            )
                        }
                        Spacer(Modifier.height(Dimens.sm))
                        Text(stringResource(R.string.traces_intro), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(Dimens.lg))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.padding(end = Dimens.sm))
                            Text(
                                pull,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("adb", pull))) } }) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                            }
                        }
                    }
                }
            }
            if (files.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xxl)) {
                        ShapeIcon(
                            icon = Icons.Rounded.Timeline,
                            shape = MaterialShapes.SoftBurst,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                            size = 88.dp,
                            iconSize = 36.dp,
                        )
                        Spacer(Modifier.height(Dimens.lg))
                        Text(stringResource(R.string.traces_empty), style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                item { SectionHeader(stringResource(R.string.traces_recorded)) }
                itemsIndexed(files, key = { _, f -> f.file.name }) { index, f ->
                    SettingItem(
                        headlineContent = { Text(f.file.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.traces_meta,
                                    DateUtils.getRelativeTimeSpanString(f.modified).toString(),
                                    Formatter.formatShortFileSize(context, f.sizeBytes),
                                )
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Timeline, null) },
                        trailingContent = {
                            IconButton(onClick = { app.traces.delete(f) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
                        },
                        modifier = Modifier.animateItem().clip(groupedShape(index, files.size)),
                    )
                }
            }
        }
    }
}
