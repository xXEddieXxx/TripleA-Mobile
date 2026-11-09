package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import org.triplea.mobile.app.AppServices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GameData
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.PlayerKind
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.forum.ForumAccount
import org.triplea.mobile.app.forum.ForumThreadPicker
import org.triplea.mobile.forum.NodeBbClient.GameThread
import org.triplea.mobile.forum.PbfProperties
import androidx.compose.material3.OutlinedTextField

/**
 * Game selection and player assignment. With [saveFile] set, the save game is loaded instead of
 * offering the installed games.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(
    saveFile: Path?,
    onGameStarted: () -> Unit,
    onBack: () -> Unit,
    forumThread: GameThread? = null,
    newForumGame: Boolean = false,
    onRegisterDiceEmail: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val forumGame = forumThread != null || newForumGame
    var myEmail by remember { mutableStateOf(AppSettings.current.diceEmail) }
    // the other players' dice emails: the opponent, and with "Add player" more (desktop's "CC")
    val otherEmails = remember { mutableStateListOf("") }
    var emailWarning by remember { mutableStateOf<String?>(null) }
    var threadTitle by remember { mutableStateOf("") }
    var firstPost by remember { mutableStateOf("") }
    var createdThread by remember { mutableStateOf<GameThread?>(null) }
    // a new game in a thread that already exists (picked), instead of opening a new one
    var existingThread by remember { mutableStateOf<GameThread?>(null) }
    var threadHeldBack by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<MobileEngine.InstalledGame>>(emptyList()) }
    var gameData by remember { mutableStateOf<GameData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val kinds = remember { mutableStateMapOf<String, PlayerKind>() }
    var showOptions by remember { mutableStateOf(false) }

    LaunchedEffect(saveFile) {
        loading = true
        try {
            if (saveFile != null) {
                gameData = withContext(Dispatchers.IO) {
                    MobileEngine.loadSaveGame(saveFile).orElse(null)
                }
                if (gameData == null) error = "Could not load the save game."
            } else {
                games = withContext(Dispatchers.IO) {
                    runCatching { MobileEngine.listInstalledGames() }
                        .onFailure { error = "Could not read the installed maps: ${it.message}" }
                        .getOrDefault(emptyList())
                }
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(gameData) {
        val data = gameData ?: return@LaunchedEffect
        kinds.clear()
        // the Hard (Pro) AI needs minutes per turn on big maps on a phone; start those with the Fast AI
        val bigMap = data.map.territories.size > 120
        val defaultAi = if (bigMap) PlayerKind.AI_FAST else PlayerKind.AI_HARD
        // a save remembers who played each nation (the engine records it at game start)
        val remembered = if (saveFile != null) {
            data.playerList.players.associate { it.name to PlayerKind.fromWhoAmI(it.whoAmI).orElse(null) }
        } else {
            emptyMap()
        }
        val anyRemembered = remembered.values.any { it != null }
        data.playerList.players.forEachIndexed { index, player ->
            kinds[player.name] = remembered[player.name]
                ?: if (forumGame || (index == 0 && !anyRemembered)) PlayerKind.HUMAN else defaultAi
        }
        if (forumGame) {
            threadTitle = data.gameName
            // the second field (desktop's "CC") may hold several addresses, separated by spaces
            val saved = listOf(PbfProperties.DICE_EMAIL_1, PbfProperties.DICE_EMAIL_2)
                .flatMap { data.properties.get(it, "").split(Regex("\\s+")) }.filter { it.isNotBlank() }
            if (saved.isNotEmpty()) {
                // the people of this game join the addresses offered in every setup
                AppSettings.update { it.copy(diceContacts = (it.diceContacts + saved).distinctBy { c -> c.lowercase() }) }
                val mine = saved.firstOrNull { it.equals(myEmail, ignoreCase = true) }
                val rest = if (myEmail.isBlank()) {
                    // no email remembered yet: the player confirms which one is theirs
                    myEmail = saved[0]
                    emailWarning = "Is ${saved[0]} your dice email? Swap or correct the fields if not; yours is remembered."
                    saved.drop(1)
                } else if (mine == null) {
                    // joining a game you have not rolled in yet: your email joins the others
                    emailWarning = "You have not rolled in this game yet (its dice emails: ${saved.joinToString()}). " +
                        "Enter your email and keep the others', so every roll is mailed to all of you."
                    saved
                } else {
                    emailWarning = null
                    saved.filterNot { it.equals(mine, ignoreCase = true) }
                }
                otherEmails.clear()
                otherEmails += rest.ifEmpty { listOf("") }
            } else if (forumThread != null && saveFile != null) {
                emailWarning = "This save has no dice server emails. Enter both, so every roll is mailed to both players."
            }
        }
    }

    val optionsData = gameData
    if (showOptions && optionsData != null) {
        GameOptionsScreen(data = optionsData, onBack = { showOptions = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(
            if (gameData == null) "Choose a game" else (gameData?.gameName ?: "Players"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (gameData == null) {
            Box(Modifier.weight(1f)) {
                GameChooser(games) { game ->
                    loading = true
                    error = null
                    scope.launch {
                        val parsed = withContext(Dispatchers.IO) {
                            runCatching { MobileEngine.parseGame(game.xmlPath).orElse(null) }.getOrElse { null }
                        }
                        loading = false
                        if (parsed == null) error = "Could not read ${game.gameName}" else gameData = parsed
                    }
                }
            }
        } else {
            val data = gameData!!
            val alliances = remember(data) {
                runCatching {
                    data.allianceTracker.alliances.sorted().map { name -> name to data.allianceTracker.getPlayersInAlliance(name).map { it.name } }
                }.getOrDefault(emptyList())
            }
            // one line of chips: everyone or an alliance at once
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                QuickPick("All") { kind -> data.playerList.players.forEach { kinds[it.name] = kind } }
                alliances.forEach { (name, members) -> QuickPick(name) { kind -> members.forEach { kinds[it] = kind } } }
            }
            // the nations grouped by their alliance, so it is clear who fights with whom
            val groups = remember(data, alliances) {
                val placed = HashSet<String>()
                val result = ArrayList<Pair<String, List<games.strategy.engine.data.GamePlayer>>>()
                alliances.forEach { (name, members) ->
                    val players = data.playerList.players.filter { it.name in members && placed.add(it.name) }
                    if (players.isNotEmpty()) result += name to players
                }
                val rest = data.playerList.players.filter { it.name !in placed }
                if (rest.isNotEmpty()) result += (if (result.isEmpty()) "" else "No alliance") to rest
                result
            }
            LazyColumn(Modifier.weight(1f)) {
                if (forumGame) {
                    item(key = "forum") {
                        ForumGameFields(
                            newGame = newForumGame,
                            existingThread = existingThread,
                            onExistingThread = { existingThread = it },
                            threadTitle = threadTitle,
                            onThreadTitle = { threadTitle = it },
                            firstPost = firstPost,
                            onFirstPost = { firstPost = it },
                            myEmail = myEmail,
                            onMyEmail = { myEmail = it },
                            otherEmails = otherEmails,
                            warning = emailWarning,
                            onRegister = onRegisterDiceEmail,
                        )
                    }
                }
                groups.forEach { (alliance, players) ->
                    if (alliance.isNotBlank()) {
                        item(key = "alliance-$alliance") {
                            Text(
                                alliance,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                items(players, key = { it.name }) { player ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NationFlag(player.name)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            player.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        PlayerKindPicker(
                            kind = kinds[player.name] ?: PlayerKind.AI_HARD,
                            onChange = { kinds[player.name] = it },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (saveFile == null) gameData = null else onBack() }) { Text("Back") }
                TextButton(onClick = { showOptions = true }) { Text("Options & bids") }
                Button(
                    onClick = {
                        val others = otherEmails.map { it.trim() }.filter { it.isNotEmpty() }
                        if (forumGame && (!myEmail.contains('@') || others.isEmpty() || others.any { !it.contains('@') })) {
                            error = "Enter your and every other player's dice server email: every roll is mailed to all of you."
                            return@Button
                        }
                        if (threadHeldBack) {
                            error = ThreadHeldBack().message
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    // a thread opened by an earlier Start is reused, never opened twice
                                    val thread = if (forumGame) {
                                        prepareForumGame(data, forumThread ?: existingThread ?: createdThread, threadTitle, firstPost, myEmail.trim(), others)
                                            .also { createdThread = it }
                                    } else null
                                    GameController.startSession(data, kinds.toMap(), fromSave = saveFile != null, forumThread = thread)
                                }.onFailure { if (it is ThreadHeldBack) threadHeldBack = true }
                            }
                            loading = false
                            ok.onSuccess { onGameStarted() }
                                .onFailure { error = it.message ?: it.toString() }
                        }
                    },
                ) { Text("Start") }
            }
        }
        if (gameData == null && !loading) {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
    }
}

/**
 * Writes the dice emails into the game, remembers the player's own, and for a new game opens its
 * forum thread. Returns the thread the game posts to. Call from a background thread.
 */
