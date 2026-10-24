package com.aiyu.vellune.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FiberSmartRecord
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.vellune.BuildConfig
import com.aiyu.vellune.R
import com.aiyu.vellune.container
import com.aiyu.vellune.core.input.InputMode
import com.aiyu.vellune.data.AppSettings
import com.aiyu.vellune.data.ThemeMode
import com.aiyu.vellune.ui.components.SectionHeader
import com.aiyu.vellune.ui.components.SettingItem
import com.aiyu.vellune.ui.components.groupedShape
import com.aiyu.vellune.ui.components.readableGutter
import com.aiyu.vellune.ui.theme.Dimens
import kotlin.math.roundToInt

/** Settings rows start their text after a 24dp icon plus the 16dp gaps of [SettingItem]. */
private val RowTextInset = 56.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repo = LocalContext.current.container.settings
    val settings by repo.settings.collectAsStateWithLifecycle()
    val update: ((AppSettings) -> AppSettings) -> Unit = repo::update
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.nav_settings)) },
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
        ) {
            item { SectionHeader(stringResource(R.string.settings_pen_touch)) }
            item {
                ChoiceCard(
                    title = stringResource(R.string.settings_input_mode),
                    description = stringResource(
                        when (settings.inputMode) {
                            InputMode.PenOnly -> R.string.settings_mode_pen_only_desc
                            InputMode.PenAndFinger -> R.string.settings_mode_pen_and_finger_desc
                            InputMode.FingerOnly -> R.string.settings_mode_finger_only_desc
                        }
                    ),
                    options = listOf(
                        Triple(InputMode.PenOnly, R.string.mode_short_pen, Icons.Rounded.Gesture),
                        Triple(InputMode.PenAndFinger, R.string.mode_short_both, Icons.Rounded.BackHand),
                        Triple(InputMode.FingerOnly, R.string.mode_short_finger, Icons.Rounded.TouchApp),
                    ),
                    selected = settings.inputMode,
                    onSelect = { m -> update { it.copy(inputMode = m) } },
                    shape = groupedShape(0, 4),
                )
            }
            item {
                SliderRow(
                    icon = Icons.Rounded.BackHand,
                    title = stringResource(R.string.settings_palm_size),
                    description = stringResource(R.string.settings_palm_size_desc, settings.palmSizeMm.roundToInt()),
                    value = settings.palmSizeMm,
                    range = 8f..40f,
                    onChange = { v -> update { it.copy(palmSizeMm = v) } },
                    shape = groupedShape(1, 4),
                )
            }
            item {
                SwitchRow(
                    icon = Icons.Rounded.Timer,
                    title = stringResource(R.string.settings_lockout),
                    description = stringResource(R.string.settings_lockout_desc, settings.lockoutMs),
                    checked = settings.lockoutEnabled,
                    onChange = { v -> update { it.copy(lockoutEnabled = v) } },
                    shape = groupedShape(2, 4),
                ) {
                    if (settings.lockoutEnabled) Slider(
                        value = settings.lockoutMs.toFloat(),
                        onValueChange = { v -> update { it.copy(lockoutMs = (v / 50).roundToInt() * 50) } },
                        valueRange = 100f..1000f,
                    )
                }
            }
            item {
                SwitchRow(
                    icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    title = stringResource(R.string.settings_prediction),
                    description = stringResource(R.string.settings_prediction_desc),
                    checked = settings.prediction,
                    onChange = { v -> update { it.copy(prediction = v) } },
                    shape = groupedShape(3, 4),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_developer)) }
            item {
                SwitchRow(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.settings_hud),
                    description = stringResource(R.string.settings_hud_desc),
                    checked = settings.showHud,
                    onChange = { v -> update { it.copy(showHud = v) } },
                    shape = groupedShape(0, 2),
                )
            }
            item {
                SwitchRow(
                    icon = Icons.Rounded.FiberSmartRecord,
                    title = stringResource(R.string.settings_record_traces),
                    description = stringResource(R.string.settings_record_traces_desc),
                    checked = settings.recordTraces,
                    onChange = { v -> update { it.copy(recordTraces = v) } },
                    shape = groupedShape(1, 2),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                ChoiceCard(
                    title = stringResource(R.string.settings_theme),
                    description = null,
                    options = listOf(
                        Triple(ThemeMode.System, R.string.settings_theme_system, Icons.Rounded.Contrast),
                        Triple(ThemeMode.Light, R.string.settings_theme_light, Icons.Rounded.LightMode),
                        Triple(ThemeMode.Dark, R.string.settings_theme_dark, Icons.Rounded.DarkMode),
                    ),
                    selected = settings.themeMode,
                    onSelect = { m -> update { it.copy(themeMode = m) } },
                    shape = groupedShape(0, if (dynamicSupported) 2 else 1),
                )
            }
            if (dynamicSupported) item {
                SwitchRow(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_dynamic_color),
                    description = stringResource(R.string.settings_dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onChange = { v -> update { it.copy(dynamicColor = v) } },
                    shape = groupedShape(1, 2),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    modifier = Modifier.clip(groupedShape(0, 1)),
                )
            }
        }
    }
}

/** Connected button group: one choice, shapes morph on selection. */
@Composable
private fun <T> ChoiceCard(
    title: String,
    description: String?,
    options: List<Triple<T, Int, ImageVector>>,
    selected: T,
    onSelect: (T) -> Unit,
    shape: Shape,
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(Dimens.lg)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Dimens.md))
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { index, (value, label, icon) ->
                    ToggleButton(
                        checked = selected == value,
                        onCheckedChange = { onSelect(value) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = Dimens.xs).height(ToggleButtonDefaults.IconSize))
                        Text(stringResource(label), maxLines = 1)
                    }
                }
            }
            if (description != null) {
                Spacer(Modifier.height(Dimens.md))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    shape: Shape,
    extra: @Composable () -> Unit = {},
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            SettingItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(description) },
                leadingContent = { Icon(icon, null) },
                trailingContent = { Switch(checked = checked, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
            )
            Column(Modifier.padding(start = RowTextInset, end = Dimens.lg)) { extra() }
        }
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    shape: Shape,
) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            SettingItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(description) },
                leadingContent = { Icon(icon, null) },
            )
            Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.padding(start = RowTextInset, end = Dimens.lg))
        }
    }
}
