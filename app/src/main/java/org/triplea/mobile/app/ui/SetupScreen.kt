package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import org.triplea.mobile.app.AppServices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GameData
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.PlayerKind
import org.triplea.mobile.app.game.GameController

/**
 * Game selection and player assignment. With [saveFile] set, the save game is loaded instead of
 * offering the installed games.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(saveFile: Path?, onGameStarted: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<MobileEngine.InstalledGame>>(emptyList()) }
    var gameData by remember { mutableStateOf<GameData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val kinds = remember { mutableStateMapOf<String, PlayerKind>() }
    var showOptions by remember { mutableStateOf(false) }

    LaunchedEffect(saveFile) {
        loading = true
        try {
            if (saveFile != null) {
                gameData = withContext(Dispatchers.IO) {
                    MobileEngine.loadSaveGame(saveFile).orElse(null)
                }
                if (gameData == null) error = "Could not load the save game."
            } else {
                games = withContext(Dispatchers.IO) {
                    runCatching { MobileEngine.listInstalledGames() }
                        .onFailure { error = "Could not read the installed maps: ${it.message}" }
                        .getOrDefault(emptyList())
                }
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(gameData) {
        val data = gameData ?: return@LaunchedEffect
        kinds.clear()
        // the Hard (Pro) AI needs minutes per turn on big maps on a phone; start those with the Fast AI
        val bigMap = data.map.territories.size > 120
        val defaultAi = if (bigMap) PlayerKind.AI_FAST else PlayerKind.AI_HARD
        // a save remembers who played each nation (the engine records it at game start)
        val remembered = if (saveFile != null) {
            data.playerList.players.associate { it.name to PlayerKind.fromWhoAmI(it.whoAmI).orElse(null) }
        } else {
            emptyMap()
        }
        val anyRemembered = remembered.values.any { it != null }
        data.playerList.players.forEachIndexed { index, player ->
            kinds[player.name] = remembered[player.name]
                ?: if (index == 0 && !anyRemembered) PlayerKind.HUMAN else defaultAi
        }
    }

    val optionsData = gameData
    if (showOptions && optionsData != null) {
        GameOptionsScreen(data = optionsData, onBack = { showOptions = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(
            if (gameData == null) "Choose a game" else (gameData?.gameName ?: "Players"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (gameData == null) {
            Box(Modifier.weight(1f)) {
                GameChooser(games) { game ->
                    loading = true
                    error = null
                    scope.launch {
                        val parsed = withContext(Dispatchers.IO) {
                            runCatching { MobileEngine.parseGame(game.xmlPath).orElse(null) }.getOrElse { null }
                        }
                        loading = false
                        if (parsed == null) error = "Could not read ${game.gameName}" else gameData = parsed
                    }
                }
            }
        } else {
            val data = gameData!!
            val alliances = remember(data) {
                runCatching {
                    data.allianceTracker.alliances.sorted().map { name -> name to data.allianceTracker.getPlayersInAlliance(name).map { it.name } }
                }.getOrDefault(emptyList())
            }
            // one line of chips: everyone or an alliance at once
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                QuickPick("All") { kind -> data.playerList.players.forEach { kinds[it.name] = kind } }
                alliances.forEach { (name, members) -> QuickPick(name) { kind -> members.forEach { kinds[it] = kind } } }
            }
            // the nations grouped by their alliance, so it is clear who fights with whom
            val groups = remember(data, alliances) {
                val placed = HashSet<String>()
                val result = ArrayList<Pair<String, List<games.strategy.engine.data.GamePlayer>>>()
                alliances.forEach { (name, members) ->
                    val players = data.playerList.players.filter { it.name in members && placed.add(it.name) }
                    if (players.isNotEmpty()) result += name to players
                }
                val rest = data.playerList.players.filter { it.name !in placed }
                if (rest.isNotEmpty()) result += (if (result.isEmpty()) "" else "No alliance") to rest
                result
            }
            LazyColumn(Modifier.weight(1f)) {
                groups.forEach { (alliance, players) ->
                    if (alliance.isNotBlank()) {
                        item(key = "alliance-$alliance") {
                            Text(
                                alliance,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                    }
                items(players, key = { it.name }) { player ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NationFlag(player.name)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            player.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        PlayerKindPicker(
                            kind = kinds[player.name] ?: PlayerKind.AI_HARD,
                            onChange = { kinds[player.name] = it },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (saveFile == null) gameData = null else onBack() }) { Text("Back") }
                TextButton(onClick = { showOptions = true }) { Text("Options & bids") }
                Button(
                    onClick = {
                        loading = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { GameController.startSession(data, kinds.toMap()) }
                            }
                            loading = false
                            ok.onSuccess { onGameStarted() }
                                .onFailure { error = it.message ?: it.toString() }
                        }
                    },
                ) { Text("Start") }
            }
        }
        if (gameData == null && !loading) {
            OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
    }
}

/** A chip of the quick selection: tap for "set everyone / this alliance to ...". */
@Composable
private fun QuickPick(label: String, onPick: (PlayerKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(" \u25be", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayerKind.values().forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onPick(option) })
            }
        }
    }
}

private val PICKER_WIDTH = 104.dp

/** The nation's flag from the engine assets or the map, or a blank placeholder. */
@Composable
private fun NationFlag(name: String) {
    val bitmap = remember(name) {
        runCatching {
            val candidates = listOf(
                AppServices.engineAssetsDir.resolve("flags").resolve("$name.png"),
                AppServices.engineAssetsDir.resolve("flags").resolve("${name}_small.png"),
            )
            candidates.firstOrNull { java.nio.file.Files.exists(it) }?.let { android.graphics.BitmapFactory.decodeFile(it.toString()) }
        }.getOrNull()
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = name, modifier = Modifier.height(20.dp).widthIn(max = 34.dp))
    } else {
        Spacer(Modifier.width(24.dp))
    }
}

/** Human or one of the AIs, as a fixed width dropdown so long nation names never push it away. */
@Composable
private fun PlayerKindPicker(kind: PlayerKind, onChange: (PlayerKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.width(PICKER_WIDTH),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (kind == PlayerKind.HUMAN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(kind.label.removeSuffix(" (AI)"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayerKind.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}
