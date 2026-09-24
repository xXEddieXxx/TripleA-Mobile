package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GameData
import games.strategy.engine.data.properties.BooleanProperty
import games.strategy.engine.data.properties.IEditableProperty
import games.strategy.engine.data.properties.NumberProperty
import games.strategy.engine.data.properties.StringProperty

/**
 * The game options of the desktop "Game Options" dialog: every editable property of the game
 * XML (bids, Low Luck, tech, victory conditions, ...) with a control matching its type. Values
 * are written straight into the game data, so the engine starts with them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameOptionsScreen(data: GameData, onBack: () -> Unit) {
    val properties = remember(data) { data.properties.editableProperties.toList() }
    val bids = remember(properties) { properties.filter { it.name.endsWith(" bid", ignoreCase = true) } }
    val others = remember(properties) { properties.filterNot { it.name.endsWith(" bid", ignoreCase = true) } }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game options") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text(
                    "The rules of this game as the desktop client offers them. The map's defaults are set; changes apply to the new game only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (properties.isEmpty()) {
                item { Text("This game has no adjustable options.", style = MaterialTheme.typography.bodyMedium) }
            }
            if (bids.isNotEmpty()) {
                item { SectionTitle("Bids (PUs a nation may spend before round 1; 0 = no bid)") }
                items(bids, key = { it.name }) { PropertyRow(it) }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle("Rules") }
                items(others, key = { it.name }) { PropertyRow(it) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun PropertyRow(property: IEditableProperty<*>) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(property.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val description = property.description?.takeIf { it.isNotBlank() && it != property.name }
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        when (property) {
            is BooleanProperty -> {
                var value by remember(property) { mutableStateOf(property.value) }
                Switch(checked = value, onCheckedChange = { value = it; property.value = it })
            }
            is NumberProperty -> {
                var value by remember(property) { mutableIntStateOf(property.value) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (value > property.min) { value -= 1; property.value = value } }, enabled = value > property.min) {
                        Icon(Icons.Filled.Remove, contentDescription = "less")
                    }
                    Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { if (value < property.max) { value += 1; property.value = value } }, enabled = value < property.max) {
                        Icon(Icons.Filled.Add, contentDescription = "more")
                    }
                    if (property.max - property.min >= 20) {
                        TextButton(onClick = {
                            value = (value + 10).coerceAtMost(property.max)
                            property.value = value
                        }) { Text("+10") }
                    }
                }
            }
            is StringProperty -> {
                var value by remember(property) { mutableStateOf(property.value ?: "") }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; if (property.validate(it)) property.value = it },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
            }
            else -> Text(property.value?.toString() ?: "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Suppress("unused")
private val unusedArrangement = Arrangement.Top
