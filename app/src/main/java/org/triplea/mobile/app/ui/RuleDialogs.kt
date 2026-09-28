package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.RepairRule
import games.strategy.engine.data.Resource
import games.strategy.engine.data.TechnologyFrontier
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.Constants
import games.strategy.triplea.Properties
import games.strategy.triplea.delegate.TechAdvance
import games.strategy.triplea.delegate.TechTracker
import games.strategy.triplea.delegate.TechnologyDelegate
import games.strategy.triplea.delegate.data.TechRoll
import java.util.Optional
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.app.game.KamikazeRequest
import org.triplea.mobile.app.game.PickTerritoryAndUnitsRequest
import org.triplea.mobile.app.game.RepairItem
import org.triplea.mobile.app.game.RepairRequest
import org.triplea.mobile.app.game.ScrambleRequest
import org.triplea.mobile.app.game.TechRequest
import org.triplea.mobile.app.render.ImageCache
import org.triplea.util.Tuple

/*
 * Dialogs for the optional rules a map may use: technology research, repairing bombing damage,
 * scrambling aircraft into a battle, kamikaze suicide attacks and picking a starting territory.
 */

/** A small heading between the groups of a dialog list. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

private fun costText(costs: IntegerMap<Resource>): String =
    costs.keySet().joinToString(", ") { "${costs.getInt(it)} ${it.name}" }

// ---- technology --------------------------------------------------------------------------------

/**
 * The technology phase. Two models exist: the classic one rolls dice bought for PUs (some maps
 * let the player pick the technology, otherwise a hit discovers a random one), the WW2V3 one buys
 * research tokens that accumulate over turns and are rolled for a chosen field of research.
 */
@Composable
fun TechDialog(request: TechRequest) {
    val player = request.player
    val data = player.data
    val props = data.properties
    val ww2v3 = remember(request) { Properties.getWW2V3TechModel(props) }
    val selectable = remember(request) { Properties.getWW2V2(props) || Properties.getSelectableTechRoll(props) }
    val lowLuck = remember(request) { Properties.getLowLuckTechOnly(props) }
    val available = remember(request) { TechnologyDelegate.getAvailableTechs(player, data.technologyFrontier) }
    val cost = remember(request) { runCatching { TechTracker.getTechCost(player) }.getOrDefault(5).coerceAtLeast(1) }
    val pus = remember(request) { player.resources.getQuantity(Constants.PUS) }
    val tokens = remember(request) { if (ww2v3) player.resources.getQuantity(Constants.TECH_TOKENS) else 0 }
    val maxBuy = pus / cost
    val diceSides = data.diceSides
    val categories = remember(request) {
        if (ww2v3) TechAdvance.getPlayerTechCategories(player).filter { category -> category.techs.any { it in available } } else emptyList()
    }
    var count by remember(request) { mutableIntStateOf(if (ww2v3) 0 else minOf(1, maxBuy)) }
    var category by remember(request) { mutableStateOf(categories.firstOrNull()) }
    var advance by remember(request) { mutableStateOf<TechAdvance?>(if (available.size == 1) available[0] else null) }
    val totalRolls = if (ww2v3) tokens + count else count
    val ready = totalRolls > 0 && (!ww2v3 || category != null) && (ww2v3 || !selectable || advance != null)

    fun finish() {
        val chosen = advance
        val roll = when {
            ww2v3 -> TechRoll(category, tokens + count, count)
            chosen != null -> TechRoll(TechnologyFrontier("", data).also { it.addAdvance(chosen) }, count)
            else -> TechRoll(null, count)
        }
        request.complete(Optional.of(roll))
    }

    val subtitle = when {
        ww2v3 -> "Research tokens cost $cost PUs each and are kept until a discovery. Every token rolls one die; a $diceSides discovers a technology from the chosen field."
        lowLuck -> "Each roll costs $cost PUs. Every $diceSides dice discover one technology; the rest is rolled for."
        else -> "Each roll costs $cost PUs. A $diceSides discovers a technology."
    }
    AppDialog(
        title = "Technology",
        subtitle = subtitle,
        onDismiss = null,
        status = "$pus PUs" + (if (ww2v3) " · $tokens tokens" else "") + (if (count > 0) " · spend ${count * cost}" else ""),
        statusColor = if (count > 0) MaterialTheme.colorScheme.primary else null,
        buttons = {
            TextButton(onClick = { request.complete(Optional.empty()) }) { Text("No research") }
            ConfirmButton(enabled = ready) { finish() }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                CountRow(
                    title = if (ww2v3) "Tokens to buy" else "Dice to roll",
                    subtitle = if (maxBuy == 0) "Not enough PUs" else "up to $maxBuy for the PUs you have",
                    value = count,
                    max = maxBuy,
                    onChange = { count = it.coerceIn(0, maxBuy) },
                )
            }
            when {
                ww2v3 -> {
                    item { SectionLabel("Field of research") }
                    items(categories) { c ->
                        OptionRow(
                            title = c.name,
                            subtitle = c.techs.filter { it in available }.joinToString(", ") { it.name },
                            emphasized = c == category,
                            onClick = { category = c },
                        )
                    }
                }
                selectable -> {
                    item { SectionLabel("Technology to research") }
                    items(available) { a ->
                        OptionRow(title = a.name, emphasized = a == advance, onClick = { advance = a })
                    }
                }
                else -> {
                    item { InfoNote("A hit discovers one of these at random: " + available.joinToString(", ") { it.name }) }
                }
            }
        }
    }
}

