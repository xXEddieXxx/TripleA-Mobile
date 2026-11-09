package org.triplea.mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import games.strategy.engine.data.UnitType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.triplea.mobile.app.game.EditHelper
import games.strategy.engine.data.Unit as GameUnit
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.KeyboardType
import games.strategy.engine.data.RelationshipType
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Tab
import org.triplea.mobile.app.game.RelationshipLine
import games.strategy.triplea.delegate.TechAdvance
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Territory
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.app.render.ImageCache

/** The edit mode tools; the territory ones wait for a tap on the map, the others open their dialog at once. */
enum class EditTool(val label: String, val needsTerritory: Boolean) {
    ADD_UNITS("Add units", true),
    REMOVE_UNITS("Remove units", true),
    CHANGE_OWNER("Change owner", true),
    UNIT_DAMAGE("Unit damage", true),
    RESOURCES("PUs", false),
    TECHS("Techs", false),
    POLITICS("Politics", false),
}

private val EditTool.icon: ImageVector
    get() = when (this) {
        EditTool.ADD_UNITS -> Icons.Filled.AddBox
        EditTool.REMOVE_UNITS -> Icons.Filled.IndeterminateCheckBox
        EditTool.CHANGE_OWNER -> Icons.Filled.Flag
        EditTool.UNIT_DAMAGE -> Icons.Filled.HeartBroken
        EditTool.RESOURCES -> Icons.Filled.Paid
        EditTool.TECHS -> Icons.Filled.Science
        EditTool.POLITICS -> Icons.Filled.Handshake
    }

/** The column of edit tools floating on the map; the active one is filled red. */
@Composable
fun EditToolStrip(active: EditTool?, showTechs: Boolean, onSelect: (EditTool) -> Unit, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        // wraps into a second column where the height runs out (landscape phones)
        FlowColumn(Modifier.padding(2.dp)) {
            EditTool.entries.filter { showTechs || it != EditTool.TECHS }.forEach { tool ->
                val colors = if (tool == active) {
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else IconButtonDefaults.iconButtonColors()
                IconButton(onClick = { onSelect(tool) }, colors = colors) {
                    Icon(tool.icon, contentDescription = tool.label)
                }
            }
        }
    }
}

