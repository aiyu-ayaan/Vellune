package app.vellune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vellune.core.Status
import app.vellune.ui.AppState
import app.vellune.ui.auth.KeyScreen
import app.vellune.ui.auth.LoginScreen
import app.vellune.ui.shell.Shell
import app.vellune.ui.theme.VelluneTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as Vellune

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(app.db.kv("dynamic_color") == "1") }
            VelluneTheme(dark = isSystemInDarkTheme(), dynamicColor = dynamic) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Root(app, dynamic) {
                        dynamic = it
                        app.db.setKv("dynamic_color", if (it) "1" else null)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Going to the background: write every pending doc save and outbox entry now.
        app.sync.flush()
    }
}

@Composable
private fun Root(app: Vellune, dynamicColor: Boolean, onDynamicColor: (Boolean) -> Unit) {
    val session by app.session.state.collectAsStateWithLifecycle()
    val loaded by app.workspace.loaded.collectAsStateWithLifecycle()
    val nav = remember { AppState(app.workspace, app.db) }

    LaunchedEffect(Unit) {
        app.workspace.load()
        app.session.init()
    }
    // Sync runs only while signed in and unlocked; editing never waits for it.
    LaunchedEffect(loaded, session.status, session.identity) {
        val id = session.identity
        if (loaded && session.status == Status.Ready && id != null) app.sync.start(id) else app.sync.stop()
    }

    val gate = when {
        !loaded || session.status == Status.Loading -> "loading"
        session.status == Status.SignedOut -> "login"
        session.status == Status.NeedsKeys -> "setup"
        session.status == Status.Locked -> "unlock"
        else -> "app"
    }
    AnimatedContent(gate, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "gate") { g ->
        when (g) {
            "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            "login" -> LoginScreen(app.session, app.api.server, session.error)
            "setup", "unlock" -> KeyScreen(app.session, setup = g == "setup", user = session.user, error = session.error)
            else -> Shell(app, nav, dynamicColor, onDynamicColor)
        }
    }
}
