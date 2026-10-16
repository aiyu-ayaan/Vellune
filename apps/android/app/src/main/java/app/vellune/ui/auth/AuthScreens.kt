package app.vellune.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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

@Composable
private fun AuthFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Text("V", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            content()
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
        subtitle = "Where thoughts take form. Your notes are end-to-end encrypted before they leave this device.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
            ToggleButton(!signup, { signup = false }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(), modifier = Modifier.weight(1f)) { Text("Sign in") }
            ToggleButton(signup, { signup = true }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(), modifier = Modifier.weight(1f)) { Text("Create account") }
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
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