/** The red "EDIT MODE" chip with a ✕ that switches edit mode off; landscape has no top bar for it. */
@Composable
fun EditModeChip(onClose: () -> Unit, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EDIT MODE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Turn off edit mode", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The dialog of a territory tool for one territory; [onDone] gets the engine's error, null on success or cancel. */
@Composable
fun EditTerritoryDialog(tool: EditTool, territory: Territory, session: LocalGameSession, images: ImageCache?, onDone: (String?) -> Unit) {
    when (tool) {
        EditTool.ADD_UNITS -> AddUnitsDialog(territory, session, images, onDone)
        EditTool.REMOVE_UNITS -> RemoveUnitsDialog(territory, session, images, onDone)
        EditTool.CHANGE_OWNER -> ChangeOwnerDialog(territory, session, onDone)
        EditTool.UNIT_DAMAGE -> UnitDamageDialog(territory, session, images, onDone)
        else -> onDone(null)
    }
}

/** Runs an engine edit off the main thread and hands its error (null on success) to [onDone]. */
private fun CoroutineScope.edit(onDone: (String?) -> kotlin.Unit, call: () -> String?) {
    launch { onDone(withContext(Dispatchers.Default) { call() }) }
}

/** [sections]: the unit types by kind (land, air, sea, other), the tabs of the shop. */
private class AddData(val players: List<GamePlayer>, val sections: List<Pair<String, List<UnitType>>>, val current: GamePlayer?)

@Composable
private fun AddUnitsDialog(territory: Territory, session: LocalGameSession, images: ImageCache?, onDone: (String?) -> kotlin.Unit) {
    val data by produceState<AddData?>(null, session) {
        value = withContext(Dispatchers.Default) {
            val types = EditHelper.unitTypes(session)
            val (current, grouped) = session.gameData.acquireReadLock().use {
                session.gameData.sequence.step?.playerId to types.groupBy { unitKind(it) }
            }
            AddData(
                EditHelper.playersWithNeutral(session, onlyIfNeutralUnits = true),
                UNIT_KINDS.mapNotNull { kind -> grouped[kind]?.let { kind to it } },
                current,
            )
        }
    }
    val loaded = data ?: return
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf(loaded.current ?: loaded.players.first()) }
    val counts = remember { mutableStateMapOf<UnitType, Int>() }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val shown = loaded.sections.getOrNull(tab)?.second.orEmpty()
    AppDialog(
        title = "Add units to ${territory.name}",
        onDismiss = { if (!busy) onDone(null) },
        status = "${counts.values.sum()} to add",
        buttons = {
            ConfirmButton(enabled = !busy && counts.values.sum() > 0) {
                busy = true
                scope.edit(onDone) { EditHelper.addUnits(session, territory, owner, counts.filterValues { it > 0 }) }
            }
        },
    ) {
        // one tab per kind like the shop, with the count chosen in each
        if (loaded.sections.size > 1) {
            PrimaryTabRow(selectedTabIndex = tab) {
                loaded.sections.forEachIndexed { index, (kind, types) ->
                    val chosen = types.sumOf { counts[it] ?: 0 }
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(if (chosen > 0) "$kind ($chosen)" else kind, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                    )
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = 8.dp)) {
            // the owner row opens the players list; picking one closes it
            item {
                OptionRow(title = "Owner: ${owner.name}", subtitle = "tap to choose", onClick = { choosing = !choosing })
            }
            if (choosing) {
                items(loaded.players) { player ->
                    OptionRow(
                        title = player.name,
                        emphasized = player == owner,
                        onClick = { owner = player; choosing = false },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            items(shown) { type ->
                CountRow(
                    title = type.name,
                    subtitle = null,
                    value = counts[type] ?: 0,
                    max = 99,
                    onChange = { counts[type] = it.coerceIn(0, 99) },
                    leading = { UnitIcon(images, type, owner, size = 30) },
                )
            }
        }
    }
}

@Composable
private fun RemoveUnitsDialog(territory: Territory, session: LocalGameSession, images: ImageCache?, onDone: (String?) -> kotlin.Unit) {
    val units by produceState<List<GameUnit>?>(null, session, territory) {
        value = withContext(Dispatchers.Default) { session.gameData.acquireReadLock().use { territory.unitCollection.toList() } }
    }
    val loaded = units ?: return
    if (loaded.isEmpty()) {
        LaunchedEffect(Unit) { onDone("No units to remove in ${territory.name}") }
        return
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    UnitPickerDialog(
        UnitPickerSpec(
            title = "Remove units from ${territory.name}",
            message = "",
            units = loaded,
            max = loaded.size,
            onConfirm = { chosen ->
                if (!busy) {
                    busy = true
                    scope.edit(onDone) { EditHelper.removeUnits(session, territory, chosen) }
                }
            },
            onCancel = { if (!busy) onDone(null) },
        ),
        images,
    )
}

@Composable
private fun ChangeOwnerDialog(territory: Territory, session: LocalGameSession, onDone: (String?) -> kotlin.Unit) {
    val data by produceState<Pair<List<GamePlayer>, GamePlayer>?>(null, session, territory) {
        value = withContext(Dispatchers.Default) {
            EditHelper.playersWithNeutral(session, onlyIfNeutralUnits = false) to session.gameData.acquireReadLock().use { territory.owner }
        }
    }
    val (loaded, owner) = data ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AppDialog(title = "Change owner of ${territory.name}", onDismiss = { if (!busy) onDone(null) }, buttons = {}) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(loaded) { player ->
                val current = player == owner
                OptionRow(
                    title = player.name,
                    subtitle = if (current) "current owner" else null,
                    emphasized = current,
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.edit(onDone) { EditHelper.changeOwner(session, territory, player) }
                        }
                    },
                )
            }
        }
    }
}

private class DamageRow(val unit: GameUnit, val ownerName: String, val hitMax: Int, val hits: Int, val bombingMax: Int, val bombing: Int)

private class DamageData(val rows: List<DamageRow>, val bombingEnabled: Boolean)

@Composable
private fun UnitDamageDialog(territory: Territory, session: LocalGameSession, images: ImageCache?, onDone: (String?) -> kotlin.Unit) {
    val data by produceState<DamageData?>(null, session, territory) {
        value = withContext(Dispatchers.Default) {
            val bombingEnabled = EditHelper.bombingDamageEnabled(session)
            val rows = EditHelper.damageableUnits(session, territory)
            session.gameData.acquireReadLock().use {
                DamageData(
                    rows.map {
                        DamageRow(it, it.owner.name, it.unitAttachment.hitPoints - 1, it.hits, it.getHowMuchDamageCanThisUnitTakeTotal(territory), it.unitDamage)
                    },
                    bombingEnabled,
                )
            }
        }
    }
    val loaded = data ?: return
    if (loaded.rows.isEmpty()) {
        LaunchedEffect(Unit) { onDone("No unit here can take damage") }
        return
    }
    val scope = rememberCoroutineScope()
    val hits = remember { mutableStateMapOf<GameUnit, Int>().also { m -> loaded.rows.forEach { m[it.unit] = it.hits } } }
    val bombing = remember { mutableStateMapOf<GameUnit, Int>().also { m -> loaded.rows.forEach { m[it.unit] = it.bombing } } }
    var busy by remember { mutableStateOf(false) }
    AppDialog(
        title = "Unit damage in ${territory.name}",
        onDismiss = { if (!busy) onDone(null) },
        buttons = {
            ConfirmButton(enabled = !busy) {
                busy = true
                // only the units whose value changed go to the engine
                val hit = loaded.rows.filter { hits[it.unit] != it.hits }.associate { it.unit to hits.getValue(it.unit) }
                val bomb = loaded.rows.filter { bombing[it.unit] != it.bombing }.associate { it.unit to bombing.getValue(it.unit) }
                scope.edit(onDone) { EditHelper.changeDamage(session, territory, hit, bomb) }
            }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(loaded.rows) { row ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UnitIcon(images, row.unit.type, row.unit.owner, size = 30)
                            Spacer(Modifier.width(10.dp))
                            Text("${row.unit.type.name} (${row.ownerName})", style = MaterialTheme.typography.bodyLarge)
                        }
                        if (row.hitMax > 0) DamageStepper("Hits taken", hits.getValue(row.unit), row.hitMax) { hits[row.unit] = it }
                        if (loaded.bombingEnabled && row.bombingMax > 0) DamageStepper("Bombing damage", bombing.getValue(row.unit), row.bombingMax) { bombing[row.unit] = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun DamageStepper(label: String, value: Int, max: Int, onChange: (Int) -> kotlin.Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Stepper(value, max, onChange)
    }
}

/** The dialog of a player tool (PUs, techs, politics); [onDone] gets the engine's error, null on success or cancel. */
@Composable
fun EditPlayerDialog(tool: EditTool, session: LocalGameSession, initialPlayer: GamePlayer, images: ImageCache?, onDone: (String?) -> kotlin.Unit) {
    val players by produceState<List<GamePlayer>?>(null, session) {
        value = withContext(Dispatchers.Default) { EditHelper.players(session) }
    }
    val all = players ?: return
    when (tool) {
        EditTool.RESOURCES -> ResourcesDialog(all, initialPlayer, session, onDone)
        EditTool.TECHS -> TechsDialog(all, initialPlayer, session, onDone)
        EditTool.POLITICS -> PoliticsDialog(all, session, images, onDone)
        else -> onDone(null)
    }
}

@Composable
private fun ResourcesDialog(players: List<GamePlayer>, initial: GamePlayer, session: LocalGameSession, onDone: (String?) -> kotlin.Unit) {
    var selected by remember { mutableIntStateOf(players.indexOf(initial).coerceAtLeast(0)) }
    val player = players[selected]
    // the amounts of every nation at once: a tab switch then shows its rows at once, without the
    // dialog shrinking to an empty list and growing back while they load
    val amounts by produceState<Map<GamePlayer, List<Pair<String, Int>>>?>(null, session) {
        value = withContext(Dispatchers.Default) {
            val resources = EditHelper.resources(session)
            session.gameData.acquireReadLock().use {
                players.associateWith { p -> resources.map { it.name to p.resources.getQuantity(it) } }
            }
        }
    }
    val loaded = amounts ?: return
    val rows = loaded[player]
    val scope = rememberCoroutineScope()
    // typed amounts per nation and resource; one without an entry is unchanged
    val texts = remember { mutableStateMapOf<Pair<GamePlayer, String>, String>() }
    val changed = loaded.flatMap { (p, list) ->
        list.mapNotNull { (name, old) -> texts[p to name]?.toIntOrNull()?.takeIf { it != old }?.let { Triple(p, name, it) } }
    }
    var busy by remember { mutableStateOf(false) }
    AppDialog(
        title = "Change resources",
        onDismiss = { if (!busy) onDone(null) },
        buttons = {
            ConfirmButton(enabled = !busy && changed.isNotEmpty()) {
                busy = true
                scope.edit(onDone) { EditHelper.inOrder(changed.map { (p, name, total) -> { EditHelper.changeResource(session, p, name, total) } }) }
            }
        },
    ) {
        NationTabs(players.map { it.name }, selected) { selected = it }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows.orEmpty()) { (name, old) ->
                val text = texts[player to name] ?: old.toString()
                val value = text.toIntOrNull() ?: old
                // the amount once, in the field between − and +; the field takes large changes at once
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { texts[player to name] = (value - 1).toString() }, enabled = value > 0) {
                        Icon(Icons.Filled.Remove, contentDescription = "less")
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { texts[player to name] = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    IconButton(onClick = { texts[player to name] = (value + 1).toString() }, enabled = value < 999_999) {
                        Icon(Icons.Filled.Add, contentDescription = "more")
                    }
                }
            }
        }
    }
}

private class TechData(val owned: List<TechAdvance>, val available: List<TechAdvance>)

@Composable
private fun TechsDialog(players: List<GamePlayer>, initial: GamePlayer, session: LocalGameSession, onDone: (String?) -> kotlin.Unit) {
    var selected by remember { mutableIntStateOf(players.indexOf(initial).coerceAtLeast(0)) }
    val player = players[selected]
    // the techs of every nation at once (no empty list on a tab switch) and the ticks on them,
    // so a tab switch keeps the edits
    val techs by produceState<Map<GamePlayer, TechData>?>(null, session) {
        value = withContext(Dispatchers.Default) {
            players.associateWith { TechData(EditHelper.ownedTechs(session, it), EditHelper.availableTechs(session, it)) }
        }
    }
    val loaded = techs ?: return
    val checked = remember(loaded) {
        mutableStateMapOf<Pair<GamePlayer, TechAdvance>, Boolean>().apply {
            loaded.forEach { (p, d) -> d.owned.forEach { put(p to it, true) }; d.available.forEach { put(p to it, false) } }
        }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val changes = loaded.mapNotNull { (p, d) ->
        val add = d.available.filter { checked[p to it] == true }
        val remove = d.owned.filter { checked[p to it] == false }
        if (add.isEmpty() && remove.isEmpty()) null else Triple(p, add, remove)
    }
    AppDialog(
        title = "Change technologies",
        onDismiss = { if (!busy) onDone(null) },
        buttons = {
            ConfirmButton(enabled = !busy && changes.isNotEmpty()) {
                busy = true
                scope.edit(onDone) { EditHelper.inOrder(changes.map { (p, add, remove) -> { EditHelper.changeTechs(session, p, add, remove) } }) }
            }
        },
    ) {
        NationTabs(players.map { it.name }, selected) { selected = it }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(loaded[player]?.let { it.owned + it.available }.orEmpty().sortedBy { it.name }) { tech ->
                val on = checked[player to tech] == true
                // owned and not removable (industrial technology, improved shipyards): shown ticked and locked
                val locked = on && tech in loaded[player]?.owned.orEmpty() && !EditHelper.canRemoveTech(tech)
                OptionRow(
                    title = tech.name,
                    subtitle = if (locked) "cannot be removed" else null,
                    emphasized = on,
                    onClick = { if (!locked) checked[player to tech] = !on },
                    leading = { Checkbox(checked = on, onCheckedChange = null, enabled = !locked) },
                )
            }
        }
    }
}

private class PoliticsData(
    val types: List<RelationshipType>,
    val pairs: List<Pair<GamePlayer, GamePlayer>>,
    val current: Map<Pair<GamePlayer, GamePlayer>, RelationshipType>,
    /** War and allied per relationship type, for the matrix colours; read under the lock. */
    val kinds: Map<RelationshipType, Pair<Boolean, Boolean>>,
)

/** The Diplomacy matrix of the game info, with tappable cells: a tap opens the choice for that pair. */
@Composable
private fun PoliticsDialog(players: List<GamePlayer>, session: LocalGameSession, images: ImageCache?, onDone: (String?) -> kotlin.Unit) {
    val data by produceState<PoliticsData?>(null, session) {
        value = withContext(Dispatchers.Default) {
            val pairs = players.flatMapIndexed { i, a -> players.drop(i + 1).map { b -> a to b } }
            val types = EditHelper.relationshipTypes(session)
            val current = pairs.associateWith { (a, b) -> EditHelper.relationship(session, a, b) }
            val kinds = session.gameData.acquireReadLock().use {
                (types + current.values).associateWith { it.relationshipTypeAttachment.let { a -> a.isWar to a.isAllied } }
            }
            PoliticsData(types, pairs, current, kinds)
        }
    }
    val loaded = data ?: return
    val scope = rememberCoroutineScope()
    val chosen = remember { mutableStateMapOf<Pair<GamePlayer, GamePlayer>, RelationshipType>() }
    var open by remember { mutableStateOf<Pair<GamePlayer, GamePlayer>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val changes = loaded.pairs.filter { chosen[it] != null && chosen[it] != loaded.current[it] }
    val lines = loaded.pairs.map { pair ->
        val type = chosen[pair] ?: loaded.current.getValue(pair)
        val (war, allied) = loaded.kinds[type] ?: (false to false)
        RelationshipLine(pair.first.name, pair.second.name, type.name, war, allied)
    }
    AppDialog(
        title = "Change politics",
        onDismiss = { if (!busy) onDone(null) },
        status = if (changes.isEmpty()) "Tap a cell" else "${changes.size} changed",
        buttons = {
            ConfirmButton(enabled = !busy && changes.isNotEmpty()) {
                busy = true
                scope.edit(onDone) { EditHelper.changeRelationships(session, changes.map { org.triplea.util.Triple.of(it.first, it.second, chosen.getValue(it)) }) }
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            InfoNote("Relationship changes are not checked against the map's rules.")
            Spacer(Modifier.height(8.dp))
            DiplomacyView(
                relationships = lines,
                images = images,
                onCellTap = { a, b -> open = loaded.pairs.firstOrNull { it.first.name == a && it.second.name == b || it.first.name == b && it.second.name == a } },
                marked = changes.mapTo(HashSet()) { it.first.name to it.second.name },
            )
        }
    }
    open?.let { pair ->
        val type = chosen[pair] ?: loaded.current.getValue(pair)
        AppDialog(
            title = "${displayName(pair.first.name)} – ${displayName(pair.second.name)}",
            onDismiss = { open = null },
            buttons = {},
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(loaded.types) { option ->
                    OptionRow(
                        title = displayName(option.name),
                        emphasized = option == type,
                        onClick = { chosen[pair] = option; open = null },
                    )
                }
            }
        }
    }
}
