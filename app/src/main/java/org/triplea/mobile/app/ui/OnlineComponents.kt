package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.SeatDto
import java.nio.file.Path

// The online screens' shared look, built from the friends list: round avatars with a status dot,
// small upper-case section headings, and icons with a number instead of sentences.

internal val ONLINE_DOT = Color(0xFF3EC46D)
internal val PLAYING_DOT = Color(0xFFF0B429)
internal val OFFLINE_DOT = Color(0xFF6B6873)
private val AVATAR_COLORS = listOf(Color(0xFF5B4B8A), Color(0xFF2F6F8F), Color(0xFF8A5A2F), Color(0xFF3B5A3B), Color(0xFF8F2F5A), Color(0xFF2F7F75))

/** A map's folder name for display: "the_pact_of_steel" becomes "The Pact Of Steel", "world_war_ii_classic" "World War II Classic". */
internal fun mapTitle(mapName: String) = displayName(mapName).split(' ').joinToString(" ") { word ->
    if (ROMAN_NUMERAL.matches(word)) word.uppercase() else word.replaceFirstChar(Char::uppercaseChar)
}

private val ROMAN_NUMERAL = Regex("[ivx]+", RegexOption.IGNORE_CASE)

/** Two letters for a nation without a flag: the first and last capital ("AustrianEmpire" AE, "KingdomOfPrussia" KP), else the first two ("France" FR). */
internal fun nationInitials(nation: String): String {
    val capitals = nation.filter { it.isUpperCase() }
    return if (capitals.length >= 2) "${capitals.first()}${capitals.last()}" else nation.filter { it.isLetter() }.take(2).uppercase()
}

/** Whether two map names mean the same map, loosely like the engine's search: "Capture the Flag" is "capture_the_flag". */
internal fun sameMap(a: String, b: String) = a.replace('_', ' ').equals(b.replace('_', ' '), ignoreCase = true)

internal fun avatarColor(username: String) = AVATAR_COLORS[Math.floorMod(username.hashCode(), AVATAR_COLORS.size)]

/** The user's initial on a colour picked from the name. */
@Composable
internal fun Initial(username: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(avatarColor(username), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            username.take(1).uppercase(),
            color = Color.White,
            style = if (size >= 32.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
        )
    }
}

/** The initial with the status dot at the bottom right (none for a null [dot]); a tap runs [onClick] (the profile). */
@Composable
internal fun Avatar(username: String, dot: Color?, onClick: () -> kotlin.Unit, size: Dp = 40.dp) {
    Box(Modifier.size(size).clickable(onClickLabel = "profile of $username", onClick = onClick)) {
        Initial(username, size, Modifier.fillMaxSize())
        if (dot != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp)
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                    .padding(2.dp).background(dot, CircleShape),
            )
        }
    }
}

@Composable
internal fun RowScope.NameAndStatus(name: String, status: String) {
    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A section heading in small capitals, as in the friends list ("ONLINE · 3"). */
@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** An icon and a short value on a soft pill: a clock, "3/5" players, a round. [strong] marks what needs the user. */
@Composable
internal fun StatusPill(text: String, icon: ImageVector?, modifier: Modifier = Modifier, strong: Boolean = false, warning: Boolean = false) {
    val container = when {
        warning -> MaterialTheme.colorScheme.errorContainer
        strong -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        warning -> MaterialTheme.colorScheme.onErrorContainer
        strong -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp).padding(end = 3.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * One seat as a tile: the nation's flag, and on its corner who plays it (the player's initial, a robot for the AI,
 * a green + for a free seat). [active] rings the nation to move; [showReady] ticks a ready player (game setup).
 */
@Composable
internal fun SeatTile(seat: SeatDto, active: Boolean, showReady: Boolean, onOpenProfile: (String) -> kotlin.Unit) {
    val name = seat.username
    val who = when (seat.kind) {
        "OPEN" -> "free"
        "HUMAN" -> name.orEmpty() + (if (showReady && seat.ready) ", ready" else "")
        else -> seatLabel(seat)
    }
    val description = "${displayName(seat.nation)}: $who" + (if (active) ", to move" else "")
    val ring = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.size(48.dp)
            .then(if (name != null) Modifier.clickable(onClickLabel = "profile of $name") { onOpenProfile(name) } else Modifier)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Box(
            Modifier.align(Alignment.Center).size(38.dp)
                .border(if (active) 2.dp else 1.dp, ring, RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // the engine's flags cover the common nations only; others ("AustrianEmpire") show their initials
            val (flag, _) = rememberFlag(AppServices.engineImages, seat.nation)
            if (flag != null) {
                Image(flag.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
            } else {
                Text(nationInitials(seat.nation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val badge = Modifier.align(Alignment.BottomEnd).size(20.dp)
        when {
            seat.kind == "OPEN" -> Box(badge.background(FREE_SEAT, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            seat.kind == "HUMAN" && name != null -> Initial(name, 20.dp, badge.border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape))
            else -> Box(badge.background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(13.dp))
            }
        }
        if (showReady && seat.ready) {
            Box(Modifier.align(Alignment.TopEnd).size(16.dp).background(ONLINE_DOT, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Every seat of [game] as a [SeatTile] in one row that scrolls sideways on big maps, so cards keep their height. */
@Composable
internal fun SeatStrip(game: GameInfoDto, activeNation: String?, showReady: Boolean, onOpenProfile: (String) -> kotlin.Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        game.seats.forEach { SeatTile(it, active = it.nation == activeNation, showReady = showReady, onOpenProfile = onOpenProfile) }
    }
}

/** An (i) button that shows [text] in a dialog: the explanation stays one tap away instead of on the screen. */
@Composable
internal fun InfoButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, contentDescription = "about $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AppDialog(title = title, onDismiss = { open = false }, buttons = { Button(onClick = { open = false }) { Text("OK") } }) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** An empty list: a big icon, one line and at most one button. */
@Composable
internal fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> kotlin.Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

/** A big number with an icon and a one-word label: the profile's stats. */
@Composable
internal fun StatTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The preview picture of every map installed here, by map name; empty while loading. */
@Composable
internal fun rememberMapPreviews(): Map<String, Path?> {
    val previews by produceState(emptyMap<String, Path?>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { groupByMap(MobileEngine.listInstalledGames()).associate { it.mapName to it.previewFile } }.getOrDefault(emptyMap())
        }
    }
    return previews
}

/** The map's preview picture, or a map icon when the map is not installed here. */
@Composable
internal fun MapThumb(preview: Path?, size: Dp = 56.dp) {
    PreviewImage(rememberPreview(preview, 160), Modifier.size(size))
}

/** A free seat's green: readable on the light and the dark theme. */
internal val FREE_SEAT = Color(0xFF2E9E5B)
