package org.triplea.mobile.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.online.client.ServerApi
import org.triplea.mobile.online.client.ServerException
import org.triplea.mobile.protocol.CreateGameRequest
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.SeatDto
import org.triplea.mobile.protocol.TimeControlDto
import org.triplea.mobile.protocol.UpdateGameRequest

private const val RELOAD_DELAY_MS = 300L

/** A LobbyUpdate reloads at once; this poll covers a dropped social socket, so nobody waits here for a start they missed. */
private const val POLL_MS = 10_000L

/** The mockup's colour of a free seat (readable on the light and the dark theme). */
private val FREE_SEAT_GREEN = Color(0xFF2E9E5B)

private val SEAT_PICKER_WIDTH = 128.dp

/** Who may see and join an online game: the server's visibility value and its label. */
private val visibilities = listOf("link" to "Invite code", "friends" to "Friends", "public" to "Public")

internal fun visibilityLabel(game: GameInfoDto) = visibilities.firstOrNull { it.first == game.visibility }?.second ?: game.visibility

/** A time control: its choices of base time (in [unitMs]) and of increment seconds; the server's limits. */
private class TimeControlChoice(val type: String, val label: String, val bases: List<Int>, val unit: String, val unitMs: Long, val increments: List<Int>)

private val timeControls = listOf(
    TimeControlChoice("blitz", "Blitz", listOf(3, 5, 10), "min", 60_000, listOf(0, 2, 5, 10, 30)),
    TimeControlChoice("rapid", "Rapid", listOf(15, 30, 60), "min", 60_000, listOf(0, 10, 30, 60)),
    TimeControlChoice("correspondence", "Correspondence", listOf(1, 2, 3, 5, 7, 14), "days per turn", 86_400_000, emptyList()),
)

/** The computer seats of the dropdown: server kind and label. */
private val computerSeats = listOf("EASY_AI" to "Easy AI", "FAST_AI" to "Fast AI", "HARD_AI" to "Hard AI", "DOES_NOTHING" to "Does nothing")

/** A seat as the nation rows and the game cards show it: the player (✓ when ready), "Free" or the AI. */
internal fun seatLabel(seat: SeatDto): String =
    when (seat.kind) {
        "OPEN" -> "Free"
        "HUMAN" -> seat.username.orEmpty() + if (seat.ready) " ✓" else ""
        else -> computerSeats.firstOrNull { it.first == seat.kind }?.second ?: seat.kind
    }

private fun clockLabel(control: TimeControlDto?): String =
    when {
        control == null -> "No clock"
        control.type == "correspondence" -> "Correspondence ${control.baseMs / 86_400_000} d"
        else -> control.type.replaceFirstChar { it.uppercase() } + " ${control.baseMs / 60_000}+${control.incrementMs / 1000}"
    }

/** Game [id] as the caller may see it: one of their games, else a lobby game they may join; null once it is gone. */
private fun fetch(server: ServerApi, id: String): GameInfoDto? =
    try {
        server.game(id)
    } catch (e: ServerException) {
        if (e.status != 404) throw e
        server.lobby().firstOrNull { it.id == id }
    }