private fun prepareForumGame(
    data: GameData,
    existing: GameThread?,
    title: String,
    firstPost: String,
    myEmail: String,
    otherEmails: List<String>,
): GameThread {
    data.properties.set(PbfProperties.DICE_EMAIL_1, myEmail)
    // as desktop's "CC": every other player's address, separated by spaces
    data.properties.set(PbfProperties.DICE_EMAIL_2, otherEmails.joinToString(" "))
    // the field the player confirmed with Start as theirs (the setup asks when it had to guess),
    // and every address used, offered again in the next game's setup
    AppSettings.update { settings ->
        val contacts = (settings.diceContacts + myEmail + otherEmails).distinctBy { it.lowercase() }
        settings.copy(diceEmail = myEmail, diceContacts = contacts)
    }
    if (existing != null) return existing
    // a new game starts on the forum the Play by Forum screen showed
    val forum = ForumAccount.selected.value
    val client = ForumAccount.client(forum) ?: throw IllegalStateException("Sign in to ${forum.label} under Play by Forum first.")
    val content = firstPost.ifBlank { "New ${data.gameName} game. Who joins?" }
    return client.startThread(title.ifBlank { data.gameName }, content).orElseThrow { ThreadHeldBack() }
}

/** The forum queued the new thread for a moderator: it has no id to post to yet. */
private class ThreadHeldBack :
    IllegalStateException("The forum holds the new thread for a moderator. Open it from Play by Forum once it is approved.")

