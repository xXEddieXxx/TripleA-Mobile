package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.game.GameResult
import org.triplea.mobile.app.game.GameSession
import org.triplea.mobile.app.online.OnlineSession
import org.triplea.mobile.app.render.ImageCache
import org.triplea.mobile.protocol.Achievements

/** One nation's line: who played it (null when unknown), how it ended ("" without winners) and their rating change (rated games). */
private class ResultRow(val nation: String, val player: String?, val outcome: String, val change: Int? = null)

/** The rows (null: only the sentence) and the unlock lines for the signed-in player. */
private class ResultContent(val rows: List<ResultRow>?, val unlocks: List<String>)

/** "Achievement unlocked: First Victory" for a badge id, "New icon: Germans armour" for an icon id, null for an id from a newer server. */
private fun unlockLine(id: String): String? = Achievements.badge(id)?.let { "Achievement unlocked: ${it.name}" }
    ?: if (id.startsWith("unit:") || id.startsWith("flag:")) "New icon: ${iconLabel(id)}" else null

/** A resignation stays one even if the team won later, as in the stats; [draw]: a rated game without winners. */
private fun outcome(nation: String, winners: List<String>, resigned: Boolean, draw: Boolean) = when {
    resigned -> "Resigned"
    winners.isEmpty() -> if (draw) "Draw" else ""
    nation in winners -> "Won"
    else -> "Lost"
}

/**
 * The end of a game: Victory or Defeat for a player, "<nations> win" for a spectator, then every nation with its player
 * and outcome. Without winners (no winner, an abort, an older server) only the sentence is shown.
 */
@Composable
internal fun ResultDialog(result: GameResult, session: GameSession, images: ImageCache, onLeave: () -> kotlin.Unit, onStay: () -> kotlin.Unit) {
    val content by produceState<ResultContent?>(null, session, result) {
        if (result.aborted) return@produceState
        value = withContext(Dispatchers.IO) {
            val data = session.gameData
            val nations = data.acquireReadLock().use { data.playerList.players.map { it.name } }
            // a failed fetch leaves the player column empty, the outcomes stay
            val game = (session as? OnlineSession)?.let { online -> try { online.api.game(online.gameId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
            // online only: the server's list for this player (badges awarded at the finish, icons its win unlocked)
            val unlocks = AppSettings.tokens.tokens?.username?.let { game?.unlocks?.get(it) }.orEmpty().mapNotNull(::unlockLine)
            // without winners only a rated game with rating changes (a draw) shows the rows
            val draw = result.winners.isEmpty() && game?.rated == true && game.ratingChanges.isNotEmpty()
            if (result.winners.isEmpty() && !draw) return@withContext ResultContent(null, unlocks)
            val seats = game?.seats
            val rows = nations.map { nation ->
                val seat = seats?.firstOrNull { it.nation == nation }
                val player = when {
                    seat != null -> seat.username ?: "AI"
                    session is OnlineSession -> null
                    GameController.isHumanPlayer(nation) -> "You"
                    else -> "AI"
                }
                ResultRow(nation, player, outcome(nation, result.winners, seat?.resigned == true, draw), seat?.username?.let { game?.ratingChanges?.get(it) })
            }
            ResultContent(rows, unlocks)
        }
    }
    // a player holds a nation here, a spectator none
    val mine = GameController.humanNations.isNotEmpty()
    val won = result.winners.any { it in GameController.humanNations }
    val (headline, color) = when {
        result.aborted -> "Game aborted" to MaterialTheme.colorScheme.onSurface
        result.winners.isEmpty() -> "Game over" to MaterialTheme.colorScheme.onSurface
        won -> "Victory" to FREE_SEAT
        mine -> "Defeat" to MaterialTheme.colorScheme.error
        else -> result.winners.joinToString(" & ") { displayName(it) } + " win" to MaterialTheme.colorScheme.onSurface
    }
    val subtitle = when {
        result.aborted -> result.message
        result.winners.isEmpty() -> result.message.takeUnless { it == "Game over" } ?: "No winner"
        else -> null
    }
    AppDialog(
        title = "Game over",
        onDismiss = onStay,
        buttons = {
            TextButton(onClick = onStay) { Text("Stay on map") }
            Button(onClick = onLeave) { Text(if (session is OnlineSession) "Back to lobby" else "Menu") }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            if (won) Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = color, modifier = Modifier.size(32.dp).padding(end = 8.dp))
            Text(headline, style = MaterialTheme.typography.headlineSmall, color = color)
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
        // first, above the rows; inside the scroll so many unlocks never push the rows out of reach
        content?.unlocks?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        content?.rows?.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                NationFlag(images, row.nation, 24.dp)
                Text(displayName(row.nation), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.player?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)) }
                Text(
                    row.outcome,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (row.outcome) {
                        "Won" -> FREE_SEAT
                        "Lost" -> MaterialTheme.colorScheme.error
                        else -> Color.Unspecified
                    },
                )
                row.change?.let { Text(ratingDelta(it), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp)) }
            }
        }
        }
    }
}
