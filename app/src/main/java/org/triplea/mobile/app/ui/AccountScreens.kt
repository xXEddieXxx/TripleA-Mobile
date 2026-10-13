package org.triplea.mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.app.online.TurnCheckWorker
import org.triplea.mobile.online.client.ServerApi

/**
 * Online "Account": the e-mail (added or changed with a mailed code), the password, and deleting the account.
 * [mailedTo]: the address a registration just mailed a confirmation code to; the code field is open for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit, mailedTo: String? = null) {
    val serverUrl = AppSettings.state.collectAsState().value.serverUrl
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by remember { mutableStateOf<String?>(null) }
    var mailOn by remember { mutableStateOf(false) }
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var newEmail by remember { mutableStateOf("") }
    var emailPassword by remember { mutableStateOf("") }
    var codeSentTo by remember { mutableStateOf(mailedTo) }
    var code by remember { mutableStateOf("") }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    /** [call] off the main thread, then [done] with its result on Main; a failure becomes [error]. */
    fun <T> run(call: (ServerApi) -> T, done: (T) -> Unit) =
        scope.runBusy(busyState, { error = it; info = null }, { call(OnlineApi.forUrl(serverUrl)) }, after = {
            deleting = false
            // a deleted account or a refused refresh has no tokens left: stop what logout stops, back to the login form
            if (AppSettings.tokens.tokens == null) {
                Social.stop()
                TurnCheckWorker.stop(context)
                onBack()
            }
        }, done = done)

    // the delete's result must always be handled here: no leaving while it runs
    BackHandler(enabled = deleting) { }

    LaunchedEffect(serverUrl) {
        run({ server -> server.features().email to server.account().email }) { (on, address) ->
            mailOn = on
            email = address
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = { IconButton(onClick = onBack, enabled = !deleting) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Username: ${AppSettings.tokens.tokens?.username.orEmpty()}")
            if (busy) CircularProgressIndicator()
            if (busy && deleting) Text("Deleting the account… Your running games are handed to the AI; this can take a minute.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            if (mailOn) {
                Section("E-mail")
                Text(email?.let { "Confirmed: $it" } ?: "No e-mail address. With one you can reset a forgotten password.")
                val sentTo = codeSentTo
                if (sentTo == null) {
                    OutlinedTextField(
                        newEmail, { newEmail = it.trim() }, label = { Text(if (email == null) "E-mail address" else "New e-mail address") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                    )
                    // the server asks for it: a stolen login alone must not move the reset mails elsewhere
                    PasswordField("Current password", emailPassword) { emailPassword = it }
                    Button(onClick = {
                        val address = newEmail
                        run({ it.requestEmail(address, emailPassword) }) {
                            codeSentTo = address
                            emailPassword = ""
                            info = "A code was sent to $address."
                        }
                    }, enabled = !busy && newEmail.contains('@') && emailPassword.isNotEmpty()) { Text("Send code") }
                    // the code mailed at registration, or one asked for before leaving this screen
                    TextButton(onClick = { codeSentTo = "the address you gave" }, enabled = !busy) { Text("I have a code") }
                } else {
                    Text("A confirmation code was sent to $sentTo.")
                    CodeField(code) { code = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            run({ server -> server.confirmEmail(code); server.account().email }) { confirmed ->
                                email = confirmed
                                codeSentTo = null
                                code = ""
                                newEmail = ""
                                info = "E-mail address confirmed."
                            }
                        }, enabled = !busy && code.length == 6) { Text("Confirm") }
                        TextButton(onClick = { codeSentTo = null; code = "" }, enabled = !busy) { Text("Cancel") }
                    }
                }
            }

            Section("Password")
            PasswordField("Current password", oldPassword) { oldPassword = it }
            PasswordField("New password (8 or more characters)", newPassword) { newPassword = it }
            Button(onClick = {
                run({ it.changePassword(oldPassword, newPassword) }) {
                    oldPassword = ""
                    newPassword = ""
                    info = "Password changed. Other devices are logged out."
                }
            }, enabled = !busy && oldPassword.isNotEmpty() && newPassword.length >= 8) { Text("Change password") }

            Section("Delete account")
            OutlinedButton(onClick = { showDelete = true }, enabled = !busy) { Text("Delete account") }
        }
    }
    if (showDelete) {
        DeleteAccountDialog(onDismiss = { showDelete = false }) { password ->
            showDelete = false
            // a wrong password keeps everything; success leaves no tokens, and run goes back to the login form
            // deleting only when the call starts: runBusy refuses one while another runs, and only after() resets it
            if (!busy) {
                deleting = true
                run({ it.deleteAccount(password) }) { }
            }
        }
    }
}

