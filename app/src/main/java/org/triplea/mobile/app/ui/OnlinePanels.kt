package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.online.OnlineSession
import org.triplea.mobile.app.online.Social
import org.triplea.mobile.protocol.Clock
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.StandingOrdersDto

/**
 * The server's info on [session]'s game (seats the AI took over, time control), fetched again after a
 * clock change or a new step.
 */
@Composable
internal fun rememberGameInfo(session: OnlineSession): GameInfoDto? {
    // seats change hands (AI takeover, back) with a clock expiry, a step or a turn
    val clock = Social.clocks.collectAsState().value[session.gameId]
    val status by GameController.status.collectAsState()
    var info by remember(session) { mutableStateOf<GameInfoDto?>(null) }
    LaunchedEffect(session, clock, status) {
        // a burst of changes (an AI turn) fetches once; the first fetch at once
        if (info != null) delay(INFO_DEBOUNCE_MS)
        withContext(Dispatchers.IO) { runCatching { session.api.game(session.gameId) } }.onSuccess { info = it }
    }
    return info
}

private const val INFO_DEBOUNCE_MS = 3_000L

/** Remaining time as the clock bar shows it: days and hours, h:mm:ss or m:ss, rounded up. */
internal fun clockText(millis: Long): String {
    val s = (millis.coerceAtLeast(0) + 999) / 1000
    val days = s / 86_400
    val hours = s % 86_400 / 3600
    val minutes = s % 3600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, s % 60)
        else -> String.format(Locale.ROOT, "%d:%02d", minutes, s % 60)
    }
}

/**
 * Every player's remaining time; the running clock counts down from the server's deadline. When the
 * AI plays one of [me]'s seats (their clock ran out) it says so, with "I'm back" ([onBack]); after a
 * resignation without it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClockBar(clock: Clock?, me: String, takenOver: Boolean, resigned: Boolean, backSent: Boolean, onBack: () -> kotlin.Unit) {
    if (clock == null && !takenOver) return
    val deadline = clock?.deadline?.takeIf { clock.running != null }
    // the deadline is the server's time: the phone's clock may be off
    val now by produceState(System.currentTimeMillis() + Social.clockSkewMs, deadline) {
        while (deadline != null) {
            value = System.currentTimeMillis() + Social.clockSkewMs
            delay(1000 - value % 1000)
        }
    }
    OverlayChip {
        if (clock != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                clock.clocks.forEach { (user, remaining) ->
                    val running = user == clock.running
                    val left = if (running && deadline != null) deadline - now else remaining
                    val low = left < LOW_TIME_MS
                    val time = clockText(left)
                    val description = "$user, $time left" + (if (running) ", running" else "") + (if (low) ", low" else "")
                    Row(
                        Modifier.clearAndSetSemantics { contentDescription = description },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Initial(user, 20.dp)
                        StatusPill(time, if (running) Icons.Filled.Timer else null, strong = running, warning = low)
                    }
                }
            }
        }
        if (takenOver) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    when {
                        resigned -> "Resigned"
                        backSent -> "Back next turn"
                        else -> "AI playing for you"
                    },
                    Icons.Filled.SmartToy,
                    warning = !backSent,
                )
                if (!backSent && !resigned) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onBack) { Text("I'm back") }
                }
            }
        }
    }
}

/**
 * The turn's time beside the nation in the header: the running clock counting down to [deadline], else how long the
 * turn has lasted since [startedAt]. Both are server time, so [Social.clockSkewMs] is added to the phone's.
 */
@Composable
internal fun TurnTimer(deadline: Long?, startedAt: Long?) {
    if (deadline == null && startedAt == null) return
    val now by produceState(System.currentTimeMillis() + Social.clockSkewMs) {
        while (true) {
            value = System.currentTimeMillis() + Social.clockSkewMs
            delay(1000 - value % 1000)
        }
    }
    if (deadline != null) {
        val left = deadline - now
        StatusPill(clockText(left), Icons.Filled.Timer, Modifier.semantics { contentDescription = "${clockText(left)} left" }, warning = left < LOW_TIME_MS)
    } else if (startedAt != null) {
        val lasted = clockText(now - startedAt)
        StatusPill(lasted, Icons.Filled.HourglassTop, Modifier.semantics { contentDescription = "turn running for $lasted" })
    }
}

/** Under a minute left: the clock turns to the warning colour. */
private const val LOW_TIME_MS = 60_000L

