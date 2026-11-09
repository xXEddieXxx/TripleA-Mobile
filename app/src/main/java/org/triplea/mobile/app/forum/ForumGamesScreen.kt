package org.triplea.mobile.app.forum

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import org.triplea.mobile.forum.Forum
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.forum.NodeBbClient.GameThread
import org.triplea.mobile.forum.GameSearch
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField

/** The player's forum games ("your turn" first) and the Play by Forum category to browse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumGamesScreen(
    onSignIn: () -> Unit,
    onOpenThread: (GameThread) -> Unit,
    onNewGame: () -> Unit,
    onBack: () -> Unit,
) {
    val forum by ForumAccount.selected.collectAsState()
    val player by ForumAccount.player(forum).collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var games by remember { mutableStateOf<List<GameThread>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var includeInactive by rememberSaveable { mutableStateOf(false) }
    var showStopped by rememberSaveable { mutableStateOf(false) }
    /** My games only: true shows the games waiting for the player, false those waiting for an opponent. */
    var turnFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) ForumHelpDialog(onDismiss = { showHelp = false })

    // "your turn" notifications need the permission from Android 13; asked once signed in
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    LaunchedEffect(player != null) {
        if (player != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(forum, tab, reload, player) {
        error = null
        games = null
        if (!ForumAccount.hasSession(forum)) return@LaunchedEffect
        try {
            val me = player ?: ForumAccount.verify(forum) ?: return@LaunchedEffect
            val client = ForumAccount.client(forum) ?: return@LaunchedEffect
            suspend fun load() = withContext(Dispatchers.IO) {
                if (tab == 0) client.threadsOf(me).sortedByDescending { it.waitsFor(me) } else client.browse()
            }
            games = load()
            // while the list is shown: once a minute, quietly (no spinner; a failed try keeps the list)
            while (true) {
                delay(TimeUnit.MINUTES.toMillis(1))
                runCatching { load() }.onSuccess { games = it }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // a tab switch or refresh restarted the load: not an error
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Play by Forum") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "help") }
                    if (player != null) {
                        IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "refresh") }
                        IconButton(onClick = { ForumAccount.signOut(forum) }) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "sign out")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (player != null) {
                ExtendedFloatingActionButton(
                    onClick = onNewGame,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New game") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // each forum with its own sign-in and games; a game always posts to its own forum
            if (ForumAccount.shown.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    ForumAccount.shown.forEachIndexed { index, site ->
                        SegmentedButton(
                            selected = site == forum,
                            onClick = { ForumAccount.select(site) },
                            shape = SegmentedButtonDefaults.itemShape(index, ForumAccount.shown.size),
                        ) { Text(site.label, maxLines = 1) }
                    }
                }
            }
            if (!ForumAccount.hasSession(forum)) {
                SignInCard(forum, onSignIn)
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My games") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Browse") })
            }
            val all = games
            // games the player stopped following leave My games; the "Stopped" chip shows only them
            val stoppedChanges by ForumThreadState.stoppedChanges.collectAsState()
            val list = remember(all, query, includeInactive, tab, showStopped, stoppedChanges) {
                all?.let { GameSearch.matching(it, query, includeInactive, Instant.now()) }
                    ?.filter { tab != 0 || ForumThreadState.isStopped(it) == showStopped }
            }
            val stoppedCount = remember(all, tab, stoppedChanges) {
                if (tab == 0) all?.count { ForumThreadState.isStopped(it) } ?: 0 else 0
            }
            // the last stopped game followed again: back to the followed ones
            LaunchedEffect(stoppedCount) { if (stoppedCount == 0 && all != null) showStopped = false }
            // the search over the whole width, its filter on a line of its own below
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                placeholder = { Text("Search games, players, nations") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "clear search") }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
            )
            // "your turn" is a guess about the player's own games only: those two filters on My games alone
            val me = player.takeIf { tab == 0 }
            val shown = remember(list, turnFilter, me) {
                list?.filter { game -> me == null || turnFilter == null || (!game.onlyWatched() && game.waitsFor(me) == turnFilter) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (me != null) {
                    // one chip, three states: all games, then "Your turn", then "Waiting", then all again
                    val waiting = turnFilter == false
                    TurnChip(
                        if (waiting) "Waiting" else "Your turn",
                        list?.count { !it.onlyWatched() && it.waitsFor(me) != waiting },
                        selected = turnFilter != null,
                    ) {
                        turnFilter = when (turnFilter) {
                            null -> true
                            true -> false
                            false -> null
                        }
                    }
                }
                FilterChip(
                    selected = includeInactive,
                    onClick = { includeInactive = !includeInactive },
                    label = { Text("Show inactive") },
                    leadingIcon = {
                        Icon(
                            if (includeInactive) Icons.Default.Check else Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
                if (stoppedCount > 0 || showStopped) {
                    FilterChip(
                        selected = showStopped,
                        onClick = { showStopped = !showStopped },
                        label = { Text("Stopped · $stoppedCount") },
                        leadingIcon = {
                            Icon(
                                if (showStopped) Icons.Default.Check else Icons.Default.NotificationsOff,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
            when {
                error != null -> Message("Could not reach the forum: $error")
                all == null || list == null || shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                all.isEmpty() -> Message(if (tab == 0) "No forum games yet. Browse to join one, or start a new game." else "No games found.")
                list.isEmpty() -> Message(
                    if (showStopped) "No stopped game matches."
                    else if (query.isBlank() && stoppedCount > 0) "No game you follow here. \"Stopped\" lists the games you stopped following."
                    else if (query.isBlank()) "Only inactive games (no post for ${GameSearch.INACTIVE_AFTER.toDays()} days). Tap \"Show inactive\" to see them."
                    else "No game matches \"$query\".",
                )
                shown.isEmpty() -> Message(if (turnFilter == true) "No game waits for your move." else "No game waits for an opponent.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.topicId() }) { game ->
                        // the guess (someone else posted last) only means something in the player's own games
                        GameRow(
                            game,
                            yourTurn = tab == 0 && player?.let { game.waitsFor(it) } == true,
                            // the forum's flag (read in a browser) or the app's own, never for the player's own last post
                            hasNew = game.unread() || (ForumThreadState.hasNew(game) && game.lastPosterSlug() != player?.userslug()),
                            onClick = { onOpenThread(game) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** The turn filter of My games with how many games it holds, eg "Your turn · 2". */
@Composable
private fun TurnChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (count == null) label else "$label · $count") },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

@Composable
private fun SignInCard(forum: Forum, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in to ${forum.uri().host}", style = MaterialTheme.typography.titleMedium)
                Text("Your games, turns and replies stay in the app. You sign in once on the forum's own page.")
                Button(onClick = onSignIn, modifier = Modifier.align(Alignment.End)) { Text("Sign in") }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text) }
}

@Composable
private fun GameRow(game: GameThread, yourTurn: Boolean, hasNew: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                game.title(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (hasNew) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                game.lastTurn().ifEmpty { null },
                game.lastPosterSlug().ifEmpty { null }?.let { "last post by $it" },
                DateUtils.getRelativeTimeSpanString(game.lastPost().toEpochMilli()).toString(),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            when {
                yourTurn -> AssistChip(onClick = onClick, label = { Text("Your turn") })
                game.onlyWatched() -> Text("Watching", style = MaterialTheme.typography.labelMedium)
            }
            if (hasNew) {
                Text("New posts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
