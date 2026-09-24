package org.triplea.mobile.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.game.GameController

/** One installed map with its games, preview image and description, as shown in the chooser. */
class MapChoice(
    val mapName: String,
    val root: Path?,
    val previewFile: Path?,
    val descriptionFile: Path?,
    val games: List<MobileEngine.InstalledGame>,
)

/** Groups the installed games by map and finds each map's preview and description files. */
fun groupByMap(games: List<MobileEngine.InstalledGame>): List<MapChoice> =
    games.groupBy { it.mapName }.map { (mapName, list) ->
        val root = findMapRoot(list.first().xmlPath)
        MapChoice(
            mapName = mapName,
            root = root,
            previewFile = root?.let { r ->
                listOf("preview.png", "preview.jpg", "map/preview.png").map { r.resolve(it) }.firstOrNull { Files.exists(it) }
                    ?: findFirstImage(r.resolve("description"))
            },
            descriptionFile = root?.let { r ->
                listOf("description.html", "map/description.html", "README.md").map { r.resolve(it) }.firstOrNull { Files.exists(it) }
            },
            games = list.sortedBy { it.gameName },
        )
    }.sortedBy { it.mapName.lowercase() }

/** The folder that holds map.yml, searched upwards from the game XML. */
private fun findMapRoot(xml: Path): Path? {
    var current: Path? = xml.parent
    repeat(4) {
        val dir = current ?: return null
        if (Files.exists(dir.resolve("map.yml"))) return dir
        current = dir.parent
    }
    return null
}

private fun findFirstImage(dir: Path): Path? = runCatching {
    if (!Files.isDirectory(dir)) return null
    Files.list(dir).use { files ->
        files.filter { it.toString().lowercase().let { n -> n.endsWith(".png") || n.endsWith(".jpg") } }.findFirst().orElse(null)
    }
}.getOrNull()

/**
 * The "new game" chooser: a search box and one card per map with preview picture, number of
 * games and, when opened, the map's description and its games with a start button each.
 */
@Composable
fun GameChooser(games: List<MobileEngine.InstalledGame>, onPick: (MobileEngine.InstalledGame) -> Unit) {
    val maps = remember(games) { groupByMap(games) }
    var query by rememberSaveable { mutableStateOf("") }
    var openMap by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(maps, query) {
        if (query.isBlank()) maps
        else maps.mapNotNull { map ->
            val mapMatches = map.mapName.contains(query, ignoreCase = true)
            val matchingGames = map.games.filter { it.gameName.contains(query, ignoreCase = true) }
            when {
                mapMatches -> map
                matchingGames.isNotEmpty() -> MapChoice(map.mapName, map.root, map.previewFile, map.descriptionFile, matchingGames)
                else -> null
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search maps and games") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (filtered.isEmpty()) {
            Text("No map matches.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(filtered, key = { it.mapName }) { map ->
                MapCard(
                    map = map,
                    open = openMap == map.mapName || (filtered.size == 1 && query.isNotBlank()),
                    onToggle = { openMap = if (openMap == map.mapName) null else map.mapName },
                    onPick = onPick,
                )
            }
        }
    }
}

@Composable
private fun MapCard(map: MapChoice, open: Boolean, onToggle: () -> Unit, onPick: (MobileEngine.InstalledGame) -> Unit) {
    val preview = rememberPreview(map.previewFile, if (open) 900 else 300)
    var description by remember(map.descriptionFile) { mutableStateOf<String?>(null) }
    var moreText by rememberSaveable(map.mapName) { mutableStateOf(false) }
    var selected by rememberSaveable(map.mapName) { mutableStateOf(0) }
    var pickGame by remember { mutableStateOf(false) }
    LaunchedEffect(open, map.descriptionFile) {
        if (open && description == null && map.descriptionFile != null) {
            description = withContext(Dispatchers.IO) {
                runCatching { GameController.stripHtml(String(Files.readAllBytes(map.descriptionFile), Charsets.UTF_8)) }
                    .getOrNull()?.trim()?.take(2000)
            }
        }
    }
    val game = map.games.getOrNull(selected.coerceIn(0, (map.games.size - 1).coerceAtLeast(0)))
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onToggle,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (open) 0.55f else 0.35f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            // ---- title row: small preview, name, number of games
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!open) {
                    PreviewImage(preview, Modifier.size(width = 84.dp, height = 56.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(map.mapName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (map.games.size > 1) {
                        Text("${map.games.size} versions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(if (open) "\u25be" else "\u25b8", style = MaterialTheme.typography.titleMedium)
            }
            if (open) {
                // ---- picture
                if (preview != null) {
                    PreviewImage(
                        preview,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(max = 220.dp)
                            .aspectRatio(preview.width.toFloat() / preview.height.toFloat().coerceAtLeast(1f)),
                    )
                }
                // ---- which version, and start
                if (map.games.size > 1 && game != null) {
                    OutlinedButton(
                        onClick = { pickGame = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(game.gameName, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("\u25be")
                    }
                    if (pickGame) {
                        AppDialog(title = map.mapName, onDismiss = { pickGame = false }, buttons = {}) {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                map.games.forEachIndexed { index, g ->
                                    OptionRow(title = g.gameName, emphasized = index == selected, onClick = { selected = index; pickGame = false })
                                }
                            }
                        }
                    }
                }
                if (game != null) {
                    Button(onClick = { onPick(game) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(if (map.games.size > 1) "Start" else "Start ${game.gameName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // ---- description, folded to a few lines
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                        maxLines = if (moreText) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (it.length > 240) {
                        TextButton(onClick = { moreText = !moreText }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(if (moreText) "less" else "more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewImage(bitmap: Bitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Decodes the preview down to roughly [targetWidth] pixels, off the main thread. */
@Composable
private fun rememberPreview(file: Path?, targetWidth: Int): Bitmap? {
    var bitmap by remember(file, targetWidth) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file, targetWidth) {
        if (file == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.toString(), bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeFile(file.toString(), BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    return bitmap
}