/**
 * The setup of an online game in the lobby (debug builds): map, options, who plays each nation, Ready and
 * the game's chat. With [gameId] null the user picks a game first, which is created with defaults. Every
 * player on this screen switches into the game when it starts.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineSetupScreen(gameId: String?, onBack: () -> kotlin.Unit, onOpenProfile: (String) -> kotlin.Unit, onOpenGame: () -> kotlin.Unit) {
    val settings by AppSettings.state.collectAsState()
    val api = remember(settings.serverUrl) { runCatching { OnlineApi.forUrl(settings.serverUrl) } }
    val me = remember { AppSettings.tokens.tokens?.username.orEmpty() }
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(gameId) }
    var game by remember { mutableStateOf<GameInfoDto?>(null) }
    var gone by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // the last failed load, cleared by the next good one (apart from [problem], an action's error)
    var loadProblem by remember { mutableStateOf<String?>(null) }
    // counts the game states that actions applied, so a load that started before one is dropped
    var applied by remember { mutableIntStateOf(0) }
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    var starting by remember { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(gameId == null) }
    var installed by remember { mutableStateOf<List<MobileEngine.InstalledGame>?>(null) }
    var inviteFor by remember { mutableStateOf<String?>(null) }
    var showClock by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // the "Settings changed – press Ready again" note: my Ready was reset by someone else's change
    var wasReady by remember { mutableStateOf(false) }
    var unreadied by remember { mutableStateOf(false) }
    var readyReset by remember { mutableStateOf(false) }
    val lobbyVersion by Social.lobbyVersion.collectAsState()

    /**
     * Runs [call] off the main thread with the spinner on. Its answer, if any, is the game's new state (a new
     * game after a create or a map change); [done] runs on success.
     */
    fun run(done: () -> kotlin.Unit = {}, call: (ServerApi) -> GameInfoDto?) {
        val server = api.getOrElse { problem = "Invalid server URL: ${settings.serverUrl}"; return }
        scope.runBusy(busyState, { problem = it }, { call(server) }) { updated ->
            if (updated != null) {
                applied++
                game = updated
                id = updated.id
                choosing = false
            }
            done()
        }
    }

    fun open(running: GameInfoDto) {
        if (starting) return
        val server = api.getOrNull() ?: return
        starting = true
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { GameController.startOnline(server, running, me) } }
                .onSuccess { onOpenGame() }
                .onFailure { problem = it.message ?: it.javaClass.simpleName; starting = false }
        }
    }

    /** "New online game" and "Change map": the first nation is mine, the others Fast AI; a map change keeps the options. */
    fun create(pick: MobileEngine.InstalledGame) = run { server ->
        val data = MobileEngine.parseGame(pick.xmlPath).orElse(null) ?: throw IllegalStateException("Could not read ${pick.gameName}")
        val nations = data.playerList.players.map { it.name }
        val old = game
        val created = server.createGame(
            CreateGameRequest(
                pick.mapName,
                pick.gameName,
                nations.mapIndexed { i, nation -> if (i == 0) SeatDto(nation, "HUMAN", me) else SeatDto(nation, "FAST_AI") },
                visibility = old?.visibility ?: "friends",
                timeControl = old?.timeControl,
                aiTakesOver = old?.aiTakesOver ?: true,
                resignAfterMissedTurns = old?.resignAfterMissedTurns ?: 3,
                allowSpectators = old?.allowSpectators ?: true,
            ),
        )
        // nobody else joined the old game (the button is only offered then), so it goes; if that fails the
        // new game still is the one shown (the old one stays in the list and can be deleted there)
        old?.let { runCatching { server.deleteGame(it.id) } }
        created
    }

    fun setSeat(current: GameInfoDto, nation: String, kind: String, username: String? = null) = run { server ->
        server.putSeat(current.id, nation, kind, username)
    }

    fun patch(current: GameInfoDto, update: UpdateGameRequest) = run { it.patchGame(current.id, update) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { runCatching { MobileEngine.listInstalledGames() } }
        installed = list.getOrElse { problem = "Could not read the installed maps: ${it.message}"; emptyList() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // a key: taking a seat ("Take seat") starts the poll, losing the last one stops it
    val holdsSeat = game?.seats.orEmpty().any { it.username == me }
    // the start comes as a running game's LobbyUpdate, which does not bump lobbyVersion
    LaunchedEffect(id) {
        Social.gameUpdates.collect { if (it.id == id) { applied++; game = it } }
    }
    LaunchedEffect(id, lobbyVersion, api, holdsSeat) {
        val shown = id ?: return@LaunchedEffect
        val server = api.getOrNull() ?: return@LaunchedEffect
        Social.start(server)
        if (game != null) delay(RELOAD_DELAY_MS)
        // a load on every LobbyUpdate and resume; then only seat holders of a lobby game poll, while the screen
        // is resumed: they must not miss the start if the social socket dropped
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val before = applied
                try {
                    val fresh = withContext(Dispatchers.IO) { fetch(server, shown) }
                    loadProblem = null
                    // an action's answer landed meanwhile: this older answer must not replace it
                    if (applied == before) {
                        gone = fresh == null
                        game = fresh
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadProblem = e.message ?: e.javaClass.simpleName
                }
                val now = game
                // a failed first load (nothing shown yet) keeps trying
                if (gone || (now != null && (now.status != "lobby" || now.seats.none { it.username == me }))) break
                delay(POLL_MS)
            }
        }
    }
    val current = game
    val mine = current?.seats.orEmpty().filter { it.username == me }
    LaunchedEffect(current?.status) {
        if (current?.status == "running" && mine.isNotEmpty()) open(current)
    }

    if (choosing) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Text(if (id == null) "New online game" else "Change map", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = installed
            if (list == null || busy) {
                CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
            } else {
                Box(Modifier.weight(1f)) { GameChooser(list, ::create) }
            }
            OutlinedButton(onClick = { if (id == null) onBack() else choosing = false }, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current?.creator == me && current.status == "lobby") "New online game" else "Online game") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        // edge to edge: the keyboard would cover the chat field without imePadding
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            (problem ?: loadProblem)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
            if (current == null) {
                if (gone) Text("This game is gone or no longer open to you.") else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            val creator = current.creator == me
            val lobby = current.status == "lobby"
            val editable = creator && lobby && !busy
            MapCard(current, installed, canChange = editable && current.seats.none { it.username != null && it.username != me }) { choosing = true }
            when {
                lobby && !current.mapReady -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Map is loading on the server…", modifier = Modifier.padding(start = 8.dp))
                }
                current.status == "aborted" -> Text(current.error ?: "The game was aborted.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp))
                current.status == "running" && mine.isEmpty() -> Text("The game has started.", modifier = Modifier.padding(vertical = 6.dp))
                current.status == "running" && starting -> Text("Opening the game…", modifier = Modifier.padding(vertical = 6.dp))
                current.status == "running" -> Button(onClick = { open(current) }) { Text("Open game") }
                !lobby -> Text(current.status.replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(vertical = 6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                var visibilityMenu by remember { mutableStateOf(false) }
                Box {
                    OptionChip(visibilityLabel(current), Icons.Filled.Public, editable) { visibilityMenu = true }
                    DropdownMenu(expanded = visibilityMenu, onDismissRequest = { visibilityMenu = false }) {
                        visibilities.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                visibilityMenu = false
                                if (value != current.visibility) patch(current, UpdateGameRequest(visibility = value))
                            })
                        }
                    }
                }
                OptionChip(clockLabel(current.timeControl), Icons.Filled.Timer, editable) { showClock = true }
                if (creator) OptionChip("More", Icons.Filled.Settings, editable) { showMore = true }
            }
            current.inviteCode?.let { code -> InviteCodeRow("${current.id}:$code") }
            Text("Nations", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(current.seats, key = { it.nation }) { seat ->
                    val profile = listOfNotNull(seat.username?.takeIf { !busy }?.let { name -> "Profile of $name" to { onOpenProfile(name) } })
                    val options: List<Pair<String, () -> kotlin.Unit>> = profile + when {
                        !lobby || busy -> emptyList()
                        creator -> listOf<Pair<String, () -> kotlin.Unit>>(
                            me to { setSeat(current, seat.nation, "HUMAN", me) },
                            "Free" to { setSeat(current, seat.nation, "OPEN") },
                            "Invite friend…" to { inviteFor = seat.nation },
                        ) + computerSeats.map { (kind, label) -> label to { setSeat(current, seat.nation, kind) } }
                        seat.kind == "OPEN" -> listOf("Take seat" to { run { it.join(current.id, seat.nation, current.inviteCode) } })
                        seat.username == me -> listOf("Free" to { run { server -> server.leave(current.id, seat.nation); fetch(server, current.id) } })
                        else -> emptyList()
                    }
                    NationRow(seat.nation) {
                        PickerButton(
                            seatLabel(seat),
                            options,
                            color = when {
                                seat.username == me -> MaterialTheme.colorScheme.primary
                                seat.kind == "OPEN" -> FREE_SEAT_GREEN
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            width = SEAT_PICKER_WIDTH,
                        )
                    }
                }
            }
            val ready = mine.isNotEmpty() && mine.all { it.ready }
            // the server's rule: holders of human seats plus free seats; online is for playing each other
            val plannedHumans = current.seats.mapNotNull { it.username.takeIf { _ -> it.kind == "HUMAN" } }.distinct().size + current.seats.count { it.kind == "OPEN" }
            val tooFewHumans = lobby && plannedHumans < 2
            LaunchedEffect(ready) {
                // my Ready went away without my "Not ready": an option or seat change reset it
                readyReset = !ready && wasReady && !unreadied
                wasReady = ready
                unreadied = false
            }
            if (tooFewHumans && !ready) Text("At least 2 human players needed – play against the AI offline", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            else if (lobby && readyReset) Text("Settings changed – press Ready again", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            if (lobby) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (creator) {
                        OutlinedButton(onClick = { run(done = onBack) { it.deleteGame(current.id); null } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Delete") }
                    } else if (mine.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { run(done = onBack) { server -> mine.forEach { server.leave(current.id, it.nation) }; null } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Leave") }
                    }
                    Button(
                        onClick = {
                            unreadied = ready
                            run { it.ready(current.id, !ready) }
                        },
                        // un-ready always works
                        enabled = !busy && mine.isNotEmpty() && current.mapReady && (ready || !tooFewHumans),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (ready) "Not ready" else "Ready") }
                }
            }
            // the game's chat is for its seat holders; it continues in the game
            if (mine.isNotEmpty()) ChatBar("game:${current.id}", me, onOpenProfile)
        }
    }

    val shown = current ?: return
    inviteFor?.let { nation -> InviteDialog(nation, onDismiss = { inviteFor = null }) { friend -> inviteFor = null; setSeat(shown, nation, "HUMAN", friend) } }
    if (showClock) {
        ClockDialog(shown.timeControl, onDismiss = { showClock = false }) { control ->
            showClock = false
            patch(shown, if (control == null) UpdateGameRequest(noTimeControl = true) else UpdateGameRequest(timeControl = control))
        }
    }
    if (showMore) MoreDialog(shown, onDismiss = { showMore = false }) { showMore = false; patch(shown, it) }
}

@Composable
private fun OptionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> kotlin.Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/** Thumbnail (if the map is installed here and has a preview), game name, nations and "Change map". */
@Composable
private fun MapCard(game: GameInfoDto, installed: List<MobileEngine.InstalledGame>?, canChange: Boolean, onChange: () -> kotlin.Unit) {
    val map = remember(installed, game.mapName) { installed?.let { list -> groupByMap(list.filter { it.mapName == game.mapName }).firstOrNull() } }
    val preview = rememberPreview(map?.previewFile, 300)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PreviewImage(preview, Modifier.size(width = 72.dp, height = 48.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(game.gameName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${game.seats.size} nations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // the game is played with the map files of this device
                if (installed != null && map == null) {
                    Text("Not installed here: get it in the map browser", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (canChange) TextButton(onClick = onChange) { Text("Change map") }
        }
    }
}

/** What a link game's player shares: the game id and its code, as "Join by code" reads them. */
@Composable
private fun InviteCodeRow(code: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Invite code: $code", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TripleA invite", code))
        }) { Icon(Icons.Filled.ContentCopy, contentDescription = "copy invite code") }
    }
}

