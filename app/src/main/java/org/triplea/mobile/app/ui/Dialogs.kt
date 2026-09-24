package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.Resource
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.engine.data.UnitType
import games.strategy.triplea.attachments.AbstractUserActionAttachment
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.ui.PoliticsText
import games.strategy.triplea.ui.UserActionText
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.game.MadeMove
import org.triplea.mobile.app.game.PoliticsRequest
import org.triplea.mobile.app.game.UserActionRequest
import games.strategy.triplea.delegate.Matches
import games.strategy.triplea.delegate.battle.IBattle
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.FightBattleDetails
import java.util.Optional
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.UnitImageNames
import org.triplea.mobile.app.game.BattleRequest
import org.triplea.mobile.app.game.CasualtyRequest
import org.triplea.mobile.app.game.ConfirmRequest
import org.triplea.mobile.app.game.PurchaseRequest
import org.triplea.mobile.app.game.RetreatRequest
import org.triplea.mobile.app.game.SelectTerritoryRequest
import org.triplea.mobile.app.render.ImageCache

/** Specification of a unit chooser dialog: which units, how many at most, what to do with them. */
class UnitPickerSpec(
    val title: String,
    val message: String,
    val units: List<Unit>,
    /** The most units that count towards the limit; see [countsTowardMax]. */
    val max: Int,
    val initialSelection: Map<UnitGroupKey, Int> = emptyMap(),
    val onConfirm: (List<Unit>) -> kotlin.Unit,
    val onCancel: () -> kotlin.Unit,
    /** Which units use up the limit; constructions (factories) are placed outside of it. */
    val countsTowardMax: (Unit) -> Boolean = { true },
)

data class UnitGroupKey(val type: String, val owner: String, val damaged: Boolean)

private fun groupKey(unit: Unit) = UnitGroupKey(
    unit.type.name,
    unit.owner.name,
    Matches.unitHasTakenSomeBombingUnitDamage().test(unit) || unit.hits > 0,
)

/**
 * The height a list inside a dialog may take: the preferred height, but never more than about
 * half the screen, so the dialog's buttons stay on screen on small phones in landscape.
 */
@Composable
fun dialogListHeight(preferred: Dp): Dp {
    val screen = LocalConfiguration.current.screenHeightDp.dp
    return minOf(preferred, screen * 0.45f)
}

@Composable
fun UnitIcon(images: ImageCache?, type: UnitType, owner: GamePlayer, size: Int = 32) {
    val bitmap = remember(images, type, owner) {
        images?.getNow(
            "units/${owner.name}/${type.name}.png",
            UnitImageNames.candidatePaths(type, owner, false, false),
        )
    }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = type.name, modifier = Modifier.size(size.dp))
    } else {
        Spacer(Modifier.size(size.dp))
    }
}

