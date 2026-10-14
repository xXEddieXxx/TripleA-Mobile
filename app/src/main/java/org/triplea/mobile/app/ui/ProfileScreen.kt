package org.triplea.mobile.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import games.strategy.triplea.ResourceLoader
import java.nio.file.Path
import java.util.Locale
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.render.ImageCache
import org.triplea.mobile.online.client.ServerApi
import org.triplea.mobile.online.client.loadServerSave
import org.triplea.mobile.protocol.ProfileDto
import org.triplea.mobile.protocol.RecentGameDto
import org.triplea.mobile.protocol.StatsDto
import androidx.compose.material.icons.filled.SmartToy

/** The longest status the server takes, in code points. */
private const val MAX_STATUS = 60

/** The engine's flags and unit images, for profile icons (no map is loaded on the online screens). */
private val engineImages by lazy { ImageCache(ResourceLoader(listOfNotNull(MobileEngine.getEngineAssetsFolder().orElse(null)))) }

/** The image files of icon [id]: `flag:<Nation>` or `unit:<unit>` (drawn as the neutral nation's); none for `emblem:default`. */
private fun iconPaths(id: String): List<String> = when {
    id.startsWith("flag:") -> id.removePrefix("flag:").let { listOf("flags/${it}_large.png", "flags/$it.png") }
    id.startsWith("unit:") -> id.removePrefix("unit:").let { listOf("units/Neutral/$it.png", "units/Germans/$it.png") }
    else -> emptyList()
}