/** The collapsed chat: the last message and the number of new ones; a tap unfolds the whole chat below it. */
@Composable
private fun ChatBar(channel: String, me: String, onOpenProfile: (String) -> kotlin.Unit) {
    val chats by Social.chat.collectAsState()
    val blocked by Social.blocked.collectAsState()
    val messages = chats[channel].orEmpty().filter { it.username !in blocked }
    val last = messages.lastOrNull()
    var open by remember { mutableStateOf(false) }
    val unread = rememberUnread(channel, me, open)
    Surface(
        onClick = { open = !open },
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "Chat" + (last?.let { " · ${it.username}: ${it.text}" } ?: ""),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (unread > 0) Badge { Text("$unread") }
            Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (open) "close chat" else "open chat")
        }
    }
    // shortcut: unfolds in the screen instead of a bottom sheet, whose window got no keyboard insets (the
    // keyboard hid the text field); the nation list above shrinks while it is open
    if (open) ChatPanel(channel, me, Modifier.fillMaxWidth().height(CHAT_HEIGHT), onOpenProfile = onOpenProfile)
}

private val CHAT_HEIGHT = 240.dp

/** The creator's accepted friends; picking one invites them to [nation]. */
@Composable
private fun InviteDialog(nation: String, onDismiss: () -> kotlin.Unit, onInvite: (String) -> kotlin.Unit) {
    // fetched on every open, so friends accepted since the Online screen's last load are offered too
    val friends = rememberFriendChannels(refresh = true)
    val channels = friends.channels
    val error = friends.error
    val list = channels?.keys.orEmpty().sorted()
    AppDialog(title = "Invite a friend as $nation", onDismiss = onDismiss, buttons = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when {
                channels == null && error != null -> LoadFailed(error, friends.retry)
                channels == null -> Text("Loading…")
                list.isEmpty() -> Text("No friends yet: add them on the Friends tab.")
                else -> list.forEach { name ->
                    Text(name, modifier = Modifier.fillMaxWidth().clickable { onInvite(name) }.padding(vertical = 12.dp))
                }
            }
        }
    }
}

