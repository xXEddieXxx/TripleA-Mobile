package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.triplea.mobile.app.R
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppServices

@Composable
fun HomeScreen(
    onNewGame: () -> Unit,
    onLoadGames: () -> Unit,
    onMapBrowser: () -> Unit,
    onSettings: () -> Unit,
    onHowToPlay: () -> Unit,
    onAbout: () -> Unit,
) {
    var ready by remember { mutableStateOf(AppServices.contentInstalled) }
    var saves by remember { mutableStateOf<List<Path>>(emptyList()) }

    LaunchedEffect(Unit) {
        AppServices.installBundledContent()
        saves = withContext(Dispatchers.IO) { MobileEngine.listSaveGames() }
        ready = true
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painterResource(R.drawable.triplea_logo),
            contentDescription = "TripleA",
            modifier = Modifier.height(132.dp).padding(bottom = 8.dp),
        )
        Text("TripleA Mobile", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Turn based strategy on the go",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
        )
        if (!ready) {
            CircularProgressIndicator()
            Text("Preparing maps...", modifier = Modifier.padding(top = 12.dp))
            return@Column
        }
        Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) { Text("New game") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onLoadGames, modifier = Modifier.fillMaxWidth()) {
            Text(if (saves.isEmpty()) "Load game" else "Load game (${saves.size})")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onMapBrowser, modifier = Modifier.fillMaxWidth()) { Text("Map browser") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onHowToPlay, modifier = Modifier.fillMaxWidth()) { Text("How to play") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onAbout) { Text("About & licenses") }
    }
}

private fun formatModified(path: Path): String = runCatching {
    val millis = java.nio.file.Files.getLastModifiedTime(path).toMillis()
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
