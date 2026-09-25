package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.engine.data.UnitType
import games.strategy.triplea.delegate.Matches
import games.strategy.triplea.delegate.TerritoryEffectHelper
import games.strategy.triplea.odds.calculator.AggregateResults
import games.strategy.triplea.odds.calculator.BattleCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.app.render.ImageCache

/** What one simulated battle setup produced, reduced to what the screen shows. */
private class CalcOutcome(
    val attackerWin: Double,
    val defenderWin: Double,
    val draw: Double,
    val attackersLeft: List<Pair<UnitType, Int>>,
    val defendersLeft: List<Pair<UnitType, Int>>,
    val rounds: Double,
    val tuvSwing: Double,
    val runs: Int,
    val millis: Long,
)

/**
 * The desktop's battle calculator: pick the territory, the two nations and their units, run a few
 * hundred simulated battles with the engine's own battle code, and read off who wins how often
 * and what is left. Opens with the tapped territory and the units standing in it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BattleCalcScreen(
    session: LocalGameSession,
    images: ImageCache,
    territoryName: String?,
    attackerName: String,
    /** The defender chosen before the map was used to pick another territory, if any. */
    defenderName: String? = null,
    onBack: () -> kotlin.Unit,
    /** Closes the calculator so the next tap on the map chooses its territory. */
    onPickOnMap: () -> kotlin.Unit,
    /** The nations chosen here, so they survive a trip to the map. */
    onNations: (attacker: String, defender: String) -> kotlin.Unit = { _, _ -> },
) {
    val data = session.gameData
    val scope = rememberCoroutineScope()
    val players = remember(data) { data.playerList.players.filter { !it.isNull } }
    var territory by remember { mutableStateOf(territoryName?.let { data.map.getTerritoryOrNull(it) }) }
    var attacker by remember { mutableStateOf(players.firstOrNull { it.name == attackerName } ?: players.first()) }
    var defender by remember(territory, attacker) {
        mutableStateOf(
            players.firstOrNull { it.name == defenderName && it != attacker } ?: territory?.let { t ->
                data.acquireReadLock().use {
                    t.units.map { it.owner }.filter { !it.isNull && it != attacker && !data.relationshipTracker.isAllied(attacker, it) }.firstOrNull()
                        ?: t.owner.takeIf { !it.isNull && it != attacker }
                }
            } ?: players.firstOrNull { it != attacker && !data.relationshipTracker.isAllied(attacker, it) } ?: players.first { it != attacker },
        )
    }
    // the unit types each side may bring: everything that can fight, in the purchase order
    val types = remember(data) {
        data.unitTypeList.allUnitTypes
            .filter { runCatching { !it.unitAttachment.isInfrastructure }.getOrDefault(false) }
            .sortedWith(compareBy<UnitType> { val ua = it.unitAttachment; if (ua.isAir) 1 else if (ua.isSea) 2 else 0 }.thenBy { it.name })
    }
    val attacking = remember { mutableStateMapOf<UnitType, Int>() }
    val defending = remember { mutableStateMapOf<UnitType, Int>() }
    // opening the calculator on a territory: the units standing there fill the two sides
    LaunchedEffect(territory, attacker, defender) {
        val t = territory ?: return@LaunchedEffect
        val (mine, theirs) = withContext(Dispatchers.Default) {
            data.acquireReadLock().use {
                val units = t.units.toList()
                val mine = units.filter { it.owner == attacker && !Matches.unitIsInfrastructure().test(it) }
                val theirs = units.filter { it.owner == defender && !Matches.unitIsInfrastructure().test(it) }
                mine.groupingBy { it.type }.eachCount() to theirs.groupingBy { it.type }.eachCount()
            }
        }
        attacking.clear(); attacking.putAll(mine)
        defending.clear(); defending.putAll(theirs)
    }

    val calculator = remember(data) { BattleCalculator(data) }
    DisposableEffect(calculator) { onDispose { runCatching { calculator.cancel() } } }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<CalcOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableIntStateOf(200) }
    var side by remember { mutableIntStateOf(0) }
    var pickPlayer by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(attacker, defender) { onNations(attacker.name, defender.name) }

    fun run() {
        val t = territory ?: return
        if (attacking.values.sum() == 0 || defending.values.sum() == 0) {
            error = "Both sides need units."
            return
        }
        error = null
        running = true
        job = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val attackUnits = ArrayList<Unit>()
                    attacking.forEach { (type, n) -> if (n > 0) attackUnits += type.create(n, attacker) }
                    val defendUnits = ArrayList<Unit>()
                    defending.forEach { (type, n) -> if (n > 0) defendUnits += type.create(n, defender) }
                    val effects = data.acquireReadLock().use { TerritoryEffectHelper.getEffects(t) }
                    val results: AggregateResults = calculator.calculate(attacker, defender, t, attackUnits, defendUnits, emptyList(), effects, false, runs)
                    fun left(units: Collection<Unit>) = units.groupingBy { it.type }.eachCount().entries.sortedBy { it.key.name }.map { it.key to it.value }
                    CalcOutcome(
                        attackerWin = results.attackerWinPercent,
                        defenderWin = results.defenderWinPercent,
                        draw = results.drawPercent,
                        attackersLeft = left(results.averageAttackingUnitsRemaining),
                        defendersLeft = left(results.averageDefendingUnitsRemaining),
                        rounds = results.averageBattleRoundsFought,
                        tuvSwing = runCatching { results.getAverageTuvSwing(attacker, attackUnits, defender, defendUnits, data) }.getOrDefault(0.0),
                        runs = runs,
                        millis = results.time,
                    )
                }
            }
            running = false
            result.onSuccess { outcome = it }.onFailure { error = it.message ?: "The calculation failed." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battle calculator") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ---- where, and who against whom
            // the territory comes from the map: this closes the calculator until the next tap
            OutlinedButton(onClick = onPickOnMap, modifier = Modifier.fillMaxWidth()) {
                Text(territory?.name ?: "Tap a territory on the map", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("map", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickPlayer = 0 }, modifier = Modifier.weight(1f)) {
                    Text(attacker.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("  ›››  ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { pickPlayer = 1 }, modifier = Modifier.weight(1f)) {
                    Text(defender.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ---- the result, right under the setup so it stays in view while units change
            val result = outcome
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            } else if (result != null) {
                ResultCard(result, attacker, defender, images)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Runs", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                listOf(200, 1000, 5000).forEach { n ->
                    TextButton(onClick = { runs = n }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("$n", fontWeight = if (runs == n) FontWeight.Bold else null, color = if (runs == n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (running) {
                    TextButton(onClick = { job?.cancel(); runCatching { calculator.cancel() }; running = false }) { Text("Stop") }
                } else {
                    Button(onClick = { run() }, enabled = territory != null) { Text("Calculate") }
                }
            }

            // ---- the units of each side
            PrimaryTabRow(selectedTabIndex = side, modifier = Modifier.padding(top = 8.dp)) {
                Tab(selected = side == 0, onClick = { side = 0 }, text = { Text("${attacker.name} (${attacking.values.sum()})", maxLines = 1) })
                Tab(selected = side == 1, onClick = { side = 1 }, text = { Text("${defender.name} (${defending.values.sum()})", maxLines = 1) })
            }
            val counts = if (side == 0) attacking else defending
            val owner = if (side == 0) attacker else defender
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { counts.clear() }, enabled = counts.values.sum() > 0) { Text("None") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                types.forEach { type ->
                    val ua = type.unitAttachment
                    val value = if (side == 0) ua.getAttack(owner) else ua.getDefense(owner)
                    CountRow(
                        title = type.name,
                        subtitle = (if (side == 0) "attack " else "defence ") + value + if (ua.hitPoints > 1) "  ·  ${ua.hitPoints} hp" else "",
                        value = counts[type] ?: 0,
                        max = 99,
                        onChange = { counts[type] = it.coerceIn(0, 99) },
                        leading = { UnitIcon(images, type, owner, size = 30) },
                    )
                }
            }
        }
    }

    pickPlayer?.let { which ->
        AppDialog(title = if (which == 0) "Attacker" else "Defender", onDismiss = { pickPlayer = null }, buttons = {}) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(players, key = { it.name }) { p ->
                    OptionRow(
                        title = p.name,
                        emphasized = p == (if (which == 0) attacker else defender),
                        leading = { NationFlagSmall(p.name, images) },
                        onClick = {
                            if (which == 0) attacker = p else defender = p
                            outcome = null
                            pickPlayer = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NationFlagSmall(name: String, images: ImageCache) {
    val flag = remember(name, images) { images.getNow("flags/$name.png", listOf("flags/$name.png", "flags/${name}_small.png")) }
    if (flag != null) {
        Image(flag.asImageBitmap(), contentDescription = name, modifier = Modifier.height(18.dp))
    } else {
        Spacer(Modifier.width(1.dp))
    }
}

/** Win chances as a bar, what is left on average, rounds and the value swing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(result: CalcOutcome, attacker: GamePlayer, defender: GamePlayer, images: ImageCache) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            // the bar: attacker share, draws, defender share
            val a = result.attackerWin.toFloat().coerceIn(0f, 1f)
            val d = result.defenderWin.toFloat().coerceIn(0f, 1f)
            val x = (1f - a - d).coerceIn(0f, 1f)
            Row(Modifier.fillMaxWidth().height(14.dp)) {
                if (a > 0f) Spacer(Modifier.weight(a).fillMaxSize().padding(end = 1.dp).background(MaterialTheme.colorScheme.primary))
                if (x > 0f) Spacer(Modifier.weight(x).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
                if (d > 0f) Spacer(Modifier.weight(d).fillMaxSize().padding(start = 1.dp).background(MaterialTheme.colorScheme.tertiary))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("${(a * 100).toInt()}%  ${attacker.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (x > 0.005f) Text("${(x * 100).toInt()}% draw", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${defender.name}  ${(d * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            // what is left on average, per side
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                LeftOver(result.attackersLeft, attacker, images, Modifier.weight(1f), end = false)
                Spacer(Modifier.width(8.dp))
                LeftOver(result.defendersLeft, defender, images, Modifier.weight(1f), end = true)
            }
            Text(
                String.format(java.util.Locale.ROOT, "%.1f rounds  ·  value swing %+.0f  ·  %d runs, %.1f s", result.rounds, result.tuvSwing, result.runs, result.millis / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeftOver(units: List<Pair<UnitType, Int>>, owner: GamePlayer, images: ImageCache, modifier: Modifier, end: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (end) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        if (units.isEmpty()) {
            Text("nothing left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        units.forEach { (type, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnitIcon(images, type, owner, size = 22)
                Text("×$n", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