/** No clock, or blitz, rapid or correspondence with their times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClockDialog(current: TimeControlDto?, onDismiss: () -> kotlin.Unit, onApply: (TimeControlDto?) -> kotlin.Unit) {
    var control by remember { mutableStateOf(timeControls.firstOrNull { it.type == current?.type }) }
    var base by remember { mutableIntStateOf(control?.let { (current!!.baseMs / it.unitMs).toInt() } ?: 0) }
    var increment by remember { mutableIntStateOf(((current?.incrementMs ?: 0L) / 1000).toInt()) }
    AppDialog(
        title = "Clock",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onApply(control?.let { TimeControlDto(it.type, base * it.unitMs, increment * 1000L) }) }) { Text("Apply") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = control == null, onClick = { control = null }, label = { Text("None") })
                timeControls.forEach { choice ->
                    FilterChip(selected = control == choice, onClick = {
                        control = choice
                        base = choice.bases[1]
                        increment = 0
                    }, label = { Text(choice.label) })
                }
            }
            control?.let { choice ->
                Text("Time (${choice.unit})", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choice.bases.forEach { FilterChip(selected = base == it, onClick = { base = it }, label = { Text("$it") }) }
                }
                if (choice.increments.isNotEmpty()) {
                    Text("Seconds added per answer", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choice.increments.forEach { FilterChip(selected = increment == it, onClick = { increment = it }, label = { Text("+$it") }) }
                    }
                }
            }
        }
    }
}

/** AI takeover, resigning and spectators. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreDialog(game: GameInfoDto, onDismiss: () -> kotlin.Unit, onApply: (UpdateGameRequest) -> kotlin.Unit) {
    var aiTakesOver by remember { mutableStateOf(game.aiTakesOver) }
    var resignAfter by remember { mutableIntStateOf(game.resignAfterMissedTurns) }
    var allowSpectators by remember { mutableStateOf(game.allowSpectators) }
    AppDialog(
        title = "More options",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onApply(UpdateGameRequest(aiTakesOver = aiTakesOver, resignAfterMissedTurns = resignAfter, allowSpectators = allowSpectators)) }) { Text("Apply") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { aiTakesOver = !aiTakesOver }) {
                Checkbox(checked = aiTakesOver, onCheckedChange = { aiTakesOver = it })
                Text("AI takes over when a clock runs out")
            }
            Text("Resign after missed turns in a row", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 2, 3, 5).forEach {
                    FilterChip(selected = resignAfter == it, onClick = { resignAfter = it }, label = { Text(if (it == 0) "Never" else "$it") })
                }
            }
            // link games are seen by their players only, so nobody could watch them
            if (game.visibility != "link") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allowSpectators = !allowSpectators }) {
                    Checkbox(checked = allowSpectators, onCheckedChange = { allowSpectators = it })
                    Text("Allow spectators (they see the game a few saves late)")
                }
            }
        }
    }
}