// ---- repairs -----------------------------------------------------------------------------------

/** Repairing bombing damage on factories (and other damageable units) before the purchase. */
@Composable
fun RepairDialog(request: RepairRequest, images: ImageCache?) {
    val player = request.player
    val counts = remember(request) { mutableStateMapOf<RepairItem, Int>() }
    val resources = remember(request) { request.items.flatMap { it.rule.costs.keySet() }.distinct() }
    val available = remember(request) { resources.associateWith { player.resources.getQuantity(it) } }
    val spent: Map<Resource, Int> = resources.associateWith { resource ->
        request.items.sumOf { (counts[it] ?: 0) * it.rule.costs.getInt(resource) }
    }
    val affordable = resources.all { (spent[it] ?: 0) <= (available[it] ?: 0) }
    val total = counts.values.sum()

    /** How many repairs the damage allows: enough applications to clear it. */
    fun repairsNeeded(item: RepairItem): Int = (item.damage + item.pointsPerRepair - 1) / item.pointsPerRepair

    /** How many more repairs of [item] the remaining resources pay for. */
    fun affordableMore(item: RepairItem): Int {
        var room = Int.MAX_VALUE
        for (resource in resources) {
            val per = item.rule.costs.getInt(resource)
            if (per <= 0) continue
            room = minOf(room, ((available[resource] ?: 0) - (spent[resource] ?: 0)) / per)
        }
        return room.coerceAtLeast(0)
    }

    fun repairAll() {
        counts.clear()
        request.items.forEach { item -> counts[item] = minOf(repairsNeeded(item), affordableMore(item)) }
    }

    fun finish() {
        val map = HashMap<Unit, IntegerMap<RepairRule>>()
        counts.forEach { (item, n) -> if (n > 0) map[item.unit] = IntegerMap<RepairRule>().also { it.put(item.rule, n) } }
        request.complete(Optional.of(map))
    }

    AppDialog(
        title = "Repair damage",
        subtitle = "Bombing damage reduces what a factory can produce. Repairs are paid before the purchase.",
        onDismiss = null,
        status = resources.joinToString(" · ") { "${spent[it] ?: 0} of ${available[it] ?: 0} ${it.name}" },
        statusColor = when {
            !affordable -> MaterialTheme.colorScheme.error
            total > 0 -> MaterialTheme.colorScheme.primary
            else -> null
        },
        buttons = {
            TextButton(onClick = { request.complete(Optional.empty()) }) { Text("Skip") }
            ConfirmButton(enabled = total > 0 && affordable) { finish() }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { counts.clear() }, enabled = total > 0) { Text("None") }
            TextButton(onClick = { repairAll() }) { Text("Repair all") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(request.items) { item ->
                val current = counts[item] ?: 0
                val perRepair = costText(item.rule.costs)
                CountRow(
                    title = "${item.unit.type.name} in ${item.territory}",
                    subtitle = "${item.damage} damage · $perRepair per " + (if (item.pointsPerRepair == 1) "point" else "${item.pointsPerRepair} points"),
                    value = current,
                    max = minOf(repairsNeeded(item), current + affordableMore(item)),
                    onChange = { counts[item] = it.coerceIn(0, repairsNeeded(item)) },
                    leading = { UnitIcon(images, item.unit.type, item.unit.owner, size = 30) },
                )
            }
        }
    }
}

// ---- scramble ----------------------------------------------------------------------------------

private data class ScrambleKey(val option: Int, val group: UnitGroupKey)

/** The defender's choice which aircraft take off from nearby air bases to join a battle. */
@Composable
fun ScrambleDialog(request: ScrambleRequest, images: ImageCache?) {
    val groups = remember(request) { request.options.map { option -> option.units.groupBy { unitGroupKey(it) } } }
    val counts = remember(request) { mutableStateMapOf<ScrambleKey, Int>() }
    val total = counts.values.sum()
    fun totalFor(option: Int): Int = counts.entries.sumOf { (key, n) -> if (key.option == option) n else 0 }

    fun finish() {
        val map = HashMap<Territory, Collection<Unit>>()
        request.options.forEachIndexed { i, option ->
            val chosen = ArrayList<Unit>()
            groups[i].forEach { (key, units) -> chosen += units.take(counts[ScrambleKey(i, key)] ?: 0) }
            if (chosen.isNotEmpty()) map[option.from] = chosen
        }
        request.complete(map)
    }

    AppDialog(
        title = "Scramble to ${request.scrambleTo.name}?",
        subtitle = "Aircraft at nearby air bases can take off and defend ${request.scrambleTo.name}. They return to their base after the battle.",
        onDismiss = null,
        status = if (total == 0) "No aircraft scramble" else "$total scrambling",
        statusColor = if (total > 0) MaterialTheme.colorScheme.primary else null,
        buttons = {
            TextButton(onClick = { request.complete(emptyMap()) }) { Text("Stay") }
            ConfirmButton(enabled = total > 0) { finish() }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            request.options.forEachIndexed { i, option ->
                item {
                    SectionLabel(option.from.name + (if (option.max < option.units.size) " · up to ${option.max}" else ""))
                }
                items(groups[i].entries.toList()) { (key, units) ->
                    val current = counts[ScrambleKey(i, key)] ?: 0
                    val room = option.max - totalFor(i)
                    val sample = units.first()
                    CountRow(
                        title = key.title(),
                        subtitle = "${units.size} available",
                        value = current,
                        max = minOf(units.size, current + room),
                        onChange = { counts[ScrambleKey(i, key)] = it.coerceIn(0, units.size) },
                        leading = { UnitIcon(images, sample.type, sample.owner, size = 30) },
                    )
                }
            }
        }
    }
}

