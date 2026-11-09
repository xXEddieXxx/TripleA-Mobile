package org.triplea.mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import org.triplea.mobile.app.NavArgs
import org.triplea.mobile.app.SaveTransfer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.game.GameController

/** A save game file with what the list shows about it. */
private class SaveEntry(
    val path: Path,
    val name: String,
    val modified: Long,
    val bytes: Long,
    /** From the sidecar written on save; empty for saves of older builds. */
    val info: Map<String, String>,
)

private fun readSaves(): List<SaveEntry> = MobileEngine.listSaveGames().map { path ->
    val info = runCatching {
        val file = GameController.infoFileFor(path)
        if (Files.exists(file)) {
            String(Files.readAllBytes(file), Charsets.UTF_8).lines()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
        } else emptyMap()
    }.getOrDefault(emptyMap())
    SaveEntry(
        path = path,
        name = path.fileName.toString().removeSuffix(".tsvg"),
        modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
        bytes = runCatching { Files.size(path) }.getOrDefault(0L),
        info = info,
    )
}.sortedByDescending { it.modified }

/** Moves or deletes the sidecar along with the save. */
private fun withSidecar(save: Path, action: (Path) -> Unit) {
    val info = GameController.infoFileFor(save)
    if (Files.exists(info)) runCatching { action(info) }
}

/** The load screen: searchable list of saves, each with a small menu to load, rename or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadGameScreen(onBack: () -> Unit, onLoad: (Path) -> Unit) {
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SaveEntry?>(null) }
    var deleting by remember { mutableStateOf<List<SaveEntry>?>(null) }
    // paths of the saves picked in selection mode (entered by a long press); empty = normal mode
    var selected by remember { mutableStateOf<Set<Path>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            saves = withContext(Dispatchers.IO) { runCatching { readSaves() }.getOrDefault(emptyList()) }
            loaded = true
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun importFrom(uri: Uri) {
        scope.launch {
            importing = true
            val result = SaveTransfer.import(context, uri)
            importing = false
            result.onSuccess { refresh() }
                .onFailure { notice = it.message ?: "The file could not be imported." }
        }
    }
    // a file handed over by another app (share sheet, file manager)
    val pendingImport by NavArgs.pendingImport.collectAsState()
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        NavArgs.pendingImport.value = null
        importFrom(uri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    val filtered = remember(saves, query) {
        if (query.isBlank()) saves else saves.filter { it.name.contains(query, ignoreCase = true) }
    }
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }

    notice?.let { MessageDialog("Import", it) { notice = null } }

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "end selection") }
                },
                actions = {
                    TextButton(onClick = { selected = filtered.mapTo(HashSet()) { it.path } }) { Text("Select all") }
                    IconButton(onClick = { deleting = saves.filter { it.path in selected } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "delete selected")
                    }
                },
            ) else TopAppBar(
                title = { Text("Load game") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                        Text(if (importing) "Importing\u2026" else "Import")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                // a new search drops the selection, so Delete never hits saves the list no longer shows
                onValueChange = { query = it; selected = emptySet() },
                placeholder = { Text("Search saves") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            when {
                !loaded -> Text("Reading saves…", style = MaterialTheme.typography.bodyMedium)
                saves.isEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Text("No saved games yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text("Games are saved from the menu in a game; an autosave is written every round.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                filtered.isEmpty() -> Text("No save matches.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.path.toString() }) { save ->
                        SaveRow(
                            save = save,
                            date = dateFormat.format(Instant.ofEpochMilli(save.modified).atZone(ZoneId.systemDefault())),
                            selected = if (selecting) save.path in selected else null,
                            // plusElement/minusElement: Path is itself an Iterable<Path>, so plain +/- would add its name segments
                            onToggle = { selected = if (save.path in selected) selected.minusElement(save.path) else selected.plusElement(save.path) },
                            onLoad = { onLoad(save.path) },
                            onRename = { renaming = save },
                            onDelete = { deleting = listOf(save) },
                            onShare = { runCatching { SaveTransfer.share(context, save.path) }.onFailure { notice = "Sharing failed: ${it.message}" } },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { save ->
        var name by remember(save) { mutableStateOf(save.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename save") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && name.trim() != save.name,
                    onClick = {
                        val clean = name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "")
                        renaming = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val target = save.path.resolveSibling("$clean.tsvg")
                                runCatching { Files.move(save.path, target) }
                                withSidecar(save.path) { Files.move(it, GameController.infoFileFor(target)) }
                            }
                            refresh()
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { victims ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (victims.size == 1) "Delete ${victims[0].name}?" else "Delete ${victims.size} saves?") },
            text = { Text(if (victims.size == 1) "The save game is removed from this device." else "The selected save games are removed from this device.") },
            confirmButton = {
                Button(onClick = {
                    deleting = null
                    selected = emptySet()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            victims.forEach { save ->
                                runCatching { Files.deleteIfExists(save.path) }
                                withSidecar(save.path) { Files.deleteIfExists(it) }
                            }
                        }
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** [selected] is null outside selection mode; in it a tap toggles the row instead of loading. */
@Composable
private fun SaveRow(
    save: SaveEntry,
    date: String,
    selected: Boolean?,
    onToggle: () -> Unit,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val autosave = save.name.startsWith("autosave", ignoreCase = true)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(CardDefaults.shape)
            .combinedClickable(onClick = if (selected != null) onToggle else onLoad, onLongClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.size(24.dp))
            } else Icon(
                Icons.Filled.Save,
                contentDescription = null,
                tint = if (autosave) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(save.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val round = save.info["round"]?.takeIf { it.isNotBlank() && it != "0" }
                val player = save.info["player"].orEmpty()
                val game = save.info["game"].orEmpty()
                if (round != null || game.isNotBlank()) {
                    Text(
                        listOfNotNull(round?.let { "Round $it" }, player.takeIf { it.isNotBlank() }, game.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    date + if (autosave) "  ·  autosave" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected == null) IconButton(onClick = onLoad) { Icon(Icons.Filled.PlayArrow, contentDescription = "load", tint = MaterialTheme.colorScheme.primary) }
            if (selected == null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Load") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { menu = false; onLoad() },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Suppress("unused")
private val unusedArrangement = Arrangement.Top
