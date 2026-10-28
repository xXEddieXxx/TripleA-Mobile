package org.triplea.mobile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.online.OnlineApi
import org.triplea.mobile.protocol.LeaderboardEntryDto

/** "Top players" (online mode, from the Online screen's account menu): rank, icon, name and rating; a tap opens the profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardScreen(onBack: () -> kotlin.Unit, onOpenProfile: (String) -> kotlin.Unit) {
    val serverUrl = AppSettings.state.collectAsState().value.serverUrl
    val scope = rememberCoroutineScope()
    val busyState = remember { mutableStateOf(false) }
    val busy by busyState
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<LeaderboardEntryDto>?>(null) }
    LaunchedEffect(serverUrl) {
        scope.runBusy(busyState, { error = it }, { OnlineApi.forUrl(serverUrl).leaderboard().entries }) { entries = it }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Top players") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = entries ?: return@Column
            if (list.isEmpty()) EmptyState(Icons.Filled.Leaderboard, "No one yet: $LEADERBOARD_MIN_GAMES rated games put a player on this list")
            LazyColumn {
                itemsIndexed(list, key = { _, entry -> entry.username }) { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenProfile(entry.username) }
                            .semantics(mergeDescendants = true) { contentDescription = "${index + 1}, ${entry.username}, ${entry.rating}" }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp))
                        ProfileIcon(entry.icon, entry.username, 36.dp)
                        Text(entry.username, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text("${entry.rating}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