// ---- kamikaze ----------------------------------------------------------------------------------

/** Kamikaze suicide attacks against enemy ships in the player's kamikaze zones. */
@Composable
fun KamikazeDialog(request: KamikazeRequest, images: ImageCache?) {
    val counts = remember(request) { mutableStateMapOf<Unit, Int>() }
    val used = counts.values.sum()
    val room = request.maxAttacks - used

    fun finish() {
        val map = HashMap<Territory, IntegerMap<Unit>>()
        request.targets.forEach { (territory, units) ->
            val attacks = IntegerMap<Unit>()
            units.forEach { unit -> (counts[unit] ?: 0).let { if (it > 0) attacks.put(unit, it) } }
            if (!attacks.isEmpty) map[territory] = attacks
        }
        request.complete(map)
    }

    AppDialog(
        title = "Kamikaze attacks",
        subtitle = "Spend ${request.resource.name} on suicide attacks against enemy ships. Each attack rolls one die and hits on a ${request.attackValue} or less.",
        onDismiss = null,
        status = "$used of ${request.maxAttacks} attacks",
        statusColor = if (used > 0) MaterialTheme.colorScheme.primary else null,
        buttons = {
            TextButton(onClick = { request.complete(emptyMap()) }) { Text("No attacks") }
            ConfirmButton(enabled = used > 0) { finish() }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            request.targets.forEach { (territory, units) ->
                item { SectionLabel(territory.name) }
                items(units) { unit ->
                    val current = counts[unit] ?: 0
                    CountRow(
                        title = unit.type.name,
                        subtitle = unit.owner.name + (if (unit.hits > 0) " · damaged" else ""),
                        value = current,
                        max = current + room,
                        onChange = { counts[unit] = it.coerceAtLeast(0) },
                        leading = { UnitIcon(images, unit.type, unit.owner, size = 30) },
                    )
                }
            }
        }
    }
}

// ---- random start ------------------------------------------------------------------------------

/** A map with a random start: the player picks a territory and the units that begin there. */
@Composable
fun PickTerritoryAndUnitsDialog(request: PickTerritoryAndUnitsRequest, images: ImageCache?) {
    val required = minOf(request.unitsPerPick, request.units.size)
    var territory by remember(request) { mutableStateOf(request.territories.singleOrNull()) }
    var filter by remember(request) { mutableStateOf("") }
    val groups = remember(request) { request.units.groupBy { unitGroupKey(it) } }
    val counts = remember(request) { mutableStateMapOf<UnitGroupKey, Int>() }
    val total = counts.values.sum()
    val sorted = remember(request) { request.territories.sortedBy { it.name } }
    val shown = if (filter.isBlank()) sorted else sorted.filter { it.name.contains(filter.trim(), ignoreCase = true) }

    fun finish() {
        val chosen = HashSet<Unit>()
        groups.forEach { (key, units) -> chosen += units.take(counts[key] ?: 0) }
        request.complete(Tuple.of(territory, chosen))
    }

    AppDialog(
        title = "Pick a territory",
        subtitle = if (required > 0) "Choose a territory and the $required units that start there." else "Choose a territory.",
        onDismiss = null,
        status = (territory?.name ?: "No territory") + (if (required > 0) " · $total of $required units" else ""),
        statusColor = if (territory != null && total == required) MaterialTheme.colorScheme.primary else null,
        buttons = { ConfirmButton(enabled = territory != null && total == required) { finish() } },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (required > 0) {
                item { SectionLabel("Units") }
                items(groups.entries.toList()) { (key, units) ->
                    val current = counts[key] ?: 0
                    val sample = units.first()
                    CountRow(
                        title = key.title(),
                        subtitle = "${units.size} left",
                        value = current,
                        max = minOf(units.size, current + (required - total)),
                        onChange = { counts[key] = it.coerceIn(0, units.size) },
                        leading = { UnitIcon(images, sample.type, sample.owner, size = 30) },
                    )
                }
            }
            item { SectionLabel("Territory") }
            if (sorted.size > 8) {
                item {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(shown) { t ->
                OptionRow(title = t.name, emphasized = t == territory, onClick = { territory = t })
            }
        }
    }
}
