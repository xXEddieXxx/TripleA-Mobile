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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import games.strategy.triplea.ResourceLoader
import java.util.Locale
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.app.render.ImageCache
import org.triplea.mobile.online.client.ServerApi
import org.triplea.mobile.online.client.loadServerSave
import org.triplea.mobile.protocol.ProfileDto
import org.triplea.mobile.protocol.RecentGameDto

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
            Text(username.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * A user's profile (online mode): icon, status, stats and recent games, each with "Replay" while the server keeps its
 * final save ([onOpenGame] shows it). On the user's own profile the icon and the status can be changed.
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
    val icons = remember { context.assets.open("profile-icons.txt").bufferedReader().readLines().filter { it.isNotBlank() } }

    fun <T> run(call: (ServerApi) -> T, done: (T) -> kotlin.Unit) =
        scope.runBusy(busyState, { error = it; info = null }, { call(OnlineApi.forUrl(serverUrl)) }, done = done)

    fun show(loaded: ProfileDto) {
        profile = loaded
        icon = loaded.icon
        status = loaded.status
    }

    LaunchedEffect(username, serverUrl) { run({ it.profile(username) }, ::show) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (own) "My profile" else username) },
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
            info?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            val shown = profile ?: return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(icon, shown.username, 64.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(shown.username, style = MaterialTheme.typography.titleLarge)
                    if (!own && shown.status.isNotEmpty()) Text(shown.status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (own) {
                OutlinedTextField(
                    status,
                    { if (it.codePointCount(0, it.length) <= MAX_STATUS) status = it },
                    label = { Text("Status") },
                    supportingText = { Text("${status.codePointCount(0, status.length)}/$MAX_STATUS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Icon", style = MaterialTheme.typography.titleSmall)
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
                    onClick = { run({ it.updateProfile(icon, status.trim()) }) { show(it); info = "Profile saved." } },
                    enabled = !busy && (icon != shown.icon || status.trim() != shown.status),
                ) { Text("Save") }
            }
            Stats(shown)
            Text("Recent games", style = MaterialTheme.typography.titleSmall)
            if (shown.recentGames.isEmpty()) Text("No finished games yet.")
            shown.recentGames.forEach { game ->
                RecentGameCard(game, enabled = !busy) {
                    run({ server ->
                        val data = loadServerSave(server.downloadReplay(game.id)) ?: error("The replay could not be read")
                        GameController.startReplay(data)
                    }) { onOpenGame() }
                }
            }
        }
    }
}

@Composable
private fun Stats(profile: ProfileDto) {
    val s = profile.stats
    Text("Stats", style = MaterialTheme.typography.titleSmall)
    Text("Played ${s.played} · won ${s.won} · lost ${s.lost} · draws ${s.draws} · resigned ${s.resigned} · abandoned ${s.abandoned}")
    if (s.played > 0) {
        Text("Win rate ${String.format(Locale.ROOT, "%.0f", s.winRate * 100)} %")
        Text("Most played: " + listOfNotNull(s.mostPlayedMap, s.mostPlayedNation).joinToString(" · "))
    }
}

/** One ended game: name, nations, result and when; "Replay" while its final save is kept. */
@Composable
private fun RecentGameCard(game: RecentGameDto, enabled: Boolean, onReplay: () -> kotlin.Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${game.gameName} (${game.mapName})", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${game.nations.joinToString()} · ${game.result} · " +
                        DateUtils.getRelativeTimeSpanString(game.endedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (game.replay) OutlinedButton(onClick = onReplay, enabled = enabled) { Text("Replay") }
        }
    }
}