/** One standing order: a chip per value; null is the engine's default answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderRow(icon: ImageVector, title: String, options: List<Pair<String?, String>>, value: String?, onChange: (String?) -> kotlin.Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (option, label) -> FilterChip(selected = value == option, onClick = { onChange(option) }, label = { Text(label) }) }
    }
}

/** The player's standing orders and defender mode in [session]'s game, loaded from and saved to the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StandingOrdersSheet(session: OnlineSession, onDismiss: () -> kotlin.Unit) {
    var orders by remember { mutableStateOf<StandingOrdersDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        withContext(Dispatchers.IO) { runCatching { session.api.orders(session.gameId) } }
            .onSuccess { orders = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // the game runs fullscreen: a fixed bottom margin clears the gesture handle, as for the chat
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Standing orders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton("Standing orders", "How the server answers for you when you are not asked: outside your turn, and when your clock runs out.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            val o = orders
            if (o == null) {
                if (error == null) CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                return@Column
            }
            OrderRow(Icons.Filled.Shield, "Defending", listOf("live" to "Always ask me", "hybrid" to "Ask me when online", "standing" to "Use my orders"), o.defenderMode) {
                orders = o.copy(defenderMode = it ?: "hybrid")
            }
            OrderRow(Icons.Filled.PersonRemove, "Casualties", listOf(null to "Default", "cheapest_first" to "Cheapest first", "aircraft_last" to "Aircraft last"), o.casualties) {
                orders = o.copy(casualties = it)
            }
            OrderRow(Icons.Filled.FlightTakeoff, "Scramble", listOf(null to "Default", "never" to "Never"), o.scramble) { orders = o.copy(scramble = it) }
            OrderRow(Icons.AutoMirrored.Filled.DirectionsRun, "Retreat", listOf(null to "Default", "never" to "Never"), o.retreat) { orders = o.copy(retreat = it) }
            OrderRow(Icons.Filled.Handshake, "Political offers", listOf(null to "Default", "decline" to "Decline", "ask" to "Always ask me"), o.offers) { orders = o.copy(offers = it) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                session.api.setOrders(session.gameId, o)
                                session.api.setDefenderMode(session.gameId, o.defenderMode)
                            }
                        }.onSuccess { onDismiss() }.onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                    }
                }) { Text("Save") }
            }
        }
    }
}

/**
 * Gives up the game for good: the AI plays [me]'s nations, the server ends the game once only one team has players
 * left ([onResigned] then leaves the game screen). The consequence stays on screen: it cannot be undone.
 */
@Composable
internal fun ResignDialog(session: OnlineSession, info: GameInfoDto?, me: String, onDismiss: () -> kotlin.Unit, onResigned: () -> kotlin.Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val seats = info?.seats.orEmpty()
    val myTeams = seats.filter { it.username == me }.mapNotNull { it.team }.toSet()
    // a teammate who still plays keeps the team in the game
    val teammateLeft = seats.any { it.username != null && it.username != me && !it.resigned && it.team != null && it.team in myTeams }
    val consequence = when {
        info?.vsAi == true -> if (teammateLeft) "The AI plays your nations; your teammates play on." else "The game ends. It counts as left in your stats against the AI."
        teammateLeft -> "The AI plays your nations; your teammates play on."
        else -> "Your team loses: the other side wins. It counts as resigned."
    }
    AppDialog(
        title = "Give up?",
        subtitle = "$consequence This cannot be undone.",
        onDismiss = onDismiss,
        status = error,
        statusColor = MaterialTheme.colorScheme.error,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { session.api.resign(session.gameId) } }
                            .onSuccess { onResigned() }.onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                    }
                },
            ) { Text("Give up") }
        },
    ) {}
}

/** Correspondence games: the player's clock stops for some days (at most 14 per game, not ended early). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VacationDialog(session: OnlineSession, onDismiss: () -> kotlin.Unit) {
    var days by remember { mutableIntStateOf(3) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppDialog(
        title = "Vacation",
        subtitle = "Clock paused · max 14 days per game · can't be ended early",
        onDismiss = onDismiss,
        status = error,
        statusColor = MaterialTheme.colorScheme.error,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { session.api.vacation(session.gameId, days) } }
                        .onSuccess { onDismiss() }.onFailure { error = it.message ?: it.javaClass.simpleName }
                    busy = false
                }
            }) { Text("Take vacation") }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 3, 5, 7, 14).forEach { FilterChip(selected = days == it, onClick = { days = it }, label = { Text(if (it == 1) "1 day" else "$it days") }) }
        }
    }
}

/**
 * The online screens' server call: [call] on IO while [busy] is set, then [done] with its result on Main, a failure
 * as [setError]'s text (null clears it at the start), and [after] either way. A tap while one runs does nothing
 * (a double tap lands before recomposition disables the button).
 */
internal fun <T> CoroutineScope.runBusy(
    busy: MutableState<Boolean>,
    setError: (String?) -> kotlin.Unit,
    call: () -> T,
    after: () -> kotlin.Unit = {},
    done: (T) -> kotlin.Unit,
) {
    if (busy.value) return
    busy.value = true
    setError(null)
    launch {
        withContext(Dispatchers.IO) { runCatching(call) }
            .onSuccess(done)
            .onFailure { setError(it.message ?: it.javaClass.simpleName) }
        busy.value = false
        after()
    }
}
