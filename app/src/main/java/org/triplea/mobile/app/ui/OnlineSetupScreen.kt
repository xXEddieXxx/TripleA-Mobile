package org.triplea.mobile.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.filled.Download
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

private val SEAT_PICKER_WIDTH = 148.dp
private val MAP_HERO_HEIGHT = 132.dp

/** Who may see and join an online game: the server's visibility value and its label. */
private val visibilities = listOf("link" to "Invite code", "friends" to "Friends", "public" to "Public")

internal fun visibilityLabel(game: GameInfoDto) = visibilities.firstOrNull { it.first == game.visibility }?.second ?: game.visibility

private fun visibilityIcon(value: String) = when (value) {
    "link" -> Icons.Filled.Key
    "friends" -> Icons.Filled.Group
    else -> Icons.Filled.Public
}

/** A time control: its choices of base time (in [unitMs]) and of increment seconds; the server's limits. */
private class TimeControlChoice(val type: String, val label: String, val icon: ImageVector, val bases: List<Int>, val unit: String, val unitMs: Long, val increments: List<Int>)

private val timeControls = listOf(
    TimeControlChoice("blitz", "Blitz", Icons.Filled.Bolt, listOf(3, 5, 10), "Minutes per turn", 60_000, listOf(0, 2, 5, 10, 30)),
    TimeControlChoice("rapid", "Rapid", Icons.Filled.Timer, listOf(15, 30, 60), "Minutes per turn", 60_000, listOf(0, 10, 30, 60)),
    TimeControlChoice("correspondence", "Daily", Icons.Filled.CalendarMonth, listOf(1, 2, 3, 5, 7, 14), "Days per turn", 86_400_000, emptyList()),
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
        control.type == "correspondence" -> "${control.baseMs / 86_400_000} d/turn"
        else -> control.type.replaceFirstChar { it.uppercase() } + " ${control.baseMs / 60_000} min/turn" +
            (if (control.incrementMs > 0) " +${control.incrementMs / 1000}s" else "")
    }

