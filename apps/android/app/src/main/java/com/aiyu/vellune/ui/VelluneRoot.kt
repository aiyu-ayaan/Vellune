package com.aiyu.vellune.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.aiyu.vellune.ui.canvas.CanvasScreen
import com.aiyu.vellune.ui.components.PushDrawer
import com.aiyu.vellune.ui.components.VelluneDrawerSheet
import com.aiyu.vellune.ui.components.VelluneNavRail
import com.aiyu.vellune.ui.components.rememberPushDrawerState
import com.aiyu.vellune.ui.components.windowWidth
import com.aiyu.vellune.ui.home.HomeScreen
import com.aiyu.vellune.ui.navigation.CanvasKey
import com.aiyu.vellune.ui.navigation.HomeKey
import com.aiyu.vellune.ui.navigation.SettingsKey
import com.aiyu.vellune.ui.navigation.TracesKey
import com.aiyu.vellune.ui.settings.SettingsScreen
import com.aiyu.vellune.ui.traces.TracesScreen
import kotlinx.coroutines.launch

/** App shell: push drawer (compact) or rail (medium+), and the Navigation 3 back stack. */
@Composable
fun VelluneRoot() {
    val backStack = rememberNavBackStack(HomeKey)
    val drawer = rememberPushDrawerState()
    val scope = rememberCoroutineScope()
    val top = backStack.lastOrNull()
    val useRail = windowWidth() >= 600.dp

    val navigateTop: (NavKey) -> Unit = { key ->
        scope.launch { drawer.close() }
        if (key == HomeKey) {
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        } else if (top != key) {
            backStack.add(key)
        }
    }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    PushDrawer(
        state = drawer,
        drawerContent = { VelluneDrawerSheet(onNavigate = navigateTop, isCurrent = { it == top }) },
        gesturesEnabled = top == HomeKey,
        // The canvas is full-bleed; the rail only frames the list screens.
        rail = if (useRail && top !is CanvasKey) {
            { VelluneNavRail(onNavigate = navigateTop, isCurrent = { it == top }) }
        } else null,
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = back,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        onOpenDrawer = if (useRail) null else ({ scope.launch { drawer.open() } }),
                        drawerProgress = { drawer.fraction },
                        onOpenCanvas = { backStack.add(CanvasKey(it)) },
                        onOpenSettings = { backStack.add(SettingsKey) },
                    )
                }
                entry<CanvasKey> { key -> CanvasScreen(canvasId = key.id, onBack = back, onOpenSettings = { backStack.add(SettingsKey) }) }
                entry<TracesKey> { TracesScreen(onBack = back) }
                entry<SettingsKey> { SettingsScreen(onBack = back) }
            },
        )
    }
}
