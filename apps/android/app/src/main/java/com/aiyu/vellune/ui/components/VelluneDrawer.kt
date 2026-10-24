package com.aiyu.vellune.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavKey
import com.aiyu.vellune.R
import com.aiyu.vellune.ui.navigation.HomeKey
import com.aiyu.vellune.ui.navigation.SettingsKey
import com.aiyu.vellune.ui.navigation.TracesKey
import com.aiyu.vellune.ui.theme.Dimens

private data class DrawerEntry(val titleRes: Int, val icon: ImageVector, val route: NavKey)

private val mainEntries = listOf(
    DrawerEntry(R.string.nav_home, Icons.Rounded.Home, HomeKey),
    DrawerEntry(R.string.nav_traces, Icons.Rounded.Timeline, TracesKey),
    DrawerEntry(R.string.nav_settings, Icons.Rounded.Settings, SettingsKey),
)

/** Planned features shown with a "Soon" tag so the roadmap is visible (development/TODO.md phases 4–7). */
private val soonEntries = listOf(
    R.string.nav_pages to Icons.AutoMirrored.Rounded.Article,
    R.string.nav_graph to Icons.Rounded.Hub,
    R.string.nav_sync to Icons.Rounded.Sync,
)

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = Dimens.xxxl + Dimens.xs) {
    ShapeIcon(
        icon = Icons.Rounded.Gesture,
        shape = MaterialShapes.Cookie9Sided,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        size = size,
        iconSize = size / 2,
        modifier = modifier,
    )
}

@Composable
fun VelluneDrawerSheet(onNavigate: (NavKey) -> Unit, isCurrent: (NavKey) -> Boolean, modifier: Modifier = Modifier) {
    // Plain column: PushDrawer already supplies the surface behind it.
    Column(modifier.systemBarsPadding().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(Dimens.xl)) {
            BrandMark()
            Spacer(Modifier.width(Dimens.lg))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    stringResource(R.string.drawer_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        mainEntries.forEach { entry ->
            NavigationDrawerItem(
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(stringResource(entry.titleRes)) },
                selected = isCurrent(entry.route),
                onClick = { onNavigate(entry.route) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.sm))
        Text(
            stringResource(R.string.drawer_section_roadmap),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.xxl, vertical = Dimens.sm),
        )
        soonEntries.forEach { (title, icon) ->
            NavigationDrawerItem(
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(title)) },
                badge = { Text(stringResource(R.string.badge_soon), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge) },
                selected = false,
                onClick = {},
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

/** Medium and expanded windows: the same destinations as an always-visible rail. */
@Composable
fun VelluneNavRail(onNavigate: (NavKey) -> Unit, isCurrent: (NavKey) -> Boolean, modifier: Modifier = Modifier) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = { BrandMark(size = Dimens.xxxl, modifier = Modifier.padding(vertical = Dimens.md)) },
        modifier = modifier,
    ) {
        mainEntries.forEach { entry ->
            NavigationRailItem(
                selected = isCurrent(entry.route),
                onClick = { onNavigate(entry.route) },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(stringResource(entry.titleRes), maxLines = 1) },
            )
        }
    }
}