private fun clockIcon(control: TimeControlDto?) = control?.let { c -> timeControls.firstOrNull { it.type == c.type }?.icon } ?: Icons.Filled.TimerOff

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
fun OnlineSetupScreen(
    gameId: String?,
    onBack: () -> kotlin.Unit,
    onOpenProfile: (String) -> kotlin.Unit,
    onOpenGame: () -> kotlin.Unit,
    /** The map browser, to get a map this device lacks. */
    onOpenMaps: () -> kotlin.Unit,
) {
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
    // the "Ready again" pill: my Ready was reset by someone else's change
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
                rated = old?.rated ?: false,
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

    // read again on every return: the map browser may have just installed the game's map
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes) {
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
                if (gone) EmptyState(Icons.Filled.SearchOff, "Game no longer available", action = "Back", onAction = onBack)
                else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            val creator = current.creator == me
            val lobby = current.status == "lobby"
            val editable = creator && lobby && !busy
            MapHero(current, installed, canChange = editable && current.seats.none { it.username != null && it.username != me }, onGetMap = onOpenMaps) { choosing = true }
            when {
                lobby && !current.mapReady -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading map…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                current.status == "aborted" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(current.error ?: "Aborted", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
                current.status == "running" && mine.isEmpty() -> StatusPill("Started", Icons.Filled.PlayArrow, Modifier.padding(vertical = 6.dp))
                current.status == "running" && starting -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Opening…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                current.status == "running" -> Button(onClick = { open(current) }, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Open game", modifier = Modifier.padding(start = 8.dp))
                }
                !lobby -> StatusPill(current.status.replaceFirstChar { it.uppercase() }, null, Modifier.padding(vertical = 6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 4.dp)) {
                var visibilityMenu by remember { mutableStateOf(false) }
                Box {
                    OptionChip(visibilityLabel(current), visibilityIcon(current.visibility), editable) { visibilityMenu = true }
                    DropdownMenu(expanded = visibilityMenu, onDismissRequest = { visibilityMenu = false }) {
                        visibilities.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { Icon(visibilityIcon(value), contentDescription = null) },
                                onClick = {
                                    visibilityMenu = false
                                    if (value != current.visibility) patch(current, UpdateGameRequest(visibility = value))
                                },
                            )
                        }
                    }
                }
                OptionChip(clockLabel(current.timeControl), clockIcon(current.timeControl), editable) { showClock = true }
                // rated needs a clock (else a losing player could stall the game); joiners see it read-only
                OptionChip(if (current.rated) "Rated" else "Unrated", Icons.Filled.Leaderboard, editable && (current.rated || current.timeControl != null)) {
                    patch(current, UpdateGameRequest(rated = !current.rated))
                }
                if (creator) OptionChip("More", Icons.Filled.Tune, editable) { showMore = true }
            }
            if (creator && lobby && current.timeControl == null) {
                Text("Needs a time control", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            current.inviteCode?.let { code -> InviteCodeRow("${current.id}:$code") }
            val taken = current.seats.count { it.kind != "OPEN" }
            Heading("Nations · $taken/${current.seats.size} taken")
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
                    NationRow(seat.nation) { SeatPicker(seat, me, showReady = lobby, options) }
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
            if (tooFewHumans && !ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill("Needs 2 players", Icons.Filled.Groups, warning = true)
                    InfoButton("Players", "At least 2 human players are needed: free a seat or invite a friend. To play against the AI only, start an offline game.")
                }
            } else if (lobby && readyReset) {
                StatusPill("Ready again", Icons.Filled.Refresh, Modifier.padding(top = 6.dp), warning = true)
            }
            if (lobby) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (creator) {
                        OutlinedButton(onClick = { run(done = onBack) { it.deleteGame(current.id); null } }, enabled = !busy) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Delete", modifier = Modifier.padding(start = 6.dp))
                        }
                    } else if (mine.isNotEmpty()) {
                        OutlinedButton(onClick = { run(done = onBack) { server -> mine.forEach { server.leave(current.id, it.nation) }; null } }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Leave", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    val onReady = {
                        unreadied = ready
                        run { it.ready(current.id, !ready) }
                    }
                    // un-ready always works
                    // the game is played with this device's map files: without them the start would leave me stuck
                    val mapHere = installed?.any { sameMap(it.mapName, current.mapName) } == true
                    val canReady = !busy && mine.isNotEmpty() && current.mapReady && (ready || (!tooFewHumans && mapHere))
                    if (ready) {
                        // ticked while ready; a tap takes it back
                        FilledTonalButton(onClick = onReady, enabled = canReady, modifier = Modifier.weight(1f).semantics { stateDescription = "ready, tap for not ready" }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = FREE_SEAT, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Ready", modifier = Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Button(onClick = onReady, enabled = canReady, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Ready", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
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
            patch(shown, if (control == null) UpdateGameRequest(noTimeControl = true, rated = false.takeIf { shown.rated }) else UpdateGameRequest(timeControl = control))
        }
    }
    if (showMore) MoreDialog(shown, onDismiss = { showMore = false }) { showMore = false; patch(shown, it) }
}

@Composable
private fun OptionChip(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> kotlin.Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/** The map as the screen's banner: its preview (if installed here), the game name and nation count, and "Change". */
@Composable
private fun MapHero(game: GameInfoDto, installed: List<MobileEngine.InstalledGame>?, canChange: Boolean, onGetMap: () -> kotlin.Unit, onChange: () -> kotlin.Unit) {
    val map = remember(installed, game.mapName) { installed?.let { list -> groupByMap(list.filter { sameMap(it.mapName, game.mapName) }).firstOrNull() } }
    val preview = rememberPreview(map?.previewFile, 720)
    Box(Modifier.fillMaxWidth().height(MAP_HERO_HEIGHT).clip(RoundedCornerShape(12.dp))) {
        PreviewImage(preview, Modifier.fillMaxSize())
        // a scrim under the name: readable on any map
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        // the game is played with the map files of this device
        if (installed != null && map == null) {
            StatusPill(
                "Not installed · Get map",
                Icons.Filled.Download,
                Modifier.align(Alignment.TopStart).padding(8.dp).clickable(onClickLabel = "open the map browser", onClick = onGetMap),
                warning = true,
            )
        }
        if (canChange) {
            FilledTonalButton(
                onClick = onChange,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Change", modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                game.gameName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusPill("${game.seats.size}", Icons.Filled.Flag, Modifier.padding(start = 8.dp).clearAndSetSemantics { contentDescription = "${game.seats.size} nations" })
        }
    }
}

/**
 * Who plays a nation, as in the friends list: the player's initial and name (ticked when [showReady] and ready),
 * a robot and the AI's name, or a green "Free". A tap opens [options]; without options it only shows.
 */
@Composable
private fun SeatPicker(seat: SeatDto, me: String, showReady: Boolean, options: List<Pair<String, () -> kotlin.Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    val name = seat.username
    val open = seat.kind == "OPEN"
    val ready = showReady && seat.ready
    val label = when {
        open -> "Free"
        seat.kind == "HUMAN" -> name.orEmpty()
        else -> seatLabel(seat)
    }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            shape = RoundedCornerShape(50),
            color = if (open) FREE_SEAT.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(SEAT_PICKER_WIDTH).heightIn(min = 48.dp).padding(vertical = 4.dp)
                .semantics { contentDescription = label + if (ready) ", ready" else "" },
        ) {
            Row(Modifier.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    open -> Box(Modifier.size(26.dp).background(FREE_SEAT, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    seat.kind == "HUMAN" && name != null -> Initial(name, 26.dp)
                    else -> Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
                    }
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        open -> FREE_SEAT
                        name == me -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                when {
                    ready -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = FREE_SEAT, modifier = Modifier.size(18.dp))
                    options.isNotEmpty() -> Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; action() })
            }
        }
    }
}

