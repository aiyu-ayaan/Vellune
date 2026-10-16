package app.vellune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.layout.fillMaxSize
import app.vellune.ui.home.HomeScreen
import app.vellune.ui.note.NoteScreen
import app.vellune.ui.theme.VelluneTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(vm.prefs.getBoolean("dynamic_color", false)) }
            VelluneTheme(dark = isSystemInDarkTheme(), dynamicColor = dynamic) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    VelluneApp(vm, dynamic) {
                        dynamic = it
                        vm.prefs.edit().putBoolean("dynamic_color", it).apply()
                    }
                }
            }
        }
    }
}

/** Where the app is. `origin` is the shared-element key the editor grows out of (a card or the FAB). */
sealed interface Screen {
    data object Home : Screen
    data class Editor(val id: String, val origin: String) : Screen
}

@Composable
fun VelluneApp(vm: AppViewModel, dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var openOrigin by rememberSaveable { mutableStateOf("") }
    val start: Screen = openId?.let { Screen.Editor(it, openOrigin) } ?: Screen.Home
    val state = remember { SeekableTransitionState(start) }
    val transition = rememberTransition(state, label = "screen")
    val scope = rememberCoroutineScope()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.geometry.Rect>()

    fun go(to: Screen) {
        (state.currentState as? Screen.Editor)?.let { if (to == Screen.Home) vm.close(it.id) }
        openId = (to as? Screen.Editor)?.id
        openOrigin = (to as? Screen.Editor)?.origin.orEmpty()
        scope.launch { state.animateTo(to) }
    }

    // Predictive back: the editor shrinks back into its card as the gesture moves, and commits or springs back.
    PredictiveBackHandler(enabled = openId != null) { progress ->
        val from = state.currentState
        try {
            progress.collect { e -> state.seekTo(e.progress * 0.9f, Screen.Home) }
            go(Screen.Home)
        } catch (e: CancellationException) {
            state.animateTo(from)
            throw e
        }
    }

    SharedTransitionLayout {
        transition.AnimatedContent(
            transitionSpec = {
                if (targetState is Screen.Editor) {
                    fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120))
                } else {
                    fadeIn(tween(250)) togetherWith
                        (fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, transformOrigin = TransformOrigin.Center))
                }
            },
            contentKey = { if (it is Screen.Editor) it.id else "home" },
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(
                    vm = vm,
                    sharedScope = this@SharedTransitionLayout,
                    visibility = this,
                    boundsSpec = spatial,
                    dynamicColor = dynamicColor,
                    onDynamicColor = onDynamicColor,
                    onOpen = { id, origin -> go(Screen.Editor(id, origin)) },
                )
                is Screen.Editor -> NoteScreen(
                    vm = vm,
                    id = screen.id,
                    sharedKey = screen.origin,
                    sharedScope = this@SharedTransitionLayout,
                    visibility = this,
                    boundsSpec = spatial,
                    onBack = { go(Screen.Home) },
                )
            }
        }
    }
}
