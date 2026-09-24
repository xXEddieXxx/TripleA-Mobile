package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.ThemeMode
import org.triplea.mobile.app.UiMode
import org.triplea.mobile.app.OrientationMode
import org.triplea.mobile.app.MapQuality

/** The settings screen, reachable from the main menu and (as a dialog) from a running game. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = { TextButton(onClick = { AppSettings.resetToDefaults() }) { Text("Defaults") } },
            )
        },
    ) { padding ->
        SettingsContent(Modifier.fillMaxSize().padding(padding))
    }
}

/** Full screen settings dialog for use inside the game screen. */
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) { SettingsScreen(onBack = onClose) }
    }
}

@Composable
private fun SettingsContent(modifier: Modifier = Modifier) {
    val settings by AppSettings.state.collectAsState()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("Game")
        SwitchRow(
            "Confirm before ending a phase",
            "Ask before Done and End turn, because a finished phase cannot be reopened.",
            settings.confirmPhaseEnd,
        ) { v -> AppSettings.update { it.copy(confirmPhaseEnd = v) } }
        SwitchRow(
            "Show phase banner",
            "Announce every new phase and player for a moment on the map.",
            settings.showPhaseBanner,
        ) { v -> AppSettings.update { it.copy(showPhaseBanner = v) } }
        SwitchRow(
            "Pause after casualties",
            "Show the casualty report of every battle round in the battle window and wait for Continue.",
            settings.pauseAfterCasualties,
        ) { v -> AppSettings.update { it.copy(pauseAfterCasualties = v) } }
        SliderRow(
            "Battle speed: pause per step",
            "${settings.battleStepPauseMillis} ms",
            settings.battleStepPauseMillis.toFloat(),
            0f..2000f,
            steps = 19,
        ) { v -> AppSettings.update { it.copy(battleStepPauseMillis = (v / 100f).roundToInt() * 100) } }
        SwitchRow(
            "Accept default casualties",
            "Take the engine's casualty choice without asking (faster battles).",
            settings.autoDefaultCasualties,
        ) { v -> AppSettings.update { it.copy(autoDefaultCasualties = v) } }
        SwitchRow(
            "Show battles between AIs",
            "Open the battle window also for battles no human is part of.",
            settings.showAiBattles,
        ) { v -> AppSettings.update { it.copy(showAiBattles = v) } }
        SwitchRow(
            "Autosave each round",
            "Write an 'autosave' save game when your first phase of a round starts.",
            settings.autosaveEachRound,
        ) { v -> AppSettings.update { it.copy(autosaveEachRound = v) } }

        Section("Feedback")
        SwitchRow(
            "Vibrate when your units fight",
            "A short buzz when an AI attacks you or defends against you, so you can look up from something else.",
            settings.vibrateOnBattle,
        ) { v -> AppSettings.update { it.copy(vibrateOnBattle = v) } }
        SwitchRow(
            "Vibrate when your turn starts",
            "A buzz when the AI is done and it is your move.",
            settings.vibrateOnTurn,
        ) { v -> AppSettings.update { it.copy(vibrateOnTurn = v) } }

        Section("AI")
        Text(
            "While the pause is above 0 the map follows every AI move and shows its route. At 0 the AI plays at full speed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderRow(
            "Pause between AI moves",
            if (settings.aiMovePauseMillis == 0) "off" else "${settings.aiMovePauseMillis} ms",
            settings.aiMovePauseMillis.toFloat(),
            0f..2000f,
            steps = 19,
        ) { v -> AppSettings.update { it.copy(aiMovePauseMillis = (v / 100f).roundToInt() * 100) } }
        SliderRow(
            "Pause between AI combat steps",
            "${settings.aiCombatStepPauseMillis} ms",
            settings.aiCombatStepPauseMillis.toFloat(),
            0f..2000f,
            steps = 19,
        ) { v -> AppSettings.update { it.copy(aiCombatStepPauseMillis = (v / 100f).roundToInt() * 100) } }

        Section("Map")
        SwitchRow(
            "Show territory names",
            "Draw the names on the map when zoomed in.",
            settings.showTerritoryNames,
        ) { v -> AppSettings.update { it.copy(showTerritoryNames = v) } }
        SwitchRow(
            "Show territory values",
            "Draw the PU value of every land territory on the map (also in the game menu).",
            settings.showTerritoryValues,
        ) { v -> AppSettings.update { it.copy(showTerritoryValues = v) } }
        Text("Map quality", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Lower quality loads tiles at reduced resolution: faster and less memory on big maps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            MapQuality.values().forEach { quality ->
                FilterChip(
                    selected = settings.mapQuality == quality,
                    onClick = { AppSettings.update { it.copy(mapQuality = quality) } },
                    label = { Text(quality.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        SwitchRow(
            "Relief layer",
            "The shaded terrain layer over the map. Off saves memory and drawing time.",
            settings.showRelief,
        ) { v -> AppSettings.update { it.copy(showRelief = v) } }
        SliderRow(
            "Unit icon size",
            "${(settings.unitScale * 100).roundToInt()} %",
            settings.unitScale,
            0.7f..1.6f,
            steps = 8,
        ) { v -> AppSettings.update { it.copy(unitScale = (v * 10).roundToInt() / 10f) } }
        SliderRow(
            "Unit number size",
            "${(settings.counterScale * 100).roundToInt()} %",
            settings.counterScale,
            0.8f..2.0f,
            steps = 11,
        ) { v -> AppSettings.update { it.copy(counterScale = (v * 10).roundToInt() / 10f) } }
        SliderRow(
            "Maximum zoom",
            "${"%.1f".format(settings.mapMaxZoom)}×",
            settings.mapMaxZoom,
            2f..8f,
            steps = 11,
        ) { v -> AppSettings.update { it.copy(mapMaxZoom = (v * 2).roundToInt() / 2f) } }

        Section("Sound")
        SwitchRow(
            "Sound effects",
            "The desktop client's sound clips for battles, phases, placements and captures.",
            settings.soundEnabled,
        ) { v -> AppSettings.update { it.copy(soundEnabled = v) } }
        SliderRow(
            "Volume",
            "${(settings.soundVolume * 100).roundToInt()} %",
            settings.soundVolume,
            0f..1f,
            steps = 9,
        ) { v -> AppSettings.update { it.copy(soundVolume = (v * 10).roundToInt() / 10f) } }
        SwitchRow("Battle sounds", "Fighting, anti-aircraft fire, bombing, retreats.", settings.soundBattle) { v ->
            AppSettings.update { it.copy(soundBattle = v) }
        }
        SwitchRow("Phase sounds", "Start of your turn and of each phase.", settings.soundPhase) { v ->
            AppSettings.update { it.copy(soundPhase = v) }
        }
        SwitchRow("Placement and capture sounds", "Units placed, territories captured.", settings.soundPlacement) { v ->
            AppSettings.update { it.copy(soundPlacement = v) }
        }
        SwitchRow("Other sounds", "Game start, victory, politics and technology results.", settings.soundOther) { v ->
            AppSettings.update { it.copy(soundOther = v) }
        }
        Text("Sound theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Which set of clips to use. Default follows the map (usually WW2).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
            listOf("" to "Default", "ww2" to "WW2", "classical" to "Classical", "preindustrial" to "Preindustrial", "future" to "Future").forEach { (key, label) ->
                FilterChip(
                    selected = settings.soundTheme == key,
                    onClick = { AppSettings.update { it.copy(soundTheme = key) } },
                    label = { Text(label) },
                )
            }
        }

        Section("Appearance")
        Text("Screen orientation in a game", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Lock the game screen so it does not turn with the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            OrientationMode.values().forEach { mode ->
                FilterChip(
                    selected = settings.orientation == mode,
                    onClick = { AppSettings.update { it.copy(orientation = mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        Text("Layout", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Desktop: permanent side panel with tabs like the desktop client, meant for tablets. Auto picks it on screens of 600dp and wider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            UiMode.values().forEach { mode ->
                FilterChip(
                    selected = settings.uiMode == mode,
                    onClick = { AppSettings.update { it.copy(uiMode = mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            ThemeMode.values().forEach { mode ->
                FilterChip(
                    selected = settings.theme == mode,
                    onClick = { AppSettings.update { it.copy(theme = mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        SwitchRow(
            "Fullscreen game",
            "Hide the Android status and navigation bars while playing. Swipe from the top or bottom edge to show them briefly.",
            settings.fullscreenGame,
        ) { v -> AppSettings.update { it.copy(fullscreenGame = v) } }
        Section("Screen")
        SwitchRow(
            "Keep screen on during a game",
            "Prevents the display from sleeping while the game screen is open.",
            settings.keepScreenOn,
        ) { v -> AppSettings.update { it.copy(keepScreenOn = v) } }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}