/** What a link game's player shares: the game id and its code, as "Join by code" reads them. */
@Composable
private fun InviteCodeRow(code: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = "invite code $code" },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
            }
        }
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                last?.let { "${it.username}: ${it.text}" } ?: "Chat",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (last == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (unread > 0) Badge(Modifier.padding(end = 6.dp)) { Text("$unread") }
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
    AppDialog(title = "Invite to ${displayName(nation)}", onDismiss = onDismiss, buttons = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when {
                channels == null && error != null -> LoadFailed(error, friends.retry)
                channels == null -> CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                list.isEmpty() -> EmptyState(Icons.Filled.PersonAdd, "No friends yet")
                else -> list.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "invite $name") { onInvite(name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Initial(name, 36.dp)
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** No clock, or blitz, rapid or daily (correspondence) with their times. */
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
                FilterChip(selected = control == null, onClick = { control = null }, label = { Text("None") }, leadingIcon = { ChipIcon(Icons.Filled.TimerOff) })
                timeControls.forEach { choice ->
                    FilterChip(selected = control == choice, onClick = {
                        control = choice
                        base = choice.bases[1]
                        increment = 0
                    }, label = { Text(choice.label) }, leadingIcon = { ChipIcon(choice.icon) })
                }
            }
            control?.let { choice ->
                Heading(choice.unit)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choice.bases.forEach { FilterChip(selected = base == it, onClick = { base = it }, label = { Text("$it") }) }
                }
                if (choice.increments.isNotEmpty()) {
                    Heading("Seconds per move")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        choice.increments.forEach { FilterChip(selected = increment == it, onClick = { increment = it }, label = { Text("+$it") }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipIcon(icon: ImageVector) = Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))

/** AI takeover, resigning and spectators. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreDialog(game: GameInfoDto, onDismiss: () -> kotlin.Unit, onApply: (UpdateGameRequest) -> kotlin.Unit) {
    var aiTakesOver by remember { mutableStateOf(game.aiTakesOver) }
    var resignAfter by remember { mutableIntStateOf(game.resignAfterMissedTurns) }
    var allowSpectators by remember { mutableStateOf(game.allowSpectators) }
    // link games are seen by their players only, so nobody could watch them
    val spectatorsPossible = game.visibility != "link"
    AppDialog(
        title = "Options",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { onApply(UpdateGameRequest(aiTakesOver = aiTakesOver, resignAfterMissedTurns = resignAfter, allowSpectators = allowSpectators)) }) { Text("Apply") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("What these do", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                InfoButton(
                    "Options",
                    "AI on timeout: the AI plays a turn when a player's clock runs out. " +
                        "Resign: a player who misses that many turns in a row leaves the game. " +
                        "Spectators see the game a few saves late (not in invite-code games).",
                )
            }
            SwitchRow("AI on timeout", Icons.Filled.SmartToy, aiTakesOver) { aiTakesOver = it }
            Heading("Resign after missed turns")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 2, 3, 5).forEach {
                    FilterChip(selected = resignAfter == it, onClick = { resignAfter = it }, label = { Text(if (it == 0) "Never" else "$it") })
                }
            }
            if (spectatorsPossible) SwitchRow("Spectators", Icons.Filled.Visibility, allowSpectators) { allowSpectators = it }
        }
    }
}

/** An icon, a short label and a switch; the whole row toggles. */
@Composable
private fun SwitchRow(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> kotlin.Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
