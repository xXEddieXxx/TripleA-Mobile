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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** A unit type of one nation, the key of the count tables. */
private data class Side(val owner: GamePlayer, val type: UnitType)

/** What one simulated battle setup produced, reduced to what the screen shows. */
private class CalcOutcome(
    val attackerWin: Double,
    val defenderWin: Double,
    val draw: Double,
    val attackersLeft: List<Pair<Side, Int>>,
    val defendersLeft: List<Pair<Side, Int>>,
    val rounds: Double,
    val tuvSwing: Double,
    val runs: Int,
    val millis: Long,
)

/**
 * The desktop's battle calculator: the territory (picked on the map), one attacking nation, the
 * defending nations with their units, a few hundred simulated battles with the engine's own battle
 * code, and who wins how often with what left. Opens with the tapped territory and the units in it:
 * the attacker's units on one side, every hostile nation's units on the other.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BattleCalcScreen(
    session: LocalGameSession,
    images: ImageCache,
    territoryName: String?,
    attackerName: String,
    onBack: () -> kotlin.Unit,
    /** Closes the calculator so the next tap on the map chooses its territory. */
    onPickOnMap: () -> kotlin.Unit,
    /** The attacking nation chosen here, so it survives a trip to the map. */
    onAttacker: (String) -> kotlin.Unit = {},
) {
    val data = session.gameData
    val scope = rememberCoroutineScope()
    val players = remember(data) { data.playerList.players.filter { !it.isNull } }
    val territory = remember(territoryName) { territoryName?.let { data.map.getTerritoryOrNull(it) } }
    var attacker by remember { mutableStateOf(players.firstOrNull { it.name == attackerName } ?: players.first()) }
    fun hostile(p: GamePlayer) = p != attacker && !p.isNull && !data.relationshipTracker.isAllied(attacker, p)

    // the unit types each side may bring: everything that can fight, in the purchase order
    val types = remember(data) {
        data.unitTypeList.allUnitTypes
            .filter { runCatching { !it.unitAttachment.isInfrastructure }.getOrDefault(false) }
            .sortedWith(compareBy<UnitType> { val ua = it.unitAttachment; if (ua.isAir) 1 else if (ua.isSea) 2 else 0 }.thenBy { it.name })
    }
    val attacking = remember { mutableStateMapOf<UnitType, Int>() }
    /** The defending nations in order; the first is the one the engine treats as the defender. */
    val defenders = remember { mutableStateListOf<GamePlayer>() }
    val defending = remember { mutableStateMapOf<Side, Int>() }

    // the territory decides the setup: the attacker's units against everything hostile in it
    LaunchedEffect(territory, attacker) {
        val setup = withContext(Dispatchers.Default) {
            data.acquireReadLock().use {
                val units = territory?.units?.toList().orEmpty().filter { !Matches.unitIsInfrastructure().test(it) }
                val mine = units.filter { it.owner == attacker }.groupingBy { it.type }.eachCount()
                val theirs = units.filter { hostile(it.owner) }
                val owner = territory?.owner?.takeIf { hostile(it) }
                // the owner of the territory first, then the other hostile nations standing there
                val nations = (listOfNotNull(owner) + theirs.map { it.owner }.distinct()).distinct()
                    .ifEmpty { listOfNotNull(players.firstOrNull { hostile(it) } ?: players.firstOrNull { it != attacker }) }
                Triple(mine, nations, theirs.groupingBy { Side(it.owner, it.type) }.eachCount())
            }
        }
        attacking.clear(); attacking.putAll(setup.first)
        defenders.clear(); defenders.addAll(setup.second)
        defending.clear(); defending.putAll(setup.third)
    }
    LaunchedEffect(attacker) { onAttacker(attacker.name) }

    val calculator = remember(data) { BattleCalculator(data) }
    DisposableEffect(calculator) { onDispose { runCatching { calculator.cancel() } } }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<CalcOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableIntStateOf(200) }
    var side by remember { mutableIntStateOf(0) }
    var pickAttacker by remember { mutableStateOf(false) }
    var pickDefenders by remember { mutableStateOf(false) }

    fun run() {
        val t = territory ?: return
        val defender = defenders.firstOrNull()
        if (attacking.values.sum() == 0 || defending.values.sum() == 0 || defender == null) {
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
                    defending.forEach { (key, n) -> if (n > 0) defendUnits += key.type.create(n, key.owner) }
                    val effects = data.acquireReadLock().use { TerritoryEffectHelper.getEffects(t) }
                    val results: AggregateResults = calculator.calculate(attacker, defender, t, attackUnits, defendUnits, emptyList(), effects, false, runs)
                    fun left(units: Collection<Unit>) = units.groupingBy { Side(it.owner, it.type) }.eachCount().entries
                        .sortedWith(compareBy<Map.Entry<Side, Int>> { it.key.owner.name }.thenBy { it.key.type.name })
                        .map { it.key to it.value }
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

    val defenderLabel = if (defenders.isEmpty()) "Defender" else defenders.joinToString(", ") { it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battle calculator") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ---- where (from the map), and who against whom
            OutlinedButton(onClick = onPickOnMap, modifier = Modifier.fillMaxWidth()) {
                Text(territory?.name ?: "Tap a territory on the map", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("map", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickAttacker = true }, modifier = Modifier.weight(1f)) {
                    Text(attacker.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("  ›››  ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { pickDefenders = true }, modifier = Modifier.weight(1f)) {
                    Text(defenderLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ---- the result, right under the setup so it stays in view while units change
            val result = outcome
            if (running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            } else if (result != null) {
                ResultCard(result, attacker, defenderLabel, images)
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
                Tab(selected = side == 1, onClick = { side = 1 }, text = { Text("Defence (${defending.values.sum()})", maxLines = 1) })
            }
            if (side == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { attacking.clear() }, enabled = attacking.values.sum() > 0) { Text("None") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    types.forEach { type ->
                        UnitCountRow(type, attacker, attack = true, value = attacking[type] ?: 0, images = images) { attacking[type] = it }
                    }
                }
            } else {
                // one block per defending nation; several nations defend together, like in the game
                defenders.forEach { nation ->
                    val own = defending.filterKeys { it.owner == nation }.values.sum()
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NationFlagSmall(nation.name, images)
                        Spacer(Modifier.width(8.dp))
                        Text(nation.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { types.forEach { defending.remove(Side(nation, it)) } }, enabled = own > 0) { Text("None") }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        types.forEach { type ->
                            UnitCountRow(type, nation, attack = false, value = defending[Side(nation, type)] ?: 0, images = images) {
                                if (it == 0) defending.remove(Side(nation, type)) else defending[Side(nation, type)] = it
                            }
                        }
                    }
                }
                TextButton(onClick = { pickDefenders = true }, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)) { Text("+ nation") }
            }
        }
    }

    if (pickAttacker) {
        AppDialog(title = "Attacker", onDismiss = { pickAttacker = false }, buttons = {}) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(players, key = { it.name }) { p ->
                    OptionRow(
                        title = p.name,
                        emphasized = p == attacker,
                        leading = { NationFlagSmall(p.name, images) },
                        onClick = { attacker = p; outcome = null; pickAttacker = false },
                    )
                }
            }
        }
    }
    if (pickDefenders) {
        // tap a nation to add it to the defence or take it out; the first one listed is the main defender
        AppDialog(title = "Defending nations", onDismiss = { pickDefenders = false }, buttons = {}) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(players.filter { it != attacker }, key = { it.name }) { p ->
                    val included = p in defenders
                    OptionRow(
                        title = p.name,
                        subtitle = if (included) "defends" else null,
                        emphasized = included,
                        leading = { NationFlagSmall(p.name, images) },
                        onClick = {
                            if (included) {
                                defenders.remove(p)
                                types.forEach { defending.remove(Side(p, it)) }
                            } else {
                                defenders.add(p)
                            }
                            outcome = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitCountRow(type: UnitType, owner: GamePlayer, attack: Boolean, value: Int, images: ImageCache, onChange: (Int) -> kotlin.Unit) {
    val ua = type.unitAttachment
    val strength = if (attack) ua.getAttack(owner) else ua.getDefense(owner)
    CountRow(
        title = type.name,
        subtitle = (if (attack) "attack " else "defence ") + strength + if (ua.hitPoints > 1) "  ·  ${ua.hitPoints} hp" else "",
        value = value,
        max = 99,
        onChange = { onChange(it.coerceIn(0, 99)) },
        leading = { UnitIcon(images, type, owner, size = 30) },
    )
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
@Composable
private fun ResultCard(result: CalcOutcome, attacker: GamePlayer, defenderLabel: String, images: ImageCache) {
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
                Text("$defenderLabel  ${(d * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // what is left on average, per side
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                LeftOver(result.attackersLeft, images, Modifier.weight(1f), end = false)
                Spacer(Modifier.width(8.dp))
                LeftOver(result.defendersLeft, images, Modifier.weight(1f), end = true)
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
private fun LeftOver(units: List<Pair<Side, Int>>, images: ImageCache, modifier: Modifier, end: Boolean) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, if (end) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        if (units.isEmpty()) {
            Text("nothing left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        units.forEach { (key, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnitIcon(images, key.type, key.owner, size = 22)
                Text("×$n", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
