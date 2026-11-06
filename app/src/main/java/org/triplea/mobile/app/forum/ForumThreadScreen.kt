package org.triplea.mobile.app.forum

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.maps.DownloadState
import org.triplea.mobile.app.maps.ListingState
import org.triplea.mobile.app.maps.MapDownloadManager
import org.triplea.mobile.app.maps.MapEntry
import org.triplea.mobile.app.ui.SaveEntry
import org.triplea.mobile.app.ui.readSaves
import org.triplea.mobile.forum.NodeBbClient
import org.triplea.mobile.forum.NodeBbClient.GameThread
import org.triplea.mobile.forum.NodeBbClient.SaveGame
import org.triplea.mobile.forum.NodeBbClient.ThreadPost

/** Lines of a post a quote takes over; a whole turn summary would bury the answer. */
private const val QUOTED_LINES = 12

/**
 * A forum thread: its posts newest page first (unseen ones marked), save games to open, a menu
 * per post (quote; edit and delete the player's own) and a reply box whose draft is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumThreadScreen(
    thread: GameThread,
    onOpenSave: (Path) -> Unit,
    onWatch: () -> Unit,
    onSignIn: () -> Unit,
    onStartGame: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val posts = remember { mutableStateListOf<ThreadPost>() }
    var firstPage by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var reply by remember { ForumThreadState.draft(thread).let { mutableStateOf(TextFieldValue(it, TextRange(it.length))) } }
    /** The player's post being edited, and the draft to return to afterwards. */
    var editing by remember { mutableStateOf<Pair<Int, TextFieldValue>?>(null) }
    var deleting by remember { mutableStateOf<ThreadPost?>(null) }
    var pickSave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var missingMap by remember { mutableStateOf<Pair<MapEntry?, String>?>(null) }
    /** The downloaded save waiting for its map, and whether it is to be watched. */
    var pendingSave by remember { mutableStateOf<Pair<Path, Boolean>?>(null) }
    var chooseSave by remember { mutableStateOf<SaveGame?>(null) }
    val listState = rememberLazyListState()
    val me by ForumAccount.player(thread.forum()).collectAsState()
    // the newest post seen before this visit; posts after it are marked new (never on a first visit)
    val seenBefore = remember { ForumThreadState.seen(thread) }
    var scrolledOnce by remember { mutableStateOf(false) }
    val firstNew by remember {
        derivedStateOf {
            posts.indexOfFirst { seenBefore != Instant.EPOCH && it.time().isAfter(seenBefore) && it.authorSlug() != me?.userslug() }
        }
    }

    fun client(): NodeBbClient = ForumAccount.client(thread.forum())
        ?: throw NodeBbClient.LoginNeededException("Signed out of ${thread.forum().label}")

    /** The error as a snackbar, without waiting for it; a lost login offers the way back in. */
    fun report(what: String, e: Throwable) = scope.launch {
        if (e is NodeBbClient.LoginNeededException) {
            val answer = snackbar.showSnackbar(
                "$what: the forum needs you to sign in again.",
                actionLabel = "Sign in",
                duration = SnackbarDuration.Long,
            )
            if (answer == SnackbarResult.ActionPerformed) onSignIn()
        } else {
            snackbar.showSnackbar("$what: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // the draft survives leaving the thread; not while a sent post is edited
    LaunchedEffect(reply.text, editing) {
        if (editing == null) ForumThreadState.saveDraft(thread, reply.text)
    }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            val page = withContext(Dispatchers.IO) { client().newestPage(thread) }
            posts.clear()
            posts.addAll(page.posts())
            firstPage = page.page()
            page.posts().lastOrNull()?.let { ForumThreadState.markSeen(thread, it.time()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // a refresh restarted the load: not an error
            throw e
        } catch (e: Exception) {
            if (posts.isEmpty()) error = e.message ?: e.javaClass.simpleName
            if (posts.isNotEmpty() || e is NodeBbClient.LoginNeededException) report("Could not load the thread", e)
        } finally {
            loading = false
        }
    }

    // first to the oldest unseen post, later (after a reply) to the newest; scrolling waits for the list
    LaunchedEffect(loading) {
        if (!loading && posts.isNotEmpty()) {
            val offset = if (firstPage > 1) 1 else 0
            listState.scrollToItem(offset + if (!scrolledOnce && firstNew >= 0) firstNew else posts.size - 1)
            scrolledOnce = true
        }
    }

    fun loadOlder() = scope.launch {
        runCatching { withContext(Dispatchers.IO) { client().page(thread, firstPage - 1) } }
            .onSuccess { page ->
                posts.addAll(0, page.posts())
                firstPage = page.page()
            }
            .onFailure { report("Could not load older posts", it) }
    }

    /** Watch: the save on the map without playing it. Join: the setup, to play (and roll) in it. */
    suspend fun proceed(file: Path, watch: Boolean) {
        if (!watch) return onOpenSave(file)
        busy = "Opening ${file.fileName}…"
        runCatching {
            withContext(Dispatchers.IO) {
                val data = MobileEngine.loadSaveGame(file).orElseThrow { IllegalStateException("The save game could not be read.") }
                GameController.watchSave(data, thread)
            }
        }.onSuccess { onWatch() }.onFailure { snackbar.showSnackbar("Could not open the save: ${it.message}") }
        busy = null
    }

    fun openSave(saveGame: SaveGame, watch: Boolean) = scope.launch {
        busy = "Downloading ${saveGame.fileName()}…"
        try {
            val file = withContext(Dispatchers.IO) { client().download(saveGame, MobileEngine.getSaveGamesFolder()) }
            ForumSaves.record(file, thread)
            val mapName = withContext(Dispatchers.IO) {
                MobileEngine.loadSaveGame(file).map { it.mapName }.orElse(null)
            } ?: throw IllegalStateException("The save game could not be read.")
            if (withContext(Dispatchers.IO) { isMapInstalled(mapName) }) {
                busy = null
                proceed(file, watch)
            } else {
                pendingSave = file to watch
                missingMap = listingEntry(mapName) to mapName
            }
        } catch (e: Exception) {
            report("Could not open the save", e)
        } finally {
            busy = null
        }
    }

    chooseSave?.let { save ->
        AlertDialog(
            onDismissRequest = { chooseSave = null },
            title = { Text(if (save.turn().isEmpty()) save.fileName() else save.turn()) },
            text = { Text("Watch the game as it stands, or join and play it. Joining rolls on the dice server with your email.") },
            confirmButton = {
                Button(onClick = { chooseSave = null; openSave(save, watch = false) }) { Text("Join and play") }
            },
            dismissButton = {
                TextButton(onClick = { chooseSave = null; openSave(save, watch = true) }) { Text("Watch") }
            },
        )
    }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = "Uploading picture…"
            try {
                val url = withContext(Dispatchers.IO) {
                    val client = client()
                    val (file, mime) = copyPictureToCache(context, uri)
                    try { client.upload(file, mime) } finally { Files.deleteIfExists(file) }
                }
                reply = reply.appendBlock("![]($url)")
            } catch (e: Exception) {
                report("Could not upload the picture", e)
            } finally {
                busy = null
            }
        }
    }

    // a save as the turn posts link it, so it gets an Open button in the thread
    fun uploadSave(save: Path) = scope.launch {
        busy = "Uploading ${save.fileName}…"
        try {
            val url = withContext(Dispatchers.IO) { client().upload(save, "application/octet-stream") }
            reply = reply.appendBlock("[${save.fileName.toString().filterNot { it == '[' || it == ']' }}]($url)")
        } catch (e: Exception) {
            report("Could not upload the save", e)
        } finally {
            busy = null
        }
    }

    if (pickSave) SaveGamePicker(onPick = { pickSave = false; uploadSave(it) }, onDismiss = { pickSave = false })

    fun send() = scope.launch {
        busy = "Sending…"
        try {
            val edited = editing
            val heldBack = withContext(Dispatchers.IO) {
                if (edited != null) false.also { client().editPost(edited.first, reply.text.trim()) }
                else client().reply(thread, reply.text.trim())
            }
            reply = edited?.second ?: TextFieldValue()
            editing = null
            if (heldBack) snackbar.showSnackbar("Sent. The forum holds it for a moderator.")
            reload++
        } catch (e: Exception) {
            report(if (editing != null) "Could not save the edit" else "Could not send", e)
        } finally {
            busy = null
        }
    }

    fun edit(post: ThreadPost) = scope.launch {
        busy = "Loading your post…"
        try {
            val raw = withContext(Dispatchers.IO) { client().rawPost(post.pid()) }
            editing = post.pid() to (editing?.second ?: reply)
            reply = TextFieldValue(raw, TextRange(raw.length))
        } catch (e: Exception) {
            report("Could not load the post", e)
        } finally {
            busy = null
        }
    }

    fun quote(post: ThreadPost) {
        val lines = post.text().lines()
        val quoted = lines.take(QUOTED_LINES).joinToString("\n") { "> $it".trimEnd() } + if (lines.size > QUOTED_LINES) "\n> …" else ""
        reply = reply.appendBlock("@${post.authorSlug()} wrote:\n$quoted\n\n")
    }

    deleting?.let { post ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete your post?") },
            text = { Text("The post disappears from the thread. A moderator can restore it.") },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    scope.launch {
                        busy = "Deleting…"
                        runCatching { withContext(Dispatchers.IO) { client().deletePost(post.pid()) } }
                            .onSuccess { reload++ }
                            .onFailure { report("Could not delete", it) }
                        busy = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    missingMap?.let { (entry, mapName) ->
        MissingMapDialog(
            mapName = mapName,
            entry = entry,
            onDownload = { chosen ->
                missingMap = null
                scope.launch {
                    busy = "Downloading map $mapName…"
                    MapDownloadManager.download(chosen)
                    val installed = waitForInstall(chosen)
                    busy = null
                    val save = pendingSave
                    if (installed && save != null) proceed(save.first, save.second) else snackbar.showSnackbar("The map download failed.")
                }
            },
            onDismiss = { missingMap = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(thread.title().ifEmpty { "Forum thread" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = {
                    // no save yet: the game has not begun, so Join sets it up (map, dice) and plays the first turn here
                    // older pages are not loaded: a thread longer than one page counts as begun
                    val started = firstPage > 1 || posts.any { it.saveGames().isNotEmpty() }
                    TextButton(
                        onClick = { if (started) reply = TextFieldValue(JOIN_TEXT, TextRange(JOIN_TEXT.length)) else onStartGame() },
                        // only once the thread loaded: no posts after an error says nothing about saves
                        enabled = editing == null && !loading && error == null,
                    ) { Text(if (started) "Join" else "Start game") }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PullToRefreshBox(
                isRefreshing = loading && posts.isNotEmpty(),
                onRefresh = { reload++ },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    loading && posts.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null -> Text("Could not load the thread: $error", Modifier.align(Alignment.Center).padding(24.dp))
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (firstPage > 1) {
                            item(key = "older") {
                                TextButton(onClick = { loadOlder() }, modifier = Modifier.fillMaxWidth()) { Text("Load older posts") }
                            }
                        }
                        items(posts.size) { index ->
                            val post = posts[index]
                            if (index == firstNew) NewPostsDivider(posts.size - firstNew)
                            val own = post.authorSlug() == me?.userslug()
                            PostCard(
                                post,
                                own = own,
                                onOpenSave = { chooseSave = it },
                                onQuote = { quote(post) },
                                onEdit = if (own) ({ edit(post) }) else null,
                                onDelete = if (own) ({ deleting = post }) else null,
                            )
                        }
                    }
                }
            }
            busy?.let {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column {
                    editing?.let { (_, draft) ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Editing your post", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { reply = draft; editing = null }) { Text("Cancel") }
                        }
                    }
                    val mentions = remember(posts.size, me) {
                        posts.map { it.authorSlug() }.filter { it.isNotEmpty() && it != me?.userslug() }.distinct()
                    }
                    ForumEditor(
                        value = reply,
                        onValueChange = { reply = it },
                        forum = thread.forum(),
                        label = "Reply",
                        enabled = busy == null,
                        onPicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onSaveGame = { pickSave = true },
                        mentions = mentions,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    ) {
                        IconButton(onClick = { send() }, enabled = busy == null && reply.text.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (editing != null) "save edit" else "send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private const val JOIN_TEXT = "I'd like to join this game. I can take over: "

@Composable
private fun NewPostsDivider(count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary)
        Text(
            if (count == 1) "1 new post" else "$count new posts",
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Each post on its own card, the player's own posts tinted, so neighbouring posts stay apart. */
@Composable
private fun PostCard(
    post: ThreadPost,
    own: Boolean,
    onOpenSave: (SaveGame) -> Unit,
    onQuote: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (own) scheme.secondaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (own) scheme.onSecondaryContainer else scheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post)
                Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp)) {
                    Text(post.author(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        DateUtils.getRelativeTimeSpanString(post.time().toEpochMilli()).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "post menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Quote") },
                            leadingIcon = { Icon(Icons.Default.FormatQuote, contentDescription = null) },
                            onClick = { menu = false; onQuote() },
                        )
                        onEdit?.let {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { menu = false; it() },
                            )
                        }
                        onDelete?.let {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menu = false; it() },
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(end = 8.dp)) {
                ForumPostBody(post)
                post.saveGames().forEach { save ->
                    // solid primary, full width: a tonal button vanished on the tinted cards of the player's own posts
                    Button(onClick = { onOpenSave(save) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            if (save.turn().isEmpty()) "Open ${save.fileName()}" else "Open save: ${save.turn()}",
                            Modifier.padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The forum's letter avatar, covered by the player's picture once it has loaded. */
@Composable
private fun Avatar(post: ThreadPost) {
    val picture by produceState<ImageBitmap?>(null, post.avatarUrl()) {
        value = post.avatarUrl().takeIf { it.isNotEmpty() }?.let { ForumPictures.load(URI.create(it)) }
    }
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(avatarColor(post.avatarColor())),
        contentAlignment = Alignment.Center,
    ) {
        Text(post.avatarText().ifEmpty { post.author().take(1) }, color = Color.White)
        picture?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** The app's own saves, newest first, with their game and round, to attach one to a reply. */
@Composable
private fun SaveGamePicker(onPick: (Path) -> Unit, onDismiss: () -> Unit) {
    val saves by produceState<List<SaveEntry>?>(null) { value = withContext(Dispatchers.IO) { readSaves() } }
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach a save game") },
        text = {
            val list = saves
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No save games yet. Saves from Load game, including imported ones, show here.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { save ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(save.path) }.padding(vertical = 10.dp)) {
                            Text(save.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(
                                save.info["round"]?.takeIf { it.isNotBlank() && it != "0" }?.let { "Round $it" },
                                save.info["player"]?.takeIf { it.isNotBlank() },
                                save.info["game"]?.takeIf { it.isNotBlank() },
                            )
                            if (details.isNotEmpty()) {
                                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(dateFormat.format(Instant.ofEpochMilli(save.modified)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MissingMapDialog(mapName: String, entry: MapEntry?, onDownload: (MapEntry) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map not installed") },
        text = {
            Text(
                if (entry != null) "This game is played on \"$mapName\". Download it now and open the save?"
                else "This game is played on \"$mapName\", which is not in the map list. Install it from the map browser.",
            )
        },
        confirmButton = {
            if (entry != null) Button(onClick = { onDownload(entry) }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun avatarColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF607D8B))

/** The engine's own lookup: by the name in each map's map.yml, not the folder name. */
private fun isMapInstalled(mapName: String): Boolean =
    InstalledMapsListing.searchAllMapsForMapName(mapName).isPresent

private suspend fun listingEntry(mapName: String): MapEntry? {
    val key = MapEntry.normalizeName(mapName)
    MapDownloadManager.refreshListing()
    val state = MapDownloadManager.listing.first { it is ListingState.Loaded || it is ListingState.Error }
    return (state as? ListingState.Loaded)?.maps?.firstOrNull { it.normalizedName == key }
}

/** True once the map is installed, false when its download failed. */
private suspend fun waitForInstall(entry: MapEntry): Boolean {
    val key = entry.normalizedName
    // the manager rescans the installed maps before it drops a finished download
    MapDownloadManager.downloads.first { key !in it || it[key] is DownloadState.Failed }
    return key in MapDownloadManager.installed.value
}
