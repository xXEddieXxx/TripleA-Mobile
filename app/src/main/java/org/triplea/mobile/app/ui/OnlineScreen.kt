package org.triplea.mobile.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.app.online.TurnCheckWorker
import org.triplea.mobile.online.client.ServerApi
import org.triplea.mobile.protocol.FriendDto
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.TurnInfoDto


private const val LOBBY_RELOAD_DELAY_MS = 300L
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Usernames are typed as they are: no autocorrect ("bob" became "Bobby") and no capital first letter. */
internal val usernameKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)

/**
 * Online play (debug builds): log in; tabs for my games, the lobby with its chat, and friends. Lobby games
 * open in the setup screen ([onOpenSetup], null for a new game).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineScreen(
    onBack: () -> kotlin.Unit,
    onOpenGame: () -> kotlin.Unit,
    onOpenSetup: (String?) -> kotlin.Unit,
    onOpenChat: (String) -> kotlin.Unit,
    /** Gets the address a registration just mailed a code to, else null. */
    onOpenAccount: (String?) -> kotlin.Unit,
    onOpenPrivacy: () -> kotlin.Unit,
    onForgotPassword: () -> kotlin.Unit,
    onOpenProfile: (String) -> kotlin.Unit,
) {
    val settings by AppSettings.state.collectAsState()
    val tokens = AppSettings.tokens
    val api = remember(settings.serverUrl) { runCatching { OnlineApi.forUrl(settings.serverUrl) } }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(tokens.tokens?.username) }
    // back from Account after a delete: the tokens are gone, show the login form
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { username = tokens.tokens?.username }
    var error by remember { mutableStateOf<String?>(null) }
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    // the server's e-mail feature: the register e-mail field and "Forgot password?" exist only with it
    var mailOn by remember { mutableStateOf(false) }
    var games by remember { mutableStateOf<List<GameInfoDto>>(emptyList()) }
    var lobbyGames by remember { mutableStateOf<List<GameInfoDto>>(emptyList()) }
    // cards pushed since the running reload started (Main only): newer than what that reload fetches
    val pushed = remember { mutableMapOf<String, GameInfoDto>() }
    var friends by remember { mutableStateOf<List<FriendDto>>(emptyList()) }
    var showJoinByCode by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lobbyVersion by Social.lobbyVersion.collectAsState()

    var loadJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Runs [call] off the main thread with the spinner on ([runBusy]); success runs [done] on Main. A refused
     * refresh logs out either way: back to the login form.
     */
    fun run(done: () -> kotlin.Unit = {}, call: (ServerApi) -> kotlin.Unit) {
        val server = api.getOrElse { error = "Invalid server URL: ${settings.serverUrl}"; return }
        scope.runBusy(busyState, { error = it }, { call(server) }, after = { username = tokens.tokens?.username }) { done() }
    }

    /** Every list of the three tabs, fetched on IO and shown on Main; a newer reload cancels this one, so no stale result lands. */
    fun reload() {
        val server = api.getOrNull() ?: return
        loadJob?.cancel()
        pushed.clear()
        loadJob = scope.launch {
            try {
                val (mine, lobby, friendList) = withContext(Dispatchers.IO) {
                    Social.resetPresence()
                    Triple(server.games().also(Social::learnSkew), server.lobby(), server.friends()).also { Social.loadBlocks() }
                }
                games = mine.map { pushed[it.id] ?: it }
                lobbyGames = lobby.map { pushed[it.id] ?: it }
                friends = friendList
                Social.friendsLoaded(friendList)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                username = tokens.tokens?.username // a refused refresh logged out: back to the login form
            }
        }
    }

    fun clearLists() {
        loadJob?.cancel()
        games = emptyList()
        lobbyGames = emptyList()
        friends = emptyList()
    }

    /** A lobby or friends change on the server: [call], then the lists again. */
    fun change(call: (ServerApi) -> kotlin.Unit) = run(done = ::reload, call = call)

    fun open(game: GameInfoDto, spectate: Boolean = false) = run { server ->
        GameController.startOnline(server, game, username.orEmpty(), spectate)
        scope.launch { onOpenGame() }
    }

    fun leave() {
        Social.stop()
        onBack()
    }
    BackHandler(onBack = ::leave)

    // Android 17 blocks private addresses (the emulator's host, a LAN server) without this permission;
    // the turn notifications need the other one (Android 13+). Asked together: one request at a time
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[LOCAL_NETWORK] == false) error = "Without the local network permission only public servers can be reached."
    }
    LaunchedEffect(Unit) {
        listOfNotNull(
            LOCAL_NETWORK.takeIf { Build.VERSION.SDK_INT >= 37 },
            Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        ).takeIf { it.isNotEmpty() }?.let { permissions.launch(it.toTypedArray()) }
    }
    val context = LocalContext.current
    LaunchedEffect(username, api) {
        val server = api.getOrNull()
        if (username != null && server != null) Social.start(server) else Social.stop()
        // turn notifications poll only while logged in
        if (username != null) TurnCheckWorker.start(context) else TurnCheckWorker.stop(context)
    }
    LaunchedEffect(username, api) {
        val server = api.getOrNull() ?: return@LaunchedEffect
        // an unreachable or older server: no e-mail features
        if (username == null) mailOn = withContext(Dispatchers.IO) { runCatching { server.features().email }.getOrDefault(false) }
    }
    // the first run loads the lists, later ones follow the server's lobby LobbyUpdates and FriendsChanged: a burst
    // of them waits 300 ms and reloads once (a newer one cancels this effect's delay)
    LaunchedEffect(username, lobbyVersion) {
        if (username == null) return@LaunchedEffect
        if (lobbyVersion > 0) delay(LOBBY_RELOAD_DELAY_MS)
        reload()
    }
    // a running game's LobbyUpdate (the turn passed) replaces its card; a game in neither list waits for the next reload
    LaunchedEffect(Unit) {
        Social.gameUpdates.collect { game ->
            pushed[game.id] = game
            games =games.map { if (it.id == game.id) game else it }
            lobbyGames = lobbyGames.map { if (it.id == game.id) game else it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online") },
                navigationIcon = {
                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = {
                    if (username != null) {
                        TextButton(onClick = ::reload, enabled = !busy) { Text("Refresh") }
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "menu") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(text = { Text("Log out") }, enabled = !busy, onClick = {
                                    showMenu = false
                                    run(done = { Social.stop(); clearLists() }) { server -> server.logout() }
                                })
                                DropdownMenuItem(text = { Text("Profile") }, onClick = { showMenu = false; username?.let(onOpenProfile) })
                                DropdownMenuItem(text = { Text("Account") }, onClick = { showMenu = false; onOpenAccount(null) })
                                DropdownMenuItem(text = { Text("Privacy policy") }, onClick = { showMenu = false; onOpenPrivacy() })
                                // online play is debug-only, so is this test trigger of the turn notification
                                DropdownMenuItem(text = { Text("Check turns now") }, onClick = { showMenu = false; TurnCheckWorker.checkNow(context) })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        // edge to edge: the keyboard would cover the lobby chat field without imePadding
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val me = username
            Text("Server: ${settings.serverUrl}" + (me?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp).align(Alignment.CenterHorizontally))
            if (me == null) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    LoginForm(enabled = !busy, mailOn = mailOn, onOpenPrivacy = onOpenPrivacy, onForgotPassword = onForgotPassword) { name, password, email, register ->
                        // registered with an e-mail: the Account screen takes the mailed code
                        run(done = { if (email != null) onOpenAccount(email) }) { server ->
                            if (register) server.register(name, password, ageConfirmed = true, email = email) else server.login(name, password)
                        }
                    }
                }
                PrivacyFooter(onOpenPrivacy)
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Games", "Lobby", "Friends").forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
            }
            when (tab) {
                0 -> {
                    Button(onClick = { onOpenSetup(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("New online game")
                    }
                    if (games.isEmpty() && !busy) Text("No games yet.", modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(games, key = { it.id }) { game ->
                            GameCard(game, me, enabled = !busy, onOpenProfile = onOpenProfile, onOpen = { if (game.status == "lobby") onOpenSetup(game.id) else open(game) })
                        }
                    }
                }
                1 -> {
                    OutlinedButton(onClick = { showJoinByCode = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Join by code")
                    }
                    val joinable = lobbyGames.filter { game -> game.status == "lobby" && game.seats.none { it.username == me } && game.seats.any { it.kind == "OPEN" } }
                    // running games the server lets me watch; version 0: no save old enough for spectators yet
                    val watchable = lobbyGames.filter { it.status == "running" && it.version > 0 }
                    LazyColumn(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (joinable.isEmpty()) item { Text("No open games right now.", modifier = Modifier.padding(vertical = 8.dp)) }
                        items(joinable, key = { it.id }) { game ->
                            LobbyCard(game, enabled = !busy, onOpenProfile = onOpenProfile) { onOpenSetup(game.id) }
                        }
                        if (watchable.isNotEmpty()) item { Text("Running games", style = MaterialTheme.typography.titleSmall) }
                        items(watchable, key = { "w" + it.id }) { game ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${game.gameName} (${game.mapName}) · " + visibilityLabel(game), style = MaterialTheme.typography.titleSmall)
                                    Seats(game, onOpenProfile)
                                    TurnLine(game, me)
                                    AssistChip(onClick = { open(game, spectate = true) }, enabled = !busy, label = { Text("Watch") })
                                }
                            }
                        }
                    }
                    Text("Lobby chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    ChatPanel("lobby", me, Modifier.weight(0.55f), onOpenProfile = onOpenProfile)
                }
                else -> FriendsTab(friends, enabled = !busy, change = ::change, onOpenChat = onOpenChat, onOpenProfile = onOpenProfile)
            }
            PrivacyFooter(onOpenPrivacy)
        }
    }

    val me = username ?: return
    if (showJoinByCode) {
        JoinByCodeDialog(onDismiss = { showJoinByCode = false }) { id, code, nation ->
            showJoinByCode = false
            run(done = { onOpenSetup(id) }) { it.join(id, nation, code) }
        }
    }
}

@Composable
private fun ColumnScope.PrivacyFooter(onOpenPrivacy: () -> kotlin.Unit) {
    TextButton(onClick = onOpenPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Privacy policy", style = MaterialTheme.typography.bodySmall)
    }
}

/** Log in or register; [mailOn] adds the optional e-mail and "Forgot password?". onSubmit gets name, password, e-mail (null if none) and register. */
@Composable
private fun LoginForm(
    enabled: Boolean,
    mailOn: Boolean,
    onOpenPrivacy: () -> kotlin.Unit,
    onForgotPassword: () -> kotlin.Unit,
    onSubmit: (String, String, String?, Boolean) -> kotlin.Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var adult by remember { mutableStateOf(false) }
    var readPrivacy by remember { mutableStateOf(false) }
    OutlinedTextField(name, { name = it.trim() }, label = { Text("Username") }, singleLine = true, keyboardOptions = usernameKeyboard, modifier = Modifier.fillMaxWidth())
    PasswordField("Password", password) { password = it }
    if (mailOn) {
        TextButton(onClick = onForgotPassword, enabled = enabled) { Text("Forgot password?") }
        OutlinedTextField(
            email,
            { email = it.trim() },
            label = { Text("E-mail (optional, for registering)") },
            supportingText = { Text("Lets you reset a forgotten password. A code is mailed to confirm it.") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable { adult = !adult }) {
        Checkbox(checked = adult, onCheckedChange = { adult = it })
        Text("I am 16 or older (needed to register)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = readPrivacy, onCheckedChange = { readPrivacy = it })
        Text("I have read the ", modifier = Modifier.clickable { readPrivacy = !readPrivacy })
        Text(
            "privacy policy",
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(onClick = onOpenPrivacy),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        val filled = enabled && name.isNotEmpty() && password.isNotEmpty()
        Button(onClick = { onSubmit(name, password, null, false) }, enabled = filled) { Text("Log in") }
        OutlinedButton(
            onClick = { onSubmit(name, password, email.takeIf { mailOn && it.isNotEmpty() }, true) },
            enabled = filled && adult && readPrivacy,
        ) { Text("Register") }
    }
}

/** Who plays each nation; a player's name opens their profile. */
@Composable
private fun ColumnScope.Seats(game: GameInfoDto, onOpenProfile: (String) -> kotlin.Unit) {
    game.seats.forEach { seat ->
        val name = seat.username
        Text(
            "${seat.nation}: ${seatLabel(seat)}",
            style = MaterialTheme.typography.bodySmall,
            modifier = if (name != null) Modifier.clickable { onOpenProfile(name) } else Modifier,
        )
    }
}

/** "since 5 min", "since 2 h", "since 3 d": how long a turn without a clock has lasted. */
private fun since(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 60 -> "since $minutes min"
        minutes < 48 * 60 -> "since ${minutes / 60} h"
        else -> "since ${minutes / (24 * 60)} d"
    }
}

/**
 * Who is to move in a running game: "Your turn · 4:12", "Waiting for bob · Germans · 2:03", "AI moving (Germans)"
 * or, without a clock, "Waiting for bob · since 2 h". A running clock counts down locally to the server's deadline,
 * on the server's time ([Social.clockSkewMs]).
 */
@Composable
private fun TurnLine(game: GameInfoDto, me: String) {
    val listed = game.turn ?: return
    // the game's latest Clock is newer than the card: whose clock runs (a defender in a battle, too) and until when
    val latest = Social.clocks.collectAsState().value[game.id]
    val running = latest?.running
    val turn = game.seats.firstOrNull { running != null && running != listed.username && it.username == running }
        ?.let { TurnInfoDto(it.nation, running, ai = false) } ?: listed
    val deadline = if (latest != null) latest.deadline.takeIf { running != null } else game.turnDeadline
    val now by produceState(System.currentTimeMillis() + Social.clockSkewMs, deadline) {
        while (deadline != null) {
            value = System.currentTimeMillis() + Social.clockSkewMs
            delay(1000 - value % 1000)
        }
    }
    val clock = deadline?.let { clockText(it - now) } ?: (latest?.clocks?.get(turn.username) ?: game.turnRemainingMs)?.let(::clockText)
    val mine = !turn.ai && turn.username == me
    val text = when {
        turn.ai -> "AI moving (${turn.nation})"
        mine -> "Your turn" + (clock?.let { " · $it" } ?: "")
        else -> "Waiting for ${turn.username ?: turn.nation}" + (if (turn.username != null) " · ${turn.nation}" else "") +
            " · " + (clock ?: game.turnSince?.let { since(now - it) } ?: "")
    }.removeSuffix(" · ")
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
        color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One of my games; a tap opens a lobby game's setup or a running game. */
@Composable
private fun GameCard(game: GameInfoDto, me: String, enabled: Boolean, onOpenProfile: (String) -> kotlin.Unit, onOpen: () -> kotlin.Unit) {
    val running = game.status == "running"
    val mySeats = game.seats.filter { it.username == me }
    // a finished game shows its result only
    Card(Modifier.fillMaxWidth().then(if ((running || game.status == "lobby") && enabled) Modifier.clickable(onClick = onOpen) else Modifier)) {
        Column(Modifier.padding(12.dp)) {
            Text("${game.gameName} (${game.mapName})", style = MaterialTheme.typography.titleSmall)
            if (game.status == "lobby") {
                Text("Waiting for players · " + visibilityLabel(game), style = MaterialTheme.typography.bodySmall)
                Seats(game, onOpenProfile)
                return@Column
            }
            // finished and aborted games keep their last round; 0 only for a game that never reached its first turn
            Text(
                listOfNotNull(game.round.takeIf { it > 0 }?.let { "Round $it" }, game.currentPlayer, "you: ${mySeats.joinToString { it.nation }}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                running -> TurnLine(game, me)
                game.status == "finished" -> Text("Finished · winners: ${game.winners.joinToString().ifEmpty { "none" }}", color = MaterialTheme.colorScheme.primary)
                !running -> Text(game.status.replaceFirstChar { it.uppercase() } + (game.error?.let { ": $it" } ?: ""))
            }
        }
    }
}

/** A lobby game I hold no seat in; a tap opens its setup, where a free seat is taken. */
@Composable
private fun LobbyCard(game: GameInfoDto, enabled: Boolean, onOpenProfile: (String) -> kotlin.Unit, onOpen: () -> kotlin.Unit) {
    Card(Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onOpen) else Modifier)) {
        Column(Modifier.padding(12.dp)) {
            Text("${game.gameName} (${game.mapName}) · " + visibilityLabel(game), style = MaterialTheme.typography.titleSmall)
            Seats(game, onOpenProfile)
        }
    }
}

/** Requests, online and offline friends as cards, blocked users, and the floating "Add friend" button. */
@Composable
private fun ColumnScope.FriendsTab(
    friends: List<FriendDto>,
    enabled: Boolean,
    change: ((ServerApi) -> kotlin.Unit) -> kotlin.Unit,
    onOpenChat: (String) -> kotlin.Unit,
    onOpenProfile: (String) -> kotlin.Unit,
) {
    val presence by Social.presence.collectAsState()
    val blocked by Social.blocked.collectAsState()
    val unread by Social.unread.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<String?>(null) }
    // the friend to remove (false) or block (true), once confirmed: undoing either needs a new friend request
    var confirming by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val (accepted, requests) = friends.partition { it.status == "accepted" }
    val (online, offline) = accepted.partition { presence[it.username] ?: it.online }

    @Composable
    fun Friend(friend: FriendDto, isOnline: Boolean) = FriendCard(
        friend,
        isOnline,
        unread = friend.channel?.let { unread[it] } ?: 0,
        enabled = enabled,
        onChat = { onOpenChat(friend.username) },
        onRemove = { confirming = friend.username to false },
        onBlock = { confirming = friend.username to true },
        onReport = { reporting = friend.username },
        onProfile = { onOpenProfile(friend.username) },
    )

    Box(Modifier.weight(1f).fillMaxWidth()) {
        // the bottom padding keeps the last card's buttons clear of the floating button
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)) {
            if (requests.isNotEmpty()) item { Heading("Requests · ${requests.size}") }
            items(requests, key = { "p" + it.username }) { friend -> RequestCard(friend, enabled, change) { onOpenProfile(friend.username) } }
            if (online.isNotEmpty()) item { Heading("Online · ${online.size}") }
            items(online, key = { "f" + it.username }) { Friend(it, true) }
            if (offline.isNotEmpty()) item { Heading("Offline · ${offline.size}") }
            items(offline, key = { "f" + it.username }) { Friend(it, false) }
            if (accepted.isEmpty()) item { Text("No friends yet.", modifier = Modifier.padding(vertical = 8.dp)) }
            if (blocked.isNotEmpty()) item { Heading("Blocked · ${blocked.size}") }
            items(blocked.sorted(), key = { "b$it" }) { user ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    TextButton(onClick = { change { it.unblock(user) } }, enabled = enabled) { Text("Unblock") }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add friend") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }

    if (adding) {
        TextDialog("Add friend", "Username", "Send request", usernameKeyboard, onDismiss = { adding = false }) { who ->
            adding = false
            change { it.requestFriend(who) }
        }
    }
    confirming?.let { (user, block) ->
        AppDialog(
            title = if (block) "Block $user?" else "Remove $user?",
            subtitle = if (block) "This ends your friendship and hides their messages." else "To be friends again, one of you sends a new request.",
            onDismiss = { confirming = null },
            buttons = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
                Button(onClick = {
                    confirming = null
                    change { if (block) it.block(user) else it.removeFriend(user) }
                }) { Text(if (block) "Block" else "Remove") }
            },
        ) {}
    }
    reporting?.let { user ->
        TextDialog("Report $user", "Reason", "Send report", KeyboardOptions.Default, maxLength = 1000, onDismiss = { reporting = null }) { reason ->
            reporting = null
            change { it.report(user, reason) }
        }
    }
}

/** An incoming request (Accept or decline) or my outgoing one (withdraw); the avatar opens the profile. */
@Composable
private fun RequestCard(friend: FriendDto, enabled: Boolean, change: ((ServerApi) -> kotlin.Unit) -> kotlin.Unit, onProfile: () -> kotlin.Unit) {
    val incoming = friend.status == "incoming"
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(friend.username, OFFLINE_DOT, onProfile)
            NameAndStatus(friend.username, if (incoming) "wants to be your friend" else "request sent")
            if (incoming) Button(onClick = { change { it.acceptFriend(friend.username) } }, enabled = enabled) { Text("Accept") }
            IconButton(onClick = { change { it.removeFriend(friend.username) } }, enabled = enabled) {
                Icon(Icons.Filled.Close, contentDescription = if (incoming) "decline ${friend.username}" else "withdraw request to ${friend.username}")
            }
        }
    }
}

/** An accepted friend: status dot and line, unread badge, chat button and the ⋮ menu; a tap opens the chat, the avatar the profile. */
@Composable
private fun FriendCard(
    friend: FriendDto,
    online: Boolean,
    unread: Int,
    enabled: Boolean,
    onChat: () -> kotlin.Unit,
    onRemove: () -> kotlin.Unit,
    onBlock: () -> kotlin.Unit,
    onReport: () -> kotlin.Unit,
    onProfile: () -> kotlin.Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // the server sends activity only for online friends; live presence may have turned them offline since
    val activity = friend.activity?.takeIf { online }
    val status = when {
        activity != null -> activity
        online -> "online"
        else -> friend.lastSeen?.let { "last seen " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) } ?: "offline"
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onChat)) {
        Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(friend.username, if (activity != null) PLAYING_DOT else if (online) ONLINE_DOT else OFFLINE_DOT, onProfile)
            NameAndStatus(friend.username, status)
            if (unread > 0) Badge { Text("$unread", modifier = Modifier.semantics { contentDescription = "$unread unread" }) }
            IconButton(onClick = onChat) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "chat with ${friend.username}") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "more for ${friend.username}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Profile") }, onClick = { menu = false; onProfile() })
                    DropdownMenuItem(text = { Text("Remove friend") }, enabled = enabled, onClick = { menu = false; onRemove() })
                    DropdownMenuItem(text = { Text("Block") }, enabled = enabled, onClick = { menu = false; onBlock() })
                    DropdownMenuItem(text = { Text("Report") }, enabled = enabled, onClick = { menu = false; onReport() })
                }
            }
        }
    }
}