/** Forgot password, two steps: the username or e-mail, then the mailed code and a new password; success logs in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val serverUrl = AppSettings.state.collectAsState().value.serverUrl
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    var error by remember { mutableStateOf<String?>(null) }

    fun run(call: (ServerApi) -> Unit, done: () -> Unit) =
        scope.runBusy(busyState, { error = it }, { call(OnlineApi.forUrl(serverUrl)) }) { done() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forgot password") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!sent) {
                Text("Enter your username or the e-mail address of your account. If it has a confirmed e-mail address, a 6-digit code is sent to it.")
                OutlinedTextField(login, { login = it.trim() }, label = { Text("Username or e-mail") }, singleLine = true, keyboardOptions = usernameKeyboard, modifier = Modifier.fillMaxWidth())
                Button(onClick = { run({ it.forgotPassword(login) }) { sent = true } }, enabled = !busy && login.isNotEmpty()) { Text("Send code") }
            } else {
                Text("If the account has a confirmed e-mail address, a code is on its way to it. It is valid for 15 minutes.")
                CodeField(code) { code = it }
                PasswordField("New password (8 or more characters)", password) { password = it }
                Button(onClick = { run({ it.resetPassword(login, code, password) }, onLoggedIn) }, enabled = !busy && code.length == 6 && password.length >= 8) {
                    Text("Set password and log in")
                }
                TextButton(onClick = { sent = false; code = "" }, enabled = !busy) { Text("Send a new code") }
            }
        }
    }
}

/** The bundled privacy policy (German, the server's PRIVACY.md), with an English title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember { markdownBlocks(context.assets.open("privacy_de.md").bufferedReader().use { it.readText() }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy policy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("The privacy policy is in German.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            items(blocks) { (kind, text) ->
                when (kind) {
                    '1' -> Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                    '2' -> Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    '3' -> Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    '>' -> Text(text, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    '-' -> Row { Text("•  "); Text(text) }
                    else -> Text(text)
                }
            }
        }
    }
}

/**
 * The few Markdown forms PRIVACY.md uses, as (kind, text): '1' to '3' a heading of that level, '>' a quote,
 * '-' a list item, 'p' a paragraph. Wrapped lines are joined with a space.
 */
internal fun markdownBlocks(markdown: String): List<Pair<Char, String>> {
    val blocks = mutableListOf<Pair<Char, String>>()
    for (line in markdown.lines()) {
        val trimmed = line.trim()
        val last = blocks.lastOrNull()
        when {
            trimmed.isEmpty() -> blocks += ' ' to ""
            trimmed.startsWith("#") -> blocks += trimmed.takeWhile { it == '#' }.length.coerceAtMost(3).digitToChar() to trimmed.trimStart('#').trim()
            trimmed.startsWith(">") && last?.first == '>' -> blocks[blocks.lastIndex] = '>' to last.second + " " + trimmed.removePrefix(">").trim()
            trimmed.startsWith(">") -> blocks += '>' to trimmed.removePrefix(">").trim()
            trimmed.startsWith("- ") -> blocks += '-' to trimmed.removePrefix("- ")
            // a continuation of the paragraph or list item above
            last != null && last.first in "p-" -> blocks[blocks.lastIndex] = last.first to last.second + " " + trimmed
            else -> blocks += 'p' to trimmed
        }
    }
    return blocks.filter { it.first != ' ' }
}

@Composable
private fun ColumnScope.Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) = OutlinedTextField(
    code, { onChange(it.filter(Char::isDigit).take(6)) }, label = { Text("6-digit code from the mail") }, singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth(),
)

@Composable
internal fun PasswordField(label: String, value: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AppDialog(
        title = "Delete account",
        subtitle = "Your account is anonymised, your friends and blocks are deleted, and your seats in running games become AI. This cannot be undone.",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(enabled = password.isNotEmpty(), onClick = { onConfirm(password) }) { Text("Delete") }
        },
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) { PasswordField("Password", password) { password = it } }
    }
}
