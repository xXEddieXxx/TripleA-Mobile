package org.triplea.mobile.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.triplea.mobile.app.maps.DownloadState
import org.triplea.mobile.app.maps.InstalledMap
import org.triplea.mobile.app.maps.ListingState
import org.triplea.mobile.app.maps.MapDownloadManager
import org.triplea.mobile.app.maps.MapEntry

/** The map browser: the desktop client's "Download Maps" window, sized for a phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapBrowserScreen(onBack: () -> Unit) {
    val listing by MapDownloadManager.listing.collectAsState()
    val installed by MapDownloadManager.installed.collectAsState()
    val downloads by MapDownloadManager.downloads.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var installedOnly by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<MapEntry?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = MapDownloadManager.importZip(context, uri)
            importing = false
            result.onSuccess { notice = "Map imported" to "The map is installed and appears in the game selection." }
                .onFailure { notice = "Import failed" to (it.message ?: "The file could not be imported.") }
        }
    }
    notice?.let { (title, text) -> MessageDialog(title, text) { notice = null } }

    LaunchedEffect(Unit) {
        MapDownloadManager.refreshInstalled()
        if (listing !is ListingState.Loaded) MapDownloadManager.refreshListing()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map browser") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !importing) {
                        Text(if (importing) "Importing\u2026" else "Import")
                    }
                    IconButton(onClick = { MapDownloadManager.refreshListing(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val state = listing) {
                is ListingState.Idle, is ListingState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Loading the map list...", modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
                is ListingState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text("Could not load the map list", style = MaterialTheme.typography.titleMedium)
                            Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            Button(onClick = { MapDownloadManager.refreshListing(force = true) }, modifier = Modifier.padding(top = 16.dp)) {
                                Text("Retry")
                            }
                        }
                    }
                }
                is ListingState.Loaded -> {
                    val categories = remember(state.maps) {
                        state.maps.map { it.category.uppercase(Locale.ROOT) }.distinct()
                            .sortedBy { MapDownloadManager.categoryRank(it) }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search maps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(
                            selected = installedOnly,
                            onClick = { installedOnly = !installedOnly },
                            label = { Text("Installed") },
                        )
                        categories.forEach { name ->
                            FilterChip(
                                selected = category == name,
                                onClick = { category = if (category == name) null else name },
                                label = { Text(name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    val filtered = state.maps.filter { entry ->
                        (query.isBlank() || entry.name.contains(query, ignoreCase = true) || entry.description.contains(query, ignoreCase = true)) &&
                            (category == null || entry.category.equals(category, ignoreCase = true)) &&
                            (!installedOnly || installed.containsKey(entry.normalizedName))
                    }
                    Text(
                        "${filtered.size} of ${state.maps.size} maps" + if (state.fromCache) "  ·  list from cache" else "",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        items(filtered, key = { it.name }) { entry ->
                            MapRow(
                                entry = entry,
                                installed = installed[entry.normalizedName],
                                download = downloads[entry.normalizedName],
                                onDelete = { deleteCandidate = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("The map files are removed from this device. Saved games of this map can no longer be loaded until it is installed again.") },
            confirmButton = { Button(onClick = { deleteCandidate = null; MapDownloadManager.delete(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MapRow(entry: MapEntry, installed: InstalledMap?, download: DownloadState?, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Thumbnail(entry.imageUrl)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.category.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } + "  ·  version ${entry.version}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(statusText(entry, installed, download), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    if (!expanded) {
                        Text(
                            entry.description.lineSequence().firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (expanded && entry.description.isNotBlank()) {
                Text(entry.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            when (download) {
                is DownloadState.Downloading -> {
                    val fraction = download.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                is DownloadState.Installing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                is DownloadState.Failed -> Text(
                    download.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                null -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                val compact = PaddingValues(horizontal = 14.dp)
                when {
                    download is DownloadState.Downloading || download is DownloadState.Installing -> {
                        OutlinedButton(onClick = { MapDownloadManager.cancel(entry) }, enabled = download is DownloadState.Downloading, contentPadding = compact) {
                            Text("Cancel")
                        }
                    }
                    download is DownloadState.Failed -> {
                        TextButton(onClick = { MapDownloadManager.dismissError(entry) }) { Text("Dismiss") }
                        Button(onClick = { MapDownloadManager.download(entry) }, contentPadding = compact) { Text("Retry") }
                    }
                    installed == null -> {
                        Button(onClick = { MapDownloadManager.download(entry) }, contentPadding = compact) { Text("Download") }
                    }
                    else -> {
                        if (!installed.bundled) {
                            OutlinedButton(onClick = onDelete, contentPadding = compact) { Text("Delete") }
                        }
                        val outdated = installed.downloadVersion != null && installed.downloadVersion < entry.version
                        if (outdated) {
                            Button(onClick = { MapDownloadManager.download(entry) }, contentPadding = compact) { Text("Update") }
                        } else if (!installed.bundled) {
                            OutlinedButton(onClick = { MapDownloadManager.download(entry) }, contentPadding = compact) { Text("Reinstall") }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(entry: MapEntry, installed: InstalledMap?, download: DownloadState?): String = when {
    download is DownloadState.Downloading -> {
        val mb = download.bytes / 1_048_576.0
        val total = download.total?.let { it / 1_048_576.0 }
        if (total != null) String.format(Locale.ROOT, "Downloading %.1f of %.1f MB", mb, total)
        else String.format(Locale.ROOT, "Downloading %.1f MB", mb)
    }
    download is DownloadState.Installing -> "Installing..."
    installed == null -> "Not installed"
    installed.bundled -> "Installed (bundled with the app)"
    installed.downloadVersion == null -> "Installed"
    installed.downloadVersion < entry.version -> "Installed version ${installed.downloadVersion}, update available"
    else -> "Installed, up to date"
}

@Composable
private fun Thumbnail(url: String?) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url != null) bitmap = MapDownloadManager.thumbnail(url)
    }
    val shape = RoundedCornerShape(6.dp)
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 96.dp, height = 64.dp).clip(shape),
        )
    } else {
        Box(Modifier.size(width = 96.dp, height = 64.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