/** The initial on a colour picked from the name, with the status dot at the bottom right; a tap runs [onClick] (the profile). */
@Composable
private fun Avatar(username: String, dot: Color, onClick: () -> kotlin.Unit) {
    Box(Modifier.size(40.dp).clickable(onClickLabel = "profile of $username", onClick = onClick)) {
        Box(
            Modifier.fillMaxSize().background(AVATAR_COLORS[Math.floorMod(username.hashCode(), AVATAR_COLORS.size)], CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(username.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(13.dp)
                .border(2.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                .padding(2.dp).background(dot, CircleShape),
        )
    }
}

@Composable
private fun RowScope.NameAndStatus(name: String, status: String) {
    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One text field and a confirm button that sends the trimmed text. */
@Composable
private fun TextDialog(
    title: String,
    label: String,
    confirm: String,
    keyboard: KeyboardOptions,
    maxLength: Int = 100,
    onDismiss: () -> kotlin.Unit,
    onConfirm: (String) -> kotlin.Unit,
) {
    var text by remember { mutableStateOf("") }
    AppDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) }
        },
    ) {
        OutlinedTextField(text, { text = it.take(maxLength) }, label = { Text(label) }, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

private val ONLINE_DOT = Color(0xFF3EC46D)
private val PLAYING_DOT = Color(0xFFF0B429)
private val OFFLINE_DOT = Color(0xFF6B6873)
private val AVATAR_COLORS = listOf(Color(0xFF5B4B8A), Color(0xFF2F6F8F), Color(0xFF8A5A2F), Color(0xFF3B5A3B), Color(0xFF8F2F5A), Color(0xFF2F7F75))

/** Reads an invite ("gameId:code", as the copy button shares it) and the nation to take. */
@Composable
private fun JoinByCodeDialog(onDismiss: () -> kotlin.Unit, onJoin: (String, String, String) -> kotlin.Unit) {
    var invite by remember { mutableStateOf("") }
    var nation by remember { mutableStateOf("") }
    val parts = invite.trim().split(':')
    val valid = parts.size == 2 && parts.all { it.isNotBlank() } && nation.isNotBlank()
    AppDialog(
        title = "Join by code",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(enabled = valid, onClick = { onJoin(parts[0], parts[1], nation.trim()) }) { Text("Join") }
        },
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(invite, { invite = it }, label = { Text("Invite code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            // a link game is invisible until joined, so its open nations cannot be listed here
            OutlinedTextField(nation, { nation = it }, label = { Text("Nation (as the host told you)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