@Composable
fun UnitPickerDialog(spec: UnitPickerSpec, images: ImageCache?) {
    val groups = remember(spec) { spec.units.groupBy { groupKey(it) } }
    val counts = remember(spec) {
        mutableStateMapOf<UnitGroupKey, Int>().also { map ->
            groups.keys.forEach { key -> map[key] = spec.initialSelection[key] ?: 0 }
        }
    }
    // groups whose units use up the limit (everything except constructions)
    val limited = remember(spec) { groups.mapValues { (_, units) -> spec.countsTowardMax(units.first()) } }
    val total = counts.entries.sumOf { (key, n) -> if (limited[key] == true) n else 0 }
    val totalAll = counts.values.sum()
    // "All" when nothing limits the choice (moving), "Max" when a production limit applies (placing)
    val unlimited = spec.max >= groups.entries.sumOf { (key, units) -> if (limited[key] == true) units.size else 0 }

    fun selectMax() {
        var room = spec.max
        groups.forEach { (key, units) ->
            if (limited[key] == true) {
                val take = minOf(units.size, room)
                counts[key] = take
                room -= take
            } else {
                counts[key] = units.size
            }
        }
    }

    AppDialog(
        title = spec.title,
        subtitle = spec.message.takeIf { it.isNotBlank() },
        onDismiss = spec.onCancel,
        status = if (unlimited) "$total selected" else "$total of ${spec.max} selected",
        statusColor = if (total > 0) MaterialTheme.colorScheme.primary else null,
        buttons = {
            ConfirmButton(enabled = totalAll > 0) {
                val selected = ArrayList<Unit>()
                groups.forEach { (key, units) -> selected += units.take(counts[key] ?: 0) }
                spec.onConfirm(selected)
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { groups.keys.forEach { counts[it] = 0 } }, enabled = totalAll > 0) { Text("None") }
            TextButton(onClick = { selectMax() }) { Text(if (unlimited) "All" else "Max") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(groups.entries.toList()) { (key, units) ->
                val sample = units.first()
                val current = counts[key] ?: 0
                val allowed = if (limited[key] == true) minOf(units.size, current + (spec.max - total)) else units.size
                CountRow(
                    title = key.type + if (key.damaged) " (damaged)" else "",
                    subtitle = "${units.size} available" + if (limited[key] == false) " · does not count" else "",
                    value = current,
                    max = allowed,
                    onChange = { counts[key] = it.coerceIn(0, units.size) },
                    leading = { UnitIcon(images, sample.type, sample.owner, size = 30) },
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(request: ConfirmRequest) {
    AppDialog(
        title = request.title,
        // the X answers no, the check yes
        onDismiss = { request.complete(request.okOnly) },
        buttons = { ConfirmButton { request.complete(true) } },
    ) {
        Text(request.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

/**
 * The purchase screen: a full screen list of everything the player can build, grouped by land,
 * air and sea, with the remaining budget always visible. Rows expand to show the unit's abilities.
 */
@Composable
fun PurchaseDialog(
    request: PurchaseRequest,
    images: ImageCache?,
    capacity: Int? = null,
    /** The chosen amounts; owned by the caller so they survive hiding the screen. */
    counts: SnapshotStateMap<ProductionRule, Int> = remember(request) { mutableStateMapOf() },
    /** Hides the screen to look at the map; the phase stays open. */
    onShowMap: (() -> kotlin.Unit)? = null,
) {
    val player = request.player
    val frontier = player.productionFrontier
    val rules = remember(request) { frontier?.rules?.toList() ?: emptyList() }
    val resources = remember(request) { rules.flatMap { it.costs.keySet() }.distinct() }
    val available = remember(request) { resources.associateWith { player.resources.getQuantity(it) } }
    val spent: Map<Resource, Int> = resources.associateWith { resource ->
        rules.sumOf { rule -> (counts[rule] ?: 0) * rule.costs.getInt(resource) }
    }
    val affordable = resources.all { (spent[it] ?: 0) <= (available[it] ?: 0) }
    var warnings by remember(request) { mutableStateOf<List<String>?>(null) }
    val expanded = remember(request) { mutableStateMapOf<ProductionRule, Boolean>() }

    fun unitTypeOf(rule: ProductionRule): UnitType? {
        val resultName = rule.results.keySet().firstOrNull()?.name ?: return null
        return player.data.unitTypeList.getUnitType(resultName).orElse(null)
    }

    fun unitsIn(rule: ProductionRule): Int = rule.results.keySet().filterIsInstance<UnitType>().sumOf { rule.results.getInt(it) }

    val sections = remember(rules) {
        val grouped = rules.groupBy { rule ->
            val ua = unitTypeOf(rule)?.unitAttachment
            when {
                ua == null -> "Other"
                ua.isAir -> "Air"
                ua.isSea -> "Sea"
                else -> "Land"
            }
        }
        listOf("Land", "Air", "Sea", "Other").mapNotNull { name -> grouped[name]?.let { name to it } }
    }

    fun finish() {
        val map = IntegerMap<ProductionRule>()
        counts.forEach { (rule, n) -> if (n > 0) map.put(rule, n) }
        request.complete(Optional.of(map))
    }

    /** Collects what the desktop client would warn about before the purchase is final. */
    fun checkAndFinish() {
        val unitsBought = rules.sumOf { rule ->
            val construction = runCatching { unitTypeOf(rule)?.unitAttachment?.isConstruction }.getOrNull() ?: false
            if (construction) 0 else (counts[rule] ?: 0) * unitsIn(rule)
        }
        val problems = ArrayList<String>()
        if (counts.values.sum() == 0) problems += "Nothing bought."
        val remaining = resources.associateWith { (available[it] ?: 0) - (spent[it] ?: 0) }
        val couldBuyMore = rules.any { rule ->
            rule.costs.keySet().isNotEmpty() && resources.all { (remaining[it] ?: 0) >= rule.costs.getInt(it) }
        }
        if (couldBuyMore && remaining.values.any { it > 0 }) {
            problems += "Unspent: " + remaining.filterValues { it > 0 }.entries.joinToString(", ") { "${it.value} ${it.key.name}" }
        }
        if (!request.bid && capacity != null && unitsBought > capacity) {
            problems += "$unitsBought units bought, factories place $capacity."
        }
        if (problems.isEmpty()) finish() else warnings = problems
    }

    warnings?.let { problems ->
        AppDialog(
            title = "Finish purchase?",
            onDismiss = { warnings = null },
            buttons = { ConfirmButton { warnings = null; finish() } },
        ) {
            problems.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }

    val totalUnits = rules.sumOf { (counts[it] ?: 0) * unitsIn(it) }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // header: title and the budget
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onShowMap != null) {
                        IconButton(onClick = onShowMap, modifier = Modifier.padding(end = 4.dp)) {
                            Icon(Icons.Filled.Map, contentDescription = "show map")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (request.bid) "Bid: ${player.name}" else "Purchase: ${player.name}", style = MaterialTheme.typography.titleLarge)
                        if (capacity != null && !request.bid) {
                            Text("Factories can place $capacity unit(s) this turn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        resources.forEach { resource ->
                            val left = (available[resource] ?: 0) - (spent[resource] ?: 0)
                            Text(
                                "$left ${resource.name}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Text("of ${available[resource] ?: 0} left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()

                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    sections.forEach { (sectionName, sectionRules) ->
                        item(key = "section-$sectionName") {
                            Text(
                                sectionName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp),
                            )
                        }
                        items(sectionRules, key = { it.name }) { rule ->
                            val unitType = unitTypeOf(rule)
                            val current = counts[rule] ?: 0
                            val extra = resources.filter { rule.costs.getInt(it) > 0 }.minOfOrNull { resource ->
                                ((available[resource] ?: 0) - (spent[resource] ?: 0)) / rule.costs.getInt(resource)
                            } ?: 99
                            PurchaseRow(
                                rule = rule,
                                unitType = unitType,
                                player = player,
                                images = images,
                                count = current,
                                max = current + extra.coerceAtLeast(0),
                                expanded = expanded[rule] == true,
                                onToggle = { expanded[rule] = !(expanded[rule] ?: false) },
                                onCount = { counts[rule] = it.coerceAtLeast(0) },
                            )
                        }
                    }
                }

                // footer: summary and the two ways out
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("$totalUnits unit(s) selected", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (spent.values.all { it == 0 }) "Nothing chosen yet"
                            else "Spending " + resources.filter { (spent[it] ?: 0) > 0 }.joinToString(", ") { "${spent[it]} ${it.name}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (affordable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (onShowMap != null) {
                        TextButton(onClick = onShowMap) { Text("Map") }
                    }
                    TextButton(onClick = { counts.clear(); checkAndFinish() }) { Text("Buy nothing") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = affordable, onClick = { checkAndFinish() }) { Text("Buy") }
                }
            }
        }
    }
}

/** One purchasable unit: icon, name, a clear stat strip, the stepper, and expandable abilities. */
@Composable
private fun PurchaseRow(
    rule: ProductionRule,
    unitType: UnitType?,
    player: GamePlayer,
    images: ImageCache?,
    count: Int,
    max: Int,
    expanded: Boolean,
    onToggle: () -> kotlin.Unit,
    onCount: (Int) -> kotlin.Unit,
) {
    val name = rule.results.keySet().firstOrNull()?.name ?: rule.name
    val ua = unitType?.unitAttachment
    val produced = rule.results.keySet().filterIsInstance<UnitType>().sumOf { rule.results.getInt(it) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (count > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).clickable(onClick = onToggle),
                ) {
                    if (unitType != null) UnitIcon(images, unitType, player, size = 44) else Spacer(Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (produced > 1) "$name ×$produced" else name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (ua != null) {
                                val attackRolls = ua.getAttackRolls(player)
                                val defenseRolls = ua.getDefenseRolls(player)
                                StatCell("Att", (if (attackRolls > 1) "${attackRolls}×" else "") + ua.getAttack(player))
                                StatCell("Def", (if (defenseRolls > 1) "${defenseRolls}×" else "") + ua.getDefense(player))
                                StatCell("Move", ua.getMovement(player).toString())
                                if (ua.hitPoints > 1) StatCell("HP", ua.hitPoints.toString())
                            }
                            StatCell("Cost", rule.costs.keySet().joinToString(" ") { "${rule.costs.getInt(it)}" } + if (rule.costs.keySet().size == 1) "" else " (${rule.costs.keySet().joinToString("/") { it.name }})")
                        }
                    }
                }
                Stepper(count, max, onCount)
            }
            val info = remember(unitType, player) {
                if (ua == null) emptyList()
                else runCatching { parseUnitInfo(ua.toStringShortAndOnlyImportantDifferences(player)) }.getOrDefault(emptyList())
            }
            if (!expanded) {
                val flags = info.filter { it.value == null }.take(3).map { it.label }
                Text(
                    (if (flags.isEmpty()) "No special abilities" else flags.joinToString("  ·  ")) + (if (info.size > flags.size) "   ▸ more" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).clickable(onClick = onToggle),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onToggle),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text("Abilities", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                        UnitInfoView(info)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One property of a unit type: a flag ("Can Blitz") or a labeled value ("Transport capacity" = "2"). */
class UnitInfoEntry(val label: String, val value: String?)

/**
 * Turns the engine's unit tooltip (HTML, one "Label: <b>value</b>" per line) into entries and drops
 * what the purchase list already shows: the type line and the attack/defense/movement line.
 */
fun parseUnitInfo(html: String): List<UnitInfoEntry> {
    val entries = ArrayList<UnitInfoEntry>()
    // one tooltip line: "Label<br />" or "Label: <b>value</b><br />" (values may contain breaks)
    val line = Regex("""(?is)([^<]*?)(?::\s*<b>(.*?)</b>)?\s*<br\s*/?>""")
    for (match in line.findAll(html)) {
        val label = GameController.stripHtml(match.groupValues[1]).replace(Regex("""\s+"""), " ").trim().trimEnd(',').trim()
        val rawValue = match.groups[2]?.value
        if (label.isEmpty() || label.startsWith("Att | Def | Mov") || label == "Type") continue
        val value = rawValue?.let { GameController.stripHtml(it).replace(Regex("""\s+"""), " ").trim().trimEnd(',').trim() }
        entries += friendlyEntry(label, value?.takeIf { it.isNotEmpty() })
    }
    return entries
}

private val SUPPORT_VALUE = Regex("""^(-?\d+)\s+(?:(\S+)\s+)?(Roll & Power|Power & Roll|Targeted Roll|Targeted Power|Power|Roll)\s+to\s+(\d+)\s+(Allied & Enemy|Allied|Enemy)\s+(.+)$""")
private val AA_VALUE = Regex("""^(\d+)/(\d+)\s+(\S+)\s+with\s+(\S+)\s+Attacks\s+for\s+(\S+)\s+Rounds.*$""")

/**
 * Rewrites the engine's tooltip wording into plain language, e.g. "Support on Attack: 1 Power to 1
 * Allied infantry" becomes "Supports: +1 attack for 1 infantry".
 */
private fun friendlyEntry(label: String, value: String?): UnitInfoEntry {
    // artillery style support lines
    if (label.startsWith("Support on ")) {
        val match = value?.let { SUPPORT_VALUE.find(it) }
        if (match != null) {
            val bonus = match.groupValues[1].toInt()
            val kind = match.groupValues[3]
            val number = match.groupValues[4]
            val side = match.groupValues[5]
            val units = match.groupValues[6].trim().let { if (it.equals("Units", true)) "units" else it }
            val what = when {
                kind.contains("Targeted") -> "anti-aircraft " + if (kind.endsWith("Power")) "strength" else "shots"
                kind == "Power" -> label.removePrefix("Support on ").lowercase().replace("&", "and")
                kind == "Roll" -> "extra die on " + label.removePrefix("Support on ").lowercase().replace("&", "and")
                else -> "attack and extra die"
            }
            val sign = if (bonus >= 0) "+" else "−"
            val whom = when (side) {
                "Enemy" -> "$number enemy $units"
                "Allied & Enemy" -> "$number $units of either side"
                else -> "$number $units"
            }
            val verb = if (side == "Enemy" || bonus < 0) "Weakens" else "Supports"
            return UnitInfoEntry(verb, "$sign${kotlin.math.abs(bonus)} $what for $whom")
        }
        return UnitInfoEntry("Supports", value)
    }
    // anti-aircraft fire
    if (label == "Targeted Defense" || label == "Targeted Attack") {
        val match = value?.let { AA_VALUE.find(it) }
        val name = if (label == "Targeted Defense") "Anti-aircraft fire" else "Targeted fire on attack"
        if (match != null) {
            val hit = match.groupValues[1]
            val sides = match.groupValues[2]
            val shots = match.groupValues[4].let { if (it.equals("Unlimited", true)) "unlimited shots" else "$it shot(s)" }
            val rounds = match.groupValues[5].let { if (it.equals("Unlimited", true)) "" else ", $it round(s)" }
            return UnitInfoEntry(name, "hits on $hit of $sides, $shots$rounds")
        }
        return UnitInfoEntry(name, value)
    }
    val renamed: Pair<String, String?> = when (label) {
        "HP" -> "Hit points" to value
        "Transporting Capacity" -> "Transport capacity" to value
        "Transporting Cost" -> "Space needed on a transport" to value
        "Carrier Capacity" -> "Carrier capacity (aircraft)" to value
        "Carrier Cost" -> "Space needed on a carrier" to value
        "Can Blitz" -> "Blitz: rolls through empty enemy territory" to null
        "Can Evade" -> "Can submerge and evade" to null
        "Is First Strike" -> "First strike: fires before the enemy" to null
        "Is Anti-Stealth" -> "Destroyer: cancels submarine abilities" to null
        "Can Perform Raids" -> "Strategic bombing of factories" to null
        "Bombard" -> "Shore bombardment strength" to value
        "Is a Sea Transport" -> "Sea transport" to null
        "Is an Air Transport" -> "Air transport" to null
        "Is a Land Transport" -> "Land transport" to null
        "Is a Combat Transport" -> "Combat transport" to null
        "Can be Air Transported" -> "Can be airlifted (paratrooper)" to null
        "Can be Land Transported" -> "Can ride on land transports" to null
        "Can Produce Units" -> "Factory: produces up to" to (value?.let { "$it units" })
        "Can Produce Units up to Territory Value" -> "Factory: produces up to the territory value" to null
        "Can be Captured" -> "Infrastructure: captured, not destroyed" to null
        "Can be Damaged by Raids" -> "Can be damaged by bombing" to null
        "Can be Placed Without Factory" -> "Placed without a factory" to null
        "Cannot Combat Move" -> "No combat move" to null
        "Amphibious Attack Modifier" -> "Amphibious assault bonus" to value
        "Max Built Allowed" -> "Maximum that can be built" to value
        "Allows Scrambling" -> "Airbase: allows scrambling" to null
        "Scramble Range" -> "Scramble range" to value
        "Suicide on Attack Unit" -> "Dies after attacking" to null
        "Suicide on Defense Unit" -> "Dies after defending" to null
        "Suicide on Hit Unit" -> "Dies when it hits" to null
        "Is Kamikaze" -> "Kamikaze" to null
        "Can't Target" -> "Cannot hit" to value
        "Can't Be Targeted By" -> "Cannot be hit by" to value
        "Fuel Cost per Movement" -> "Fuel per move" to value
        "Fuel Cost each Turn if Moved" -> "Fuel per turn when moved" to value
        "Creates Units each Turn" -> "Creates each turn" to value
        "Produces Resources each Turn" -> "Produces each turn" to value
        "Blockade Loss" -> "Blockade: enemy loses" to value
        "Can Move Through Enemies" -> "Can move through enemy units" to null
        "Can Be Moved Through By Enemies" -> "Enemies can move through it" to null
        "Receives Ability", "Receives Abilities when Paired with Other Units" -> "Gains abilities when paired with" to value
        "Unit Consumes Other Units on Placement" -> "Built from (consumes)" to value
        "When Hit Loses Certain Abilities" -> "Loses abilities when hit" to null
        "Has Placement Requirements", "Placement Requirements" -> "Placement requires" to value
        "Has Placement Restrictions", "Placement Restrictions" -> "Cannot be placed in" to value
        "Has Movement Requirements", "Movement Requirements" -> "Movement requires" to value
        "Can Modify Unit Movement" -> "Changes the movement of other units" to value
        "Can Repair some Units" -> "Repairs" to value
        "Can Provide Support to Units" -> "Supports other units" to null
        "Can Rocket Attack" -> "Rocket attack on factories" to null
        else -> label.trimEnd(',').trim() to value
    }
    return UnitInfoEntry(renamed.first, renamed.second)
}

/** Abilities: plain ones as chips, everything with a number as a two column list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitInfoView(entries: List<UnitInfoEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text("No special abilities.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
        return
    }
    Column(modifier) {
        val flags = entries.filter { it.value == null }
        val valued = entries.filter { it.value != null }
        if (flags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                flags.forEach { entry ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        if (valued.isNotEmpty()) {
            Column(Modifier.padding(top = if (flags.isEmpty()) 0.dp else 8.dp)) {
                valued.forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.value.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The moves (or placements) of this phase like the desktop undo panel: one row per move with the
 * route and the units; tapping a row shows the route on the map, Undo takes that move back.
 */
@Composable
fun MovesList(
    moves: List<MadeMove>,
    images: ImageCache?,
    onShowRoute: (MadeMove) -> kotlin.Unit,
    onUndo: (MadeMove) -> kotlin.Unit,
    compact: Boolean = false,
) {
    if (moves.isEmpty()) {
        Text("No moves yet in this phase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    moves.forEach { move ->
        val groups = remember(move) { move.units.groupBy { it.type to it.owner } }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), onClick = { onShowRoute(move) }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val route = move.routeTerritories
                    Text(
                        if (route.size >= 2) "${route.first()} → ${route.last()}" + (if (route.size > 2) "  (${route.size - 1})" else "") else route.joinToString(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        groups.entries.take(if (compact) 4 else 6).forEach { (key, units) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UnitIcon(images, key.first, key.second, size = if (compact) 20 else 24)
                                Text("×${units.size}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (groups.size > (if (compact) 4 else 6)) Text("…", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!move.canUndo && move.reasonCantUndo != null) {
                        Text(move.reasonCantUndo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(
                    onClick = { onUndo(move) },
                    enabled = move.canUndo,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("Undo") }
            }
        }
    }
}

@Composable
fun MovesDialog(
    title: String,
    moves: List<MadeMove>,
    images: ImageCache?,
    onShowRoute: (MadeMove) -> kotlin.Unit,
    onUndo: (MadeMove) -> kotlin.Unit,
    onUndoAll: () -> kotlin.Unit,
    onClose: () -> kotlin.Unit,
) {
    AppDialog(
        title = title,
        onDismiss = onClose,
        buttons = {
            if (moves.any { it.canUndo }) TextButton(onClick = onUndoAll) { Text("Undo all") }
        },
    ) {
        LazyColumn { item { MovesList(moves, images, onShowRoute, onUndo) } }
    }
}

/** Facts about a political or user action: cost, chance, relationship changes, who must accept. */
private fun actionFacts(action: AbstractUserActionAttachment): String {
    val facts = ArrayList<String>()
    val cost = action.costResources
    if (!cost.isEmpty) facts += "Cost: " + cost.keySet().joinToString(", ") { "${cost.getInt(it)} ${it.name}" }
    val sides = action.chanceDiceSides
    val hit = action.chanceToHit
    if (sides > 0 && hit < sides) facts += "Success chance: $hit in $sides"
    if (action is PoliticalActionAttachment) {
        action.relationshipChanges.forEach { facts += "${it.player1.name} ↔ ${it.player2.name}: ${it.relationshipType.name}" }
    }
    val accept = action.actionAccept
    if (accept.isNotEmpty()) facts += "Must be accepted by " + accept.joinToString(", ") { it.name }
    return facts.joinToString("\n")
}

@Composable
private fun <T : AbstractUserActionAttachment> ActionChoiceDialog(
    title: String,
    actions: List<T>,
    buttonText: (T) -> String?,
    description: (T) -> String?,
    onChoose: (T) -> kotlin.Unit,
    onDone: () -> kotlin.Unit,
) {
    AppDialog(
        title = title,
        onDismiss = null,
        buttons = { Button(onClick = onDone) { Text("Done") } },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(actions) { action ->
                val label = runCatching { buttonText(action) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: (action.name ?: "").removePrefix("politicalActionAttachment_").removePrefix("userActionAttachment_").replace('_', ' ')
                val text = runCatching { description(action) }.getOrNull()?.let { GameController.stripHtml(it) } ?: ""
                val facts = actionFacts(action)
                OptionRow(
                    title = label,
                    subtitle = listOf(text, facts).filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() },
                    onClick = { onChoose(action) },
                )
            }
        }
    }
}

/** The politics phase: the desktop client's politics panel with the map's own texts. */
@Composable
fun PoliticsDialog(request: PoliticsRequest, session: LocalGameSession) {
    val texts = remember(session) { runCatching { PoliticsText(session.resourceLoader) }.getOrNull() }
    ActionChoiceDialog(
        title = "Politics: ${request.player.name}",
        actions = request.actions,
        buttonText = { texts?.getButtonText(it.text) },
        description = { texts?.getDescription(it.text) },
        onChoose = { request.complete(Optional.of(it)) },
        onDone = { request.complete(Optional.empty()) },
    )
}

/** The user actions phase (map specific actions such as lend-lease or mobilisation). */
@Composable
fun UserActionDialog(request: UserActionRequest, session: LocalGameSession) {
    val texts = remember(session) { runCatching { UserActionText(session.resourceLoader) }.getOrNull() }
    ActionChoiceDialog(
        title = "Actions: ${request.player.name}",
        actions = request.actions,
        buttonText = { texts?.getButtonText(it.text) },
        description = { texts?.getDescription(it.text) },
        onChoose = { request.complete(Optional.of(it)) },
        onDone = { request.complete(Optional.empty()) },
    )
}

@Composable
fun BattleListDialog(request: BattleRequest) {
    val entries = remember(request) {
        request.battles.battlesMap.entries.flatMap { (type, territories) ->
            territories.sortedBy { it.name }.map { type to it }
        }
    }
    AppDialog(
        title = "Choose the next battle",
        onDismiss = null,
        buttons = {},
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries) { (type, territory) ->
                OptionRow(
                    title = territory.name,
                    subtitle = type.toDisplayText(),
                    onClick = {
                        request.complete(
                            Optional.of(
                                FightBattleDetails.builder()
                                    .where(territory)
                                    .bombingRaid(type.isBombingRun)
                                    .battleType(type)
                                    .build()
                            )
                        )
                    },
                )
            }
        }
    }
}

/** The player's running choice of losses for a [CasualtyRequest]: counts per unit group. */
class CasualtyChoice(val request: CasualtyRequest) {
    val groups: Map<UnitGroupKey, List<Unit>> = request.selectFrom.groupBy { groupKey(it) }
    val damagedDefaults: List<Unit> = request.defaults.damaged.toList()
    /** Units to remove: the hits minus those that only damage a unit (e.g. a battleship's first hit). */
    val killsNeeded: Int = (request.count - damagedDefaults.size).coerceAtLeast(0)
    val counts: SnapshotStateMap<UnitGroupKey, Int> = mutableStateMapOf<UnitGroupKey, Int>().also { map ->
        groups.keys.forEach { map[it] = 0 }
        request.defaults.killed.forEach { unit ->
            val key = groupKey(unit)
            map[key] = (map[key] ?: 0) + 1
        }
    }
    val total: Int get() = counts.values.sum()
    val complete: Boolean get() = total == killsNeeded

    fun confirm() {
        val killed = ArrayList<Unit>()
        groups.forEach { (key, units) -> killed += units.take(counts[key] ?: 0) }
        request.complete(CasualtyDetails(killed, damagedDefaults, false))
    }

    fun useSuggested() = request.complete(CasualtyDetails(request.defaults, true))

    private fun keysOf(type: String, owner: String) = groups.keys.filter { it.type == type && it.owner == owner }

    /** How many units of this type and owner are marked as lost. */
    fun chosen(type: String, owner: String): Int = keysOf(type, owner).sumOf { counts[it] ?: 0 }

    /**
     * A tap on a unit tile of the front line: marks one more unit of that type. When every hit is
     * already assigned, one is taken away from another type instead; when this type cannot take
     * more, its marks are cleared.
     */
    fun tap(type: String, owner: String) {
        val keys = keysOf(type, owner)
        val key = keys.firstOrNull { (counts[it] ?: 0) < groups.getValue(it).size }
        if (key == null) {
            keys.forEach { counts[it] = 0 }
            return
        }
        if (total >= killsNeeded) {
            val other = groups.keys.firstOrNull { it !in keys && (counts[it] ?: 0) > 0 }
            if (other == null) {
                keys.forEach { counts[it] = 0 }
                return
            }
            counts[other] = (counts[other] ?: 0) - 1
        }
        counts[key] = (counts[key] ?: 0) + 1
    }
}

/** Fallback when the battle window is not on screen: the same choice as a dialog. */
@Composable
fun CasualtyDialog(request: CasualtyRequest, images: ImageCache?) {
    val choice = remember(request) { CasualtyChoice(request) }
    AppDialog(
        title = "Choose your losses",
        onDismiss = null,
        status = "${choice.total} of ${choice.killsNeeded} chosen",
        statusColor = if (choice.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        buttons = {
            TextButton(onClick = { choice.useSuggested() }) { Text("Suggested") }
            Button(enabled = choice.complete, onClick = { choice.confirm() }) { Text("Confirm") }
        },
    ) {
        request.dice?.let { dice ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                (0 until dice.size()).take(14).forEach { i ->
                    val die = dice.getDie(i)
                    Die(die.value + 1, die.type == games.strategy.triplea.delegate.Die.DieType.HIT, size = 18)
                }
            }
        }
        if (choice.damagedDefaults.isNotEmpty()) {
            Text("${choice.damagedDefaults.size} hit(s) only damage a unit and are taken automatically.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(choice.groups.entries.toList()) { (key, units) ->
                val sample = units.first()
                val current = choice.counts[key] ?: 0
                val allowed = minOf(units.size, current + (choice.killsNeeded - choice.total))
                CountRow(
                    title = key.type + if (key.damaged) " (damaged)" else "",
                    subtitle = "${units.size} in battle",
                    value = current,
                    max = allowed,
                    onChange = { choice.counts[key] = it.coerceIn(0, units.size) },
                    leading = { UnitIcon(images, sample.type, sample.owner, size = 30) },
                )
            }
        }
    }
}

@Composable
fun TerritoryPickerDialog(request: SelectTerritoryRequest) {
    AppDialog(
        title = request.title,
        subtitle = request.message.takeIf { it.isNotBlank() },
        onDismiss = null,
        buttons = {
            if (request.noneAllowed) TextButton(onClick = { request.complete(Optional.empty()) }) { Text("None") }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(request.candidates) { territory: Territory ->
                OptionRow(title = territory.name, onClick = { request.complete(Optional.of(territory)) })
            }
        }
    }
}

@Composable
fun RetreatDialog(request: RetreatRequest) {
    AppDialog(
        title = if (request.submerge) "Submerge or retreat?" else "Retreat?",
        subtitle = request.message.takeIf { it.isNotBlank() },
        onDismiss = null,
        buttons = { Button(onClick = { request.complete(Optional.empty()) }) { Text("Keep fighting") } },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(request.possibleTerritories) { territory ->
                val submerge = territory == request.battleTerritory && request.submerge
                OptionRow(
                    title = if (submerge) "Submerge" else "Retreat to ${territory.name}",
                    onClick = { request.complete(Optional.of(territory)) },
                )
            }
        }
    }
}

@Composable
fun SaveGameDialog(defaultName: String, onSave: (String) -> kotlin.Unit, onCancel: () -> kotlin.Unit) {
    var name by remember { mutableStateOf(defaultName) }
    AppDialog(
        title = "Save game",
        onDismiss = onCancel,
        buttons = { ConfirmButton(enabled = name.isNotBlank()) { onSave(name.trim()) } },
    ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun MessageDialog(title: String, text: String, onDismiss: () -> kotlin.Unit) {
    AppDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {},
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

fun IBattle.BattleType.label(): String = toDisplayText()
