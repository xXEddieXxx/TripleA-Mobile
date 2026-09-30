package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.triplea.mobile.app.game.HistoryBlock
import org.triplea.mobile.app.game.HistoryEvent
import org.triplea.mobile.app.game.HistoryKind
import org.triplea.mobile.app.render.ImageCache

/*
 * Replaying the game history on the map, the desktop client's "Show History" mode: every event of
 * the history can be shown on the map as it stood at that moment (a clone of the game data wound
 * back with HistoryView), and stepped through in play order. The point of it is play by file:
 * whoever receives a save wants to see what the other players did since their own last turn.
 */

/** One step of a replay: an event together with the step it happened in. */
class ReplayItem(val block: HistoryBlock, val event: HistoryEvent) {
    /** The territories the map shows for this item: the route of a move, the place of a battle. */
    val route: List<String> get() = if (event.route.isNotEmpty()) event.route else listOfNotNull(event.territory)

    /** "Move", "Placement", "Battle" or "Purchase", for the chip. */
    val label: String get() = when (event.kind) {
        HistoryKind.MOVE -> "Move"
        HistoryKind.PLACE -> "Placement"
        HistoryKind.BATTLE -> "Battle"
        HistoryKind.PURCHASE -> "Purchase"
        else -> "Event"
    }

    /** What the chip says: "from → to" for a move, the territory for the rest. */
    val headline: String get() = when {
        event.kind == HistoryKind.MOVE && event.route.size >= 2 -> "${event.route.first()} → ${event.route.last()}"
        event.kind == HistoryKind.BATTLE -> event.text.removePrefix("Battle in ").removePrefix("Air Battle in ")
        route.isNotEmpty() -> route.first()
        else -> event.text
    }
}

/** Every event of the history in play order: the stops of the desktop's Back/Next buttons. */
fun replayItems(history: List<HistoryBlock>): List<ReplayItem> = history.flatMap { block ->
    block.events.map { ReplayItem(block, it) }
}

/**
 * Where a replay of "what happened since my last turn" starts: the first item of the latest run
 * of other players' events, which is the one after the last human event before it. What the
 * human did since (a trigger when the turn began, a purchase) does not hide that run. -1 when no
 * other player has done anything yet.
 */
fun replayStartAfterHumanTurn(items: List<ReplayItem>, isHuman: (String) -> Boolean): Int {
    // steps without a player (the game's set-up) are nobody's turn
    val end = items.indexOfLast { it.block.player.isNotBlank() && !isHuman(it.block.player) }
    if (end < 0) return -1
    return items.subList(0, end).indexOfLast { isHuman(it.block.player) } + 1
}

/** The chip over the map while replaying: flag, units, what happened, and the previous/next buttons. */
@Composable
fun ReplayChip(
    item: ReplayItem,
    index: Int,
    total: Int,
    images: ImageCache,
    onPrevious: () -> kotlin.Unit,
    onNext: () -> kotlin.Unit,
    onClose: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
    /** The map of that moment is still being prepared. */
    busy: Boolean = false,
) {
    val player = item.block.player
    val flag = remember(player, images) {
        images.getNow("flags/$player.png", listOf("flags/$player.png", "flags/${player}_small.png"))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.widthIn(max = 480.dp)) {
        IconButton(onClick = onPrevious, enabled = index > 0) { Icon(Icons.Filled.ChevronLeft, contentDescription = "previous") }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag != null) {
                    Image(flag.asImageBitmap(), contentDescription = player, modifier = Modifier.height(16.dp).widthIn(max = 28.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "${item.label} · ${item.block.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text("${index + 1}/$total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.event.units.groupBy { it.sample.type to it.sample.owner }.entries.take(4).forEach { (key, refs) ->
                    UnitIcon(images, key.first, key.second, size = 22)
                    Text("×${refs.sumOf { it.count }} ", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    item.headline,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.event.kind == HistoryKind.BATTLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onNext, enabled = index < total - 1) { Icon(Icons.Filled.ChevronRight, contentDescription = "next") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "close replay") }
    }
}
