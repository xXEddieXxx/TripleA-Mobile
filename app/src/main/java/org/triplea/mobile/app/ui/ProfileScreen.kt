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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.triplea.mobile.protocol.Achievements
import org.triplea.mobile.protocol.Badge
import org.triplea.mobile.protocol.IconUnlocks
import org.triplea.mobile.protocol.MapProgressDto
import org.triplea.mobile.protocol.ProfileDto
import org.triplea.mobile.protocol.RecentGameDto
import org.triplea.mobile.protocol.Scenario
import org.triplea.mobile.protocol.StatsDto

/** The longest status the server takes, in code points. */
private const val MAX_STATUS = 60
private val CAMEL_CASE_GAP = Regex("(?<=[a-z])(?=[A-Z])")

/** The engine's flags and unit images, for profile icons (no map is loaded on the online screens). */
private val engineImages by lazy { ImageCache(ResourceLoader(listOfNotNull(MobileEngine.getEngineAssetsFolder().orElse(null)))) }

/**
 * The image files of icon [id]: `flag:<Nation>`, `unit:<Nation>/<unit>` or `unit:<unit>` (drawn as the neutral
 * nation's); none for `emblem:default`.
 */
private fun iconPaths(id: String): List<String> = when {
    id.startsWith("flag:") -> id.removePrefix("flag:").let { listOf("flags/${it}_large.png", "flags/$it.png") }
    id.startsWith("unit:") && '/' in id -> listOf("units/${id.removePrefix("unit:")}.png")
    id.startsWith("unit:") -> id.removePrefix("unit:").let { listOf("units/Neutral/$it.png", "units/Germans/$it.png") }
    else -> emptyList()
}

/** The editor's group of icon [id]: the nation of a nation's unit, else "Flags" or "Units" ("" for the default emblem). */
private fun iconGroup(id: String) = when {
    id.startsWith("unit:") && '/' in id -> id.removePrefix("unit:").substringBefore('/')
    id.startsWith("flag:") -> "Flags"
    id.startsWith("unit:") -> "Units"
    else -> ""
}

/** How TalkBack reads icon [id]: "Germans armour", "flag Germans". */
internal fun iconLabel(id: String) = id.substringAfter(':').replace('/', ' ').let { if (id.startsWith("flag:")) "flag $it" else it }

