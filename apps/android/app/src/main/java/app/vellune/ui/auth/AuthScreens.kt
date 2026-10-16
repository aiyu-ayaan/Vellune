package app.vellune.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vellune.core.Session
import app.vellune.core.User
import kotlinx.coroutines.launch
import java.net.URI

/** Vellune's mark: a crescent moon (lune) cut from a disc, in the primary tone. */
@Composable
fun Crescent(modifier: Modifier, color: Color = MaterialTheme.colorScheme.primary) = Canvas(modifier) {
    val r = size.minDimension / 2f
    val disc = Path().apply { addOval(Rect(center, r)) }
    val bite = Path().apply { addOval(Rect(Offset(center.x + r * 0.42f, center.y - r * 0.28f), r * 0.86f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), color)
}

@Composable
private fun Brand(large: Boolean) = Column(horizontalAlignment = if (large) Alignment.Start else Alignment.CenterHorizontally) {
    Crescent(Modifier.size(if (large) 96.dp else 64.dp))
    Spacer(Modifier.height(if (large) 28.dp else 16.dp))
    Text("Vellune", style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text("Where thoughts take form.", style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (large) {
        Spacer(Modifier.height(40.dp))
        listOf(
            Icons.Outlined.Lock to "End-to-end encrypted before it leaves this device",
            Icons.Outlined.CloudOff to "Works offline, syncs when you're back",
            Icons.Outlined.Draw to "Native ink, docs and canvases in one place",
        ).forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/** Sign-in frame: brand beside the form on wide windows, above it on phones; the form sits on a tonal card. */
@Composable
private fun AuthFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.35f), colors.surface, colors.surface))),
    ) {
        val wide = maxWidth >= 840.dp
        val form = @Composable {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerLow, modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp).animateContentSize()) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    content()
                }
            }
        }
        Row(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), verticalAlignment = Alignment.CenterVertically) {
            if (wide) Box(Modifier.weight(1f).padding(64.dp)) { Brand(large = true) }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!wide) {
                    Brand(large = false)
                    Spacer(Modifier.height(28.dp))
                }
                form()
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, label: String, onDone: () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide password" else "Show password")
            }
        },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(error: String?) = AnimatedVisibility(error != null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(error.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun PrimaryAction(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp),
    ) {
        if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary) else Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** http to a host that isn't on this network: the session cookie would cross the internet in the clear. */
private fun insecure(server: String): Boolean = runCatching {
    val u = URI(server.trim())
    if (u.scheme != "http") return false
    val host = u.host ?: return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan")) return false
    // No DNS lookup here (main thread): only IP literals can be shown to be private.
    val m = Regex("""^(\d+)\.(\d+)\.\d+\.\d+$""").matchEntire(host) ?: return true
    val a = m.groupValues[1].toInt()
    val b = m.groupValues[2].toInt()
    !(a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127))
}.getOrDefault(false)

@Composable
fun LoginScreen(session: Session, savedServer: String, error: String?) {
    val scope = rememberCoroutineScope()
    var signup by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(savedServer) }
    // A remembered server folds into one row; it only needs editing when it changes.
    var editServer by rememberSaveable { mutableStateOf(savedServer.isBlank()) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    val valid = server.isNotBlank() && email.contains('@') && password.length >= (if (signup) 8 else 1) && (!signup || name.isNotBlank())

    fun submit() {
        if (!valid || busy) return
        busy = true
        scope.launch {
            if (signup) session.signup(server, email, name, password) else session.login(server, email, password)
            busy = false
        }
    }

    AuthFrame(
        title = if (signup) "Create your account" else "Welcome back",
        subtitle = "Your notes are end-to-end encrypted before they leave this device.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
            ToggleButton(!signup, { signup = false }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f)) { Text("Sign in") }
            ToggleButton(signup, { signup = true }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f)) { Text("Create account") }
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editServer) Surface(
                onClick = { editServer = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("Server", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(server.removePrefix("https://"), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else OutlinedTextField(
                value = server, onValueChange = { server = it }, singleLine = true,
                label = { Text("Server") }, placeholder = { Text("https://notes.example.com") },
                supportingText = { Text("The address you open Vellune at in a browser") },
                leadingIcon = { Icon(Icons.Outlined.Dns, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
            )
            if (insecure(server)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        "This server uses http on a public network. Your notes stay encrypted, but your sign-in is not. Use https.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            AnimatedVisibility(signup) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Email, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(password, { password = it }, if (signup) "Password (8+ characters)" else "Password", ::submit)
        }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        PrimaryAction(if (signup) "Create account" else "Sign in", busy, valid, ::submit)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = session::continueLocal) { Text("Continue without an account") }
        Text(
            "Your password never leaves this device; only a derived key does.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun KeyScreen(session: Session, setup: Boolean, user: User?, error: String?) {
    val scope = rememberCoroutineScope()
    var pass by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    val valid = if (setup) pass.length >= 8 && pass == confirm else pass.isNotEmpty()
    fun submit() {
        if (!valid || busy) return
        busy = true
        scope.launch {
            if (setup) session.setupKeys(pass) else session.unlock(pass)
            busy = false
        }
    }
    AuthFrame(
        title = if (setup) "Set an encryption passphrase" else "Unlock this device",
        subtitle = if (setup) "Your notes are encrypted with a key only this passphrase opens. If you lose it, nobody can recover your notes."
        else "Signed in as ${user?.email.orEmpty()}. Enter your password to decrypt your notes on this device.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PasswordField(pass, { pass = it }, if (setup) "Passphrase (8+ characters)" else "Password", ::submit)
            if (setup) PasswordField(confirm, { confirm = it }, "Repeat passphrase", ::submit)
        }
        ErrorText(error)
        Spacer(Modifier.height(20.dp))
        PrimaryAction(if (setup) "Encrypt my notes" else "Unlock", busy, valid, ::submit)
        TextButton(onClick = { scope.launch { session.logout() } }) { Text("Sign out") }
    }
}