@Composable
private fun ForumGameFields(
    newGame: Boolean,
    existingThread: GameThread?,
    onExistingThread: (GameThread?) -> Unit,
    threadTitle: String,
    onThreadTitle: (String) -> Unit,
    firstPost: String,
    onFirstPost: (String) -> Unit,
    myEmail: String,
    onMyEmail: (String) -> Unit,
    otherEmails: SnapshotStateList<String>,
    warning: String?,
    onRegister: () -> Unit,
) {
    // the addresses of earlier games, offered in every field; those already filled in are left out
    val contacts = AppSettings.state.collectAsState().value.diceContacts
    fun offered(current: String) = contacts.filter { c ->
        !c.equals(current, ignoreCase = true) && !c.equals(myEmail, ignoreCase = true) &&
            otherEmails.none { it.equals(c, ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Play by Forum", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (newGame) {
            var picking by remember { mutableStateOf(false) }
            if (picking) ForumThreadPicker(onPick = { onExistingThread(it); picking = false }, onDismiss = { picking = false })
            if (existingThread == null) {
                OutlinedTextField(threadTitle, onThreadTitle, Modifier.fillMaxWidth(), label = { Text("Thread title") }, singleLine = true)
                OutlinedTextField(firstPost, onFirstPost, Modifier.fillMaxWidth(), label = { Text("First post (optional)") }, maxLines = 3)
                TextButton(onClick = { picking = true }) { Text("Use an existing thread instead") }
            } else {
                // the game posts to this thread; no new thread is opened
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Thread", style = MaterialTheme.typography.bodySmall)
                        Text(existingThread.title(), style = MaterialTheme.typography.titleSmall)
                    }
                    IconButton(onClick = { onExistingThread(null) }) { Icon(Icons.Filled.Close, contentDescription = "open a new thread instead") }
                }
            }
        }
        EmailField(myEmail, onMyEmail, "Your dice email", offered(myEmail))
        otherEmails.forEachIndexed { index, email ->
            EmailField(
                value = email,
                onValue = { otherEmails[index] = it },
                label = if (index == 0) "Opponent's dice email" else "Player ${index + 2} dice email",
                contacts = offered(email),
                onRemove = if (index == 0) null else { { otherEmails.removeAt(index) } },
            )
        }
        // the dice server mails every roll to all of them (desktop's "CC")
        TextButton(onClick = { otherEmails += "" }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add player")
        }
        warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Every email must be registered with the dice server.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRegister) { Text("Register") }
        }
    }
}

/** A dice email field; its arrow lists the addresses of earlier games, typing narrows the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmailField(value: String, onValue: (String) -> Unit, label: String, contacts: List<String>, onRemove: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val shown = contacts.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded && shown.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValue(it); expanded = true },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                trailingIcon = { if (contacts.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded && shown.isNotEmpty()) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
                shown.forEach { contact ->
                    DropdownMenuItem(text = { Text(contact) }, onClick = { onValue(contact); expanded = false })
                }
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "remove player") }
        }
    }
}

/** A chip of the quick selection: tap for "set everyone / this alliance to ...". */
@Composable
private fun QuickPick(label: String, onPick: (PlayerKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(" \u25be", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayerKind.values().forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onPick(option) })
            }
        }
    }
}

private val PICKER_WIDTH = 104.dp

/** The nation's flag from the engine assets or the map, or a blank placeholder. */
@Composable
private fun NationFlag(name: String) {
    val bitmap = remember(name) {
        runCatching {
            val candidates = listOf(
                AppServices.engineAssetsDir.resolve("flags").resolve("$name.png"),
                AppServices.engineAssetsDir.resolve("flags").resolve("${name}_small.png"),
            )
            candidates.firstOrNull { java.nio.file.Files.exists(it) }?.let { android.graphics.BitmapFactory.decodeFile(it.toString()) }
        }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = name, modifier = Modifier.height(20.dp).widthIn(max = 34.dp))
    } else {
        Spacer(Modifier.width(24.dp))
    }
}

/** Human or one of the AIs, as a fixed width dropdown so long nation names never push it away. */
@Composable
private fun PlayerKindPicker(kind: PlayerKind, onChange: (PlayerKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.width(PICKER_WIDTH),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (kind == PlayerKind.HUMAN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(kind.label.removeSuffix(" (AI)"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayerKind.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}