/** The next locked tier of one nation's icons [ids], e.g. "Armour, fighter, transport: 1 win with Germans"; null when none is locked. */
private fun lockHint(ids: List<String>, winsByNation: Map<String, Int>): String? {
    val next = ids.mapNotNull(IconUnlocks::requirement).filter { (winsByNation[it.nation] ?: 0) < it.wins }.minByOrNull { it.wins } ?: return null
    val units = ids.filter { IconUnlocks.requirement(it) == next }.joinToString(", ") { it.substringAfter('/') }
    return units.replaceFirstChar { it.uppercase() } + ": " + next.text
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
            // null: an older server sends no counts, so nothing is locked here (the server still enforces)
            val wins = shown.winsByNation
            if (own && editing) {
                OutlinedTextField(
                    status,
                    { if (it.codePointCount(0, it.length) <= MAX_STATUS) status = it },
                    label = { Text("Status") },
                    supportingText = { Text("${status.codePointCount(0, status.length)}/$MAX_STATUS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // the nations' classic units first, each under its flag; then flags and the neutral units
                icons.groupBy(::iconGroup).entries.sortedBy { it.key == "Flags" || it.key == "Units" }.forEach { (group, ids) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group != "Flags" && group != "Units" && group.isNotEmpty()) NationFlag(AppServices.engineImages, group, 20.dp, Modifier.padding(top = 10.dp))
                        Heading(group.ifEmpty { "Icon" })
                    }
                    wins?.let { lockHint(ids, it) }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ids.forEach { id ->
                            val selected = id == icon
                            // the icon the user already has stays theirs (the server keeps it too); a tap on a locked one says what it needs
                            val locked = IconUnlocks.requirement(id)?.takeUnless { id == shown.icon || wins == null || IconUnlocks.isUnlocked(id, wins) }
                            Box(
                                Modifier
                                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .clickable { if (locked != null) info = "Locked: ${locked.text}" else icon = id }
                                    .padding(4.dp)
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = iconLabel(id) + when {
                                            selected -> ", chosen"
                                            locked != null -> ", locked: ${locked.text}"
                                            else -> ""
                                        }
                                    },
                            ) {
                                Box(Modifier.alpha(if (locked != null) 0.38f else 1f)) { ProfileIcon(id, shown.username, 44.dp) }
                                if (locked != null) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp).align(Alignment.BottomEnd))
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { run({ it.updateProfile(icon, status.trim()) }) { show(it); editing = false; info = "Profile saved" } },
                    enabled = !busy && (icon != shown.icon || status.trim() != shown.status),
                ) { Text("Save") }
            }
            Stats(shown, previews)
            AchievementsSection(shown) { info = it }
            MapsSection(shown, previews) { info = it }
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
    Outcomes(profile.stats, rating = profile)
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

/** The 10 general badges as tiles: earned ones in colour (a tap tells when), the others greyed with what they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementsSection(profile: ProfileDto, onInfo: (String) -> kotlin.Unit) {
    val earned = profile.achievements.associate { it.id to it.unlockedAt }
    Heading("Achievements (${Achievements.general.count { it.id in earned }} / ${Achievements.general.size})")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Achievements.general.forEach { BadgeTile(it, earned[it.id], it.name, onInfo) }
    }
}

/** One badge as a tile labelled [label]: earned at [at] in colour (a tap tells when), else greyed with what it needs. */
@Composable
private fun BadgeTile(badge: Badge, at: Long?, label: String, onInfo: (String) -> kotlin.Unit) {
    val context = LocalContext.current
    val date = at?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE) }
    val text = if (date != null) "${badge.name}, earned $date" else "${badge.name}, locked: ${badge.description}"
    Column(
        Modifier
            .width(104.dp)
            .clickable(onClickLabel = "Show details", role = Role.Button) { onInfo(if (date != null) "${badge.name}: earned $date" else "${badge.name}: ${badge.description}") }
            .alpha(if (at != null) 1f else 0.38f)
            .padding(4.dp)
            .semantics(mergeDescendants = true) { contentDescription = text },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // one spoken description per tile: the children are silent
        Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.MilitaryTech, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // "10 wins" needs no second line under "10 wins"
            if (at == null && badge.description != label) {
                Text(badge.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
    }
}

/**
 * The map badges, collapsed to one row "Maps · n / 60"; expanded, one card per scenario: the played ones first (most
 * wins first), then the unplayed ones greyed.
 */
@Composable
private fun MapsSection(profile: ProfileDto, previews: Map<String, Path?>, onInfo: (String) -> kotlin.Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val earned = profile.achievements.associate { it.id to it.unlockedAt }
    val progress = profile.maps.associateBy { it.key }
    val mapBadges = Achievements.SCENARIOS.flatMap(Achievements::mapBadges)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = if (open) "collapse" else "expand", role = Role.Button) { open = !open }
            .semantics { stateDescription = if (open) "expanded" else "collapsed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Heading("Maps · ${mapBadges.count { it.id in earned }} / ${mapBadges.size}", Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (!open) return
    // sortedBy is stable: within each group the catalog's order stays
    Achievements.SCENARIOS.sortedBy { progress[it.key]?.let { p -> -p.wins } ?: 1 }.forEach { scenario ->
        ScenarioCard(scenario, progress[scenario.key], earned, previews[scenario.map], onInfo)
    }
}

/**
 * A scenario's card: preview, name and wins, its three badges, and its catalog nations' flags (coloured: won with).
 * Unplayed ([progress] null) it is greyed with "Not played yet", every flag greyed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioCard(scenario: Scenario, progress: MapProgressDto?, earned: Map<String, Long>, preview: Path?, onInfo: (String) -> kotlin.Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MapThumb(preview, 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(scenario.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        progress?.let { "${it.wins} win${if (it.wins == 1) "" else "s"}" } ?: "Not played yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Achievements.mapBadges(scenario).forEach { BadgeTile(it, earned[it.id], it.name.removePrefix("${scenario.name} – "), onInfo) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                scenario.nations.forEach { nation ->
                    val name = displayName(nation).replace(CAMEL_CASE_GAP, " ")
                    val won = progress != null && nation in progress.wonWith
                    Box(
                        Modifier
                            .alpha(if (won) 1f else 0.38f)
                            .clearAndSetSemantics { contentDescription = "$name, ${if (won) "won with" else "not won yet"}" },
                    ) {
                        // the engine has flags for the World War II nations; others show their name
                        if (rememberFlag(AppServices.engineImages, nation).let { it.first == null && it.second }) {
                            Text(name, style = MaterialTheme.typography.labelSmall)
                        } else {
                            NationFlag(AppServices.engineImages, nation, 24.dp)
                        }
                    }
                }
            }
        }
    }
}

/** Played, won and win rate as tiles; the other outcomes as pills (only those that happened). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Outcomes(s: StatsDto, rating: ProfileDto? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // the Elo rating leads the games against players; the hint says it is still settling
        rating?.let {
            StatTile(Icons.Filled.Leaderboard, it.rating.toString(), "Rating", Modifier.weight(1f), hint = if (it.ratedGames < PROVISIONAL_GAMES) "provisional" else null)
        }
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
                    // the sign carries the meaning, the colour only repeats it
                    game.ratingChange?.let {
                        Text(ratingDelta(it), style = MaterialTheme.typography.labelLarge, color = if (it < 0) MaterialTheme.colorScheme.error else FREE_SEAT)
                    }
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