/** Profile icon [id], or the user's initial for the default emblem (and an image this device lacks). */
@Composable
internal fun ProfileIcon(id: String, username: String, size: Dp = 40.dp) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, id) {
        value = iconPaths(id).takeIf { it.isNotEmpty() }?.let { engineImages.load(id, it) }
    }
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = id, modifier = Modifier.size(size * 0.75f))
        } else {
            Text(
                username.take(1).uppercase(),
                style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * A user's profile (online mode): icon, status, stats and recent games, each with a replay button while the server
 * keeps its final save ([onOpenGame] shows it). On the user's own profile the pencil opens the icon and status editor.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(username: String, onBack: () -> kotlin.Unit, onOpenGame: () -> kotlin.Unit) {
    val serverUrl = AppSettings.state.collectAsState().value.serverUrl
    val own = username == AppSettings.tokens.tokens?.username
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var icon by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val icons = remember { context.assets.open("profile-icons.txt").bufferedReader().readLines().filter { it.isNotBlank() } }
    val previews = rememberMapPreviews()
    val snackbar = remember { SnackbarHostState() }

    fun <T> run(call: (ServerApi) -> T, done: (T) -> kotlin.Unit) =
        scope.runBusy(busyState, { error = it; info = null }, { call(OnlineApi.forUrl(serverUrl)) }, done = done)

    fun show(loaded: ProfileDto) {
        profile = loaded
        icon = loaded.icon
        status = loaded.status
    }

    LaunchedEffect(username, serverUrl) { run({ it.profile(username) }, ::show) }
    LaunchedEffect(info) { info?.let { snackbar.showSnackbar(it); info = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (own) "My profile" else username) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val shown = profile ?: return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(icon, shown.username, 88.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(shown.username, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (shown.status.isNotEmpty()) {
                        Text(shown.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (own) {
                    IconButton(onClick = { editing = !editing }) {
                        Icon(if (editing) Icons.Filled.Close else Icons.Filled.Edit, contentDescription = if (editing) "close editor" else "edit profile")
                    }
                }
            }
            if (own && editing) {
                OutlinedTextField(
                    status,
                    { if (it.codePointCount(0, it.length) <= MAX_STATUS) status = it },
                    label = { Text("Status") },
                    supportingText = { Text("${status.codePointCount(0, status.length)}/$MAX_STATUS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Heading("Icon")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    icons.forEach { id ->
                        val selected = id == icon
                        Box(
                            Modifier
                                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .clickable { icon = id }
                                .padding(4.dp)
                                .semantics { contentDescription = if (selected) "$id, chosen" else id },
                        ) { ProfileIcon(id, shown.username, 44.dp) }
                    }
                }
                Button(
                    onClick = { run({ it.updateProfile(icon, status.trim()) }) { show(it); editing = false; info = "Profile saved" } },
                    enabled = !busy && (icon != shown.icon || status.trim() != shown.status),
                ) { Text("Save") }
            }
            Stats(shown, previews)
            Heading("Recent games")
            if (shown.recentGames.isEmpty()) EmptyState(Icons.Filled.History, "No finished games yet")
            shown.recentGames.forEach { game ->
                RecentGameCard(game, previews[game.mapName], enabled = !busy) {
                    run({ server ->
                        val data = loadServerSave(server.downloadReplay(game.id)) ?: error("The replay could not be read")
                        GameController.startReplay(data)
                    }) { onOpenGame() }
                }
            }
        }
    }
}

/**
 * The games against other players (the rating: tiles with the win rate), then the games against the AI apart, so wins
 * against the AI cannot raise the win rate; then the favourite map and nation.
 */
@Composable
private fun Stats(profile: ProfileDto, previews: Map<String, Path?>) {
    Heading("Versus players")
    Outcomes(profile.stats)
    val ai = profile.aiStats
    if (ai.played > 0) {
        Heading("Versus AI")
        Outcomes(ai)
    }
    val s = profile.stats.takeIf { it.played > 0 } ?: ai
    if (s.mostPlayedMap != null || s.mostPlayedNation != null) {
        Heading("Most played")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.mostPlayedMap?.let { map ->
                MapThumb(previews[map], 36.dp)
                Text(mapTitle(map), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            s.mostPlayedNation?.let { nation ->
                NationFlag(AppServices.engineImages, nation, 24.dp, Modifier.padding(start = 8.dp))
                Text(displayName(nation), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

/** Played, won and win rate as tiles; the other outcomes as pills (only those that happened). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Outcomes(s: StatsDto) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(Icons.Filled.SportsEsports, s.played.toString(), "Played", Modifier.weight(1f))
        StatTile(Icons.Filled.EmojiEvents, s.won.toString(), "Won", Modifier.weight(1f))
        val rated = s.won + s.lost + s.resigned > 0
        StatTile(Icons.Filled.Percent, if (rated) String.format(Locale.ROOT, "%.0f %%", s.winRate * 100) else "–", "Win rate", Modifier.weight(1f))
    }
    val others = listOf(Triple(s.lost, "lost", Icons.Filled.Close), Triple(s.draws, "draws", Icons.Filled.Handshake),
        Triple(s.resigned, "resigned", Icons.Filled.Flag), Triple(s.abandoned, "abandoned", Icons.AutoMirrored.Filled.ExitToApp))
        .filter { it.first > 0 }
    if (others.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            others.forEach { (count, label, icon) -> StatusPill("$count $label", icon) }
        }
    }
}

/** The pill of a game result: an icon and the word, so the colour is never the only cue. */
@Composable
private fun ResultPill(result: String) {
    val icon: ImageVector = when (result) {
        "won" -> Icons.Filled.EmojiEvents
        "lost" -> Icons.Filled.Close
        "draw" -> Icons.Filled.Handshake
        "resigned" -> Icons.Filled.Flag
        else -> Icons.AutoMirrored.Filled.ExitToApp
    }
    StatusPill(result.replaceFirstChar { it.uppercase() }, icon, strong = result == "won", warning = result == "lost")
}

/** One ended game as a match history row: map, name, result, nations and when; a replay button while its final save is kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentGameCard(game: RecentGameDto, preview: Path?, enabled: Boolean, onReplay: () -> kotlin.Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MapThumb(preview, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(game.gameName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                mapTitle(game.mapName).takeUnless { it.equals(game.gameName, ignoreCase = true) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    ResultPill(game.result)
                    if (game.vsAi) StatusPill("vs AI", Icons.Filled.SmartToy)
                    game.nations.forEach { NationFlag(AppServices.engineImages, it, 18.dp) }
                    Text(
                        DateUtils.getRelativeTimeSpanString(game.endedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (game.replay) {
                IconButton(onClick = onReplay, enabled = enabled) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = "replay ${game.gameName}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
