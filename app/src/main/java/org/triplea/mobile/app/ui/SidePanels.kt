package org.triplea.mobile.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import org.triplea.mobile.app.game.BattleState
import org.triplea.mobile.app.game.GameStatus
import org.triplea.mobile.app.game.HistoryBlock
import org.triplea.mobile.app.game.ObjectiveLine
import org.triplea.mobile.app.game.HistoryEvent
import org.triplea.mobile.app.game.HistoryKind
import org.triplea.mobile.app.game.UnitRef
import org.triplea.mobile.app.game.PlayerStats
import org.triplea.mobile.app.game.RelationshipLine
import org.triplea.mobile.app.game.TerritorySnapshot
import org.triplea.mobile.app.render.ImageCache

/*
 * The side panel content of the game screen: the collapsible details panel and the pieces it is
 * built from.
 */

/** The units of the selected territory with icons, or a hint when nothing is selected. */
@Composable
internal fun TerritoryDetails(territory: TerritorySnapshot?, images: ImageCache) {
    Column(Modifier.fillMaxWidth()) {
        if (territory != null) {
            Text(
                if (territory.isWater) territory.name else "${territory.name} (${territory.ownerName})",
                style = MaterialTheme.typography.labelLarge,
            )
            // terrain and weather effects of the territory, as the desktop's territory panel shows them
            territory.effects.forEach { effect ->
                val icon = remember(effect.name, images) { images.getNow(effect.imagePaths.last(), effect.imagePaths) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (icon != null) {
                        Image(icon.asImageBitmap(), contentDescription = effect.name, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(effect.name, style = MaterialTheme.typography.labelLarge)
                        if (effect.summary.isNotBlank()) {
                            Text(effect.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (territory.stacks.isEmpty()) {
                Text("No units", style = MaterialTheme.typography.bodySmall)
            } else {
                territory.stacks.forEach { stack ->
                    val sample = stack.units.firstOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        if (sample != null) UnitIcon(images, sample.type, sample.owner, size = 24)
                        Text(
                            "  ${stack.count} ${stack.typeName} (${stack.ownerName})",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Text("Tap a territory to see its units.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The national objectives, grouped by nation with the current nation first: a check for met ones,
 * the PU value on the right, and what the objective asks for underneath.
 */
@Composable
internal fun ObjectivesList(objectives: List<ObjectiveLine>, images: ImageCache, currentPlayer: String) {
    val byPlayer = remember(objectives, currentPlayer) {
        objectives.groupBy { it.player }.entries.sortedBy { if (it.key == currentPlayer) 0 else 1 }
    }
    byPlayer.forEach { (player, lines) ->
        val flag = remember(player, images) { images.getNow("flags/$player.png", listOf("flags/$player.png", "flags/${player}_small.png")) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            if (flag != null) {
                Image(flag.asImageBitmap(), contentDescription = player, modifier = Modifier.height(16.dp).widthIn(max = 28.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(player, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val met = lines.count { it.achieved }
            Text("$met / ${lines.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Text(
                    if (line.achieved) "\u2713" else "\u25cb",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (line.achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(line.title, style = MaterialTheme.typography.labelLarge, color = if (line.achieved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (line.description.isNotBlank()) {
                        Text(line.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (line.value != 0) {
                    Text(
                        (if (line.value > 0) "+" else "") + "${line.value} PU",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (line.achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** From this many nations on the diplomacy shows the desktop's matrix instead of a tab per nation. */
private const val MATRIX_FROM_NATIONS = 7
private val MATRIX_NAME_WIDTH = 44.dp
private val MATRIX_CELL = 34.dp

private fun relationOrder(line: RelationshipLine) = if (line.war) 0 else if (line.allied) 1 else 2

/** "Unfriendly_Neutral" -> "Unfriendly Neutral" */
private fun relationName(type: String) = type.replace('_', ' ')

/** A nation without a flag in the matrix: "Germans" -> "Ger", "Neutral_Allies" -> "NAl", "UK_Pacific" -> "UPa". */
private fun shortName(name: String): String {
    val parts = name.split('_', ' ').filter { it.isNotEmpty() }
    return if (parts.size < 2) name.take(3) else parts.first().take(1) + parts.drop(1).joinToString("") { it.take(2) }
}

/**
 * The matrix cell codes: the initials of each relation ("War" -> "W", "Friendly_Neutral" -> "FN"),
 * or its first letters when another relation already has those ("Custodianship" "Cu", "Concordant" "Co").
 */
private fun relationCodes(types: List<String>): Map<String, String> {
    val codes = LinkedHashMap<String, String>()
    types.forEach { type ->
        val initials = type.split('_', ' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.uppercase()
        codes[type] = (listOf(initials) + (2..type.length).map { type.take(it) }).firstOrNull { it !in codes.values } ?: type
    }
    return codes
}

/** A nation's flag fitted into a [size] box, when the map has one (the neutral pseudo nations usually have none). */
@Composable
internal fun NationFlag(images: ImageCache?, nation: String, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val flag = remember(nation, images) { images?.getNow("flags/$nation.png", listOf("flags/$nation.png", "flags/${nation}_small.png")) }
    if (flag != null) Image(flag.asImageBitmap(), contentDescription = displayName(nation), contentScale = ContentScale.Fit, modifier = modifier.size(size))
}

/**
 * A matrix row or column head: only the nation's flag (the short name when it has none), the name
 * as a tooltip on a tap or long press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NationBadge(images: ImageCache?, nation: String) {
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val flag = remember(nation, images) { images?.getNow("flags/$nation.png", listOf("flags/$nation.png", "flags/${nation}_small.png")) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
        tooltip = { PlainTooltip { Text(displayName(nation)) } },
        state = tooltip,
    ) {
        Box(
            Modifier
                .size(MATRIX_CELL)
                .clip(CircleShape)
                .clickable { scope.launch { tooltip.show() } },
            contentAlignment = Alignment.Center,
        ) {
            if (flag != null) {
                // a box, not only a height: with a free width the image keeps its own pixel width (32px)
                Image(flag.asImageBitmap(), contentDescription = displayName(nation), contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
            } else {
                Text(shortName(nation), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The relations of [nation] to every other nation, one slim row each, wars first then allies. */
@Composable
private fun NationRelations(relationships: List<RelationshipLine>, nation: String, images: ImageCache?) {
    val rows = remember(relationships, nation) {
        relationships.filter { it.player1 == nation || it.player2 == nation }.sortedBy { relationOrder(it) }
    }
    Column {
        rows.forEach { line ->
            val other = if (line.player1 == nation) line.player2 else line.player1
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(32.dp)) { NationFlag(images, other) }
                Text(displayName(other), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(relationName(line.type), style = MaterialTheme.typography.labelLarge, color = relationColor(line))
            }
            HorizontalDivider()
        }
    }
}

/**
 * Every relation of the game: a tab per nation with its relations when there are few nations,
 * the desktop politics panel's matrix when there are many. [initialNation] (the one whose turn it
 * is) is the selected tab.
 */
@Composable
internal fun DiplomacyView(relationships: List<RelationshipLine>, initialNation: String, images: ImageCache?) {
    val nations = remember(relationships) { (relationships.map { it.player1 } + relationships.map { it.player2 }).distinct() }
    if (nations.size >= MATRIX_FROM_NATIONS) {
        RelationMatrix(relationships, nations, images)
        return
    }
    var selected by rememberSaveable { mutableIntStateOf(nations.indexOf(initialNation).coerceAtLeast(0)) }
    val nation = nations[selected.coerceIn(nations.indices)]
    Column {
        NationTabs(nations, nations.indexOf(nation)) { selected = it }
        NationRelations(relationships, nation, images)
    }
}

/**
 * Every nation against every other in coloured cells, with a legend of the relation codes. The
 * name column stays in place while the cells scroll sideways, so every row keeps its nation.
 */
@Composable
private fun RelationMatrix(relationships: List<RelationshipLine>, nations: List<String>, images: ImageCache?) {
    val byPair = remember(relationships) {
        relationships.flatMap { listOf((it.player1 to it.player2) to it, (it.player2 to it.player1) to it) }.toMap()
    }
    val types = remember(relationships) { relationships.distinctBy { it.type }.sortedBy { relationOrder(it) } }
    val codes = remember(types) { relationCodes(types.map { it.type }) }
    Column {
        Row {
            Column(Modifier.width(MATRIX_NAME_WIDTH)) {
                Spacer(Modifier.height(MATRIX_CELL))
                nations.forEach { row -> NationBadge(images, row) }
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                // column heads: the same flag badges as the rows, in the same order
                Row { nations.forEach { NationBadge(images, it) } }
                nations.forEach { row ->
                    Row {
                        nations.forEach { column ->
                            val line = byPair[row to column]
                            Box(
                                Modifier.size(MATRIX_CELL).padding(1.dp).background(line?.let { relationCellColor(it) } ?: Color.Transparent, MaterialTheme.shapes.extraSmall),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(line?.let { codes[it.type] } ?: "–", style = MaterialTheme.typography.labelSmall, color = line?.let { relationCellText(it) } ?: Color.Unspecified)
                            }
                        }
                    }
                }
            }
        }
        RelationLegend(types, codes)
    }
}

/** The legend of the matrix as its own card: every code on a chip in its cell colour, then its name, in two columns. */
@Composable
private fun RelationLegend(types: List<RelationshipLine>, codes: Map<String, String>) {
    // collapsed: only the heading, a tap opens the codes
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Legend", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide legend" else "Show legend",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (open) types.chunked(2).forEach { pair ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    pair.forEach { line ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(MATRIX_CELL, 26.dp).background(relationCellColor(line), MaterialTheme.shapes.extraSmall),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(codes[line.type].orEmpty(), style = MaterialTheme.typography.labelSmall, color = relationCellText(line))
                            }
                            Text(relationName(line.type), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun relationCellColor(line: RelationshipLine): Color = when {
    line.war -> MaterialTheme.colorScheme.errorContainer
    line.allied -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** The text on a matrix cell: the "on" colour of its container, readable in light and dark theme. */
@Composable
private fun relationCellText(line: RelationshipLine): Color = when {
    line.war -> MaterialTheme.colorScheme.onErrorContainer
    line.allied -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun relationColor(line: RelationshipLine): Color = when {
    line.war -> MaterialTheme.colorScheme.error
    line.allied -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The tabs of the side panel; each shows one thing at a time. */
private enum class PanelTab(val label: String) {
    TURN("Turn"),
    ZONE("Zone"),
    STATS("Stats"),
    HISTORY("History"),
    GOALS("Goals"),
    DIPLOMACY("Diplomacy"),
}

/**
 * The details panel, one tab at a time. "Turn" is the nation whose turn it is with round, phase
 * and PUs plus the moves of the phase; "Zone" the tapped territory and the current selection;
 * then statistics, the history and (when the map has it) diplomacy.
 */
@Composable
internal fun SidePanel(
    status: GameStatus,
    gameName: String,
    resourceLine: String,
    images: ImageCache,
    playerColor: Color?,
    territory: TerritorySnapshot?,
    moveFrom: Territory?,
    moveUnits: List<Unit>,
    battle: BattleState?,
    stats: List<PlayerStats>,
    showVictoryCities: Boolean,
    history: List<HistoryBlock>,
    relationships: List<RelationshipLine>,
    objectives: List<ObjectiveLine> = emptyList(),
    movesThisPhase: @Composable ColumnScope.() -> kotlin.Unit = {},
    onFlagTap: (() -> kotlin.Unit)? = null,
    /** A tap on a history event shows it on the map. */
    onShowEvent: ((HistoryEvent) -> kotlin.Unit)? = null,
    /** Replays what happened since the player's last turn. */
    onReplay: (() -> kotlin.Unit)? = null,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember(relationships.isEmpty(), objectives.isEmpty()) {
        PanelTab.entries.filter { tab ->
            (tab != PanelTab.DIPLOMACY || relationships.isNotEmpty()) && (tab != PanelTab.GOALS || objectives.isNotEmpty())
        }
    }
    if (tabIndex >= tabs.size) tabIndex = 0
    val tab = tabs[tabIndex]
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { index, t ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                )
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            when (tab) {
                PanelTab.TURN -> {
                    PlayerHeader(status, gameName, resourceLine, images, playerColor, " · ", large = true, onFlagTap = onFlagTap)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Column(Modifier.fillMaxWidth()) { movesThisPhase() }
                    if (battle != null && battle.log.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("Battle: ${battle.territory}", style = MaterialTheme.typography.labelLarge)
                        battle.log.takeLast(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                PanelTab.ZONE -> {
                    TerritoryDetails(territory, images)
                    if (territory != null && !territory.isWater) {
                        Text(
                            (if (territory.isVictoryCity) "Victory city · " else "") + "${territory.production} PU",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (moveFrom != null && moveUnits.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("Selected in ${moveFrom.name}", style = MaterialTheme.typography.labelLarge)
                        Text(summarizeUnits(moveUnits), style = MaterialTheme.typography.bodySmall)
                    }
                }
                PanelTab.STATS -> if (stats.isEmpty()) Text("No statistics yet.", style = MaterialTheme.typography.bodySmall) else StatsTable(stats, showVictoryCities, status.playerName)
                PanelTab.HISTORY -> if (history.isEmpty()) Text("Nothing happened yet.", style = MaterialTheme.typography.bodySmall) else HistoryList(history, images, onShowEvent, onReplay)
                PanelTab.GOALS -> ObjectivesList(objectives, images, status.playerName)
                PanelTab.DIPLOMACY -> if (relationships.isEmpty()) Text("This map has no diplomacy.", style = MaterialTheme.typography.bodySmall) else DiplomacyView(relationships, status.playerName, images)
            }
        }
    }
}

/** The desktop client's stats tab: one row per player. */
@Composable
internal fun StatsTable(stats: List<PlayerStats>, showVictoryCities: Boolean, currentPlayer: String) {
    val cell = MaterialTheme.typography.labelSmall
    val columnWidth = 34.dp
    @Composable
    fun Cell(text: String, bold: Boolean = false) {
        Text(
            text,
            style = cell,
            fontWeight = if (bold) FontWeight.Bold else null,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(columnWidth),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Player", style = cell, modifier = Modifier.weight(1f))
        Cell("PUs")
        Cell("Prod")
        Cell("Terr")
        Cell("Units")
        Cell("TUV")
        if (showVictoryCities) Cell("VC")
    }
    HorizontalDivider()
    stats.forEach { row ->
        val bold = row.name == currentPlayer
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name,
                style = cell,
                fontWeight = if (bold) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Cell(row.pus.toString(), bold)
            Cell(row.production.toString(), bold)
            Cell(row.territories.toString(), bold)
            Cell(row.units.toString(), bold)
            Cell(row.tuv.toString(), bold)
            if (showVictoryCities) Cell(row.victoryCities.toString(), bold)
        }
    }
    // the technologies each nation has researched, as the desktop's tech panel shows them
    val researched = stats.filter { it.technologies.isNotEmpty() }
    if (researched.isNotEmpty()) {
        Text(
            "Technology",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        HorizontalDivider()
        researched.forEach { row ->
            val bold = row.name == currentPlayer
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text(
                    row.name,
                    style = cell,
                    fontWeight = if (bold) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    row.technologies.joinToString(", "),
                    style = cell,
                    fontWeight = if (bold) FontWeight.Bold else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What the history list shows. */
private enum class HistoryFilter(val label: String) { ALL("All"), BATTLES("Battles"), MOVES("Moves"), BUYS("Buys & places") }

/**
 * The game history of all nations as a collapsible tree like the desktop history panel:
 * rounds (newest first) > steps > events > details. Events show the units they are about as
 * icons; battles open into their dice rolls (hits red) and casualties. A filter row narrows the
 * list to battles, moves, or purchases and placements. The latest round and step start open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryList(
    history: List<HistoryBlock>,
    images: ImageCache?,
    onShowEvent: ((HistoryEvent) -> kotlin.Unit)? = null,
    onReplay: (() -> kotlin.Unit)? = null,
) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    if (onReplay != null) {
        // what the other players did since my last turn, step by step on the map
        TextButton(onClick = onReplay, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Replay since my last turn", style = MaterialTheme.typography.labelMedium)
        }
    }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val filtered = remember(history, filter) {
        if (filter == HistoryFilter.ALL) history
        else history.mapNotNull { block ->
            val events = block.events.filter { event ->
                when (filter) {
                    HistoryFilter.BATTLES -> event.kind == HistoryKind.BATTLE
                    HistoryFilter.MOVES -> event.kind == HistoryKind.MOVE
                    HistoryFilter.BUYS -> event.kind == HistoryKind.PURCHASE || event.kind == HistoryKind.PLACE
                    HistoryFilter.ALL -> true
                }
            }
            if (events.isEmpty()) null else HistoryBlock(block.round, block.title, block.player, events)
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HistoryFilter.entries.forEach { f ->
            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label, style = MaterialTheme.typography.labelSmall) })
        }
    }
    val rounds = remember(filtered) { filtered.groupBy { it.round }.entries.sortedByDescending { it.key } }
    if (rounds.isEmpty()) {
        Text("Nothing of that kind yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        return
    }
    val latestRound = rounds.first().key
    val latestStep = filtered.lastOrNull()
    rounds.forEach { (round, blocks) ->
        val roundKey = "r$round"
        val roundOpen = open[roundKey] ?: (round == latestRound)
        TreeRow(
            title = "Round $round",
            subtitle = "${blocks.size} step(s) · ${blocks.sumOf { it.events.size }} event(s)",
            expanded = roundOpen,
            level = 0,
            color = MaterialTheme.colorScheme.primary,
            onToggle = { open[roundKey] = !roundOpen },
        )
        if (!roundOpen) return@forEach
        blocks.forEachIndexed { stepIndex, block ->
            val stepKey = "$roundKey/s$stepIndex/${block.title}"
            val stepOpen = open[stepKey] ?: (block === latestStep)
            val battles = block.events.count { it.kind == HistoryKind.BATTLE }
            TreeRow(
                title = block.title,
                subtitle = listOfNotNull(
                    "${block.events.size} event(s)",
                    if (battles > 0) "$battles battle(s)" else null,
                ).joinToString(" · "),
                expanded = stepOpen,
                level = 1,
                color = MaterialTheme.colorScheme.onSurface,
                flag = block.player.takeIf { it.isNotBlank() }?.let { images?.getNow("flags/$it.png", listOf("flags/$it.png", "flags/${it}_small.png")) },
                onToggle = { open[stepKey] = !stepOpen },
            )
            if (!stepOpen) return@forEachIndexed
            block.events.forEachIndexed { eventIndex, event ->
                val eventKey = "$stepKey/e$eventIndex"
                val eventOpen = open[eventKey] ?: false
                HistoryEventRow(
                    event,
                    eventOpen,
                    images,
                    // every event has a moment on the map, as in the desktop history tree
                    onShow = onShowEvent?.let { show -> { show(event) } },
                ) { open[eventKey] = !eventOpen }
            }
        }
    }
}

/** One event: its text, the units as icons; opens into its detail lines with dice and units. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryEventRow(
    event: HistoryEvent,
    expanded: Boolean,
    images: ImageCache?,
    /** Shows the event on the map; null when it has no place there. */
    onShow: (() -> kotlin.Unit)? = null,
    onToggle: () -> kotlin.Unit,
) {
    val expandable = event.details.isNotEmpty()
    // a tap shows the event on the map and opens its details; either alone when only one applies
    val onClick: (() -> kotlin.Unit)? = when {
        onShow != null && expandable -> ({ onShow(); onToggle() })
        onShow != null -> onShow
        expandable -> onToggle
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 24.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                // a move is "from -> to" (with the number of steps when it went further), a
                // placement its territory, a purchase only its units; battles stand out by color
                val headline = when {
                    event.kind == HistoryKind.MOVE && event.route.size >= 2 ->
                        "${event.route.first()} → ${event.route.last()}" + if (event.route.size > 2) "  (${event.route.size - 1})" else ""
                    event.kind == HistoryKind.PLACE && event.route.isNotEmpty() -> event.route.first()
                    event.kind == HistoryKind.PURCHASE && event.units.isNotEmpty() -> ""
                    event.kind == HistoryKind.BATTLE -> event.text.removePrefix("Battle in ").removePrefix("Air Battle in ")
                    else -> event.text
                }
                if (headline.isNotBlank()) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (event.kind == HistoryKind.BATTLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (event.units.isNotEmpty()) UnitRefRow(event.units, images)
                if (event.kind == HistoryKind.BATTLE && event.diceCount > 0 && !expanded) {
                    Text(
                        "${event.diceCount} dice, ${event.hitCount} hits",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expandable) {
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            event.details.forEach { detail ->
                Column(Modifier.padding(start = 20.dp, top = 2.dp)) {
                    Text(
                        // the engine repeats the dice as text; the dice below say the same
                        if (detail.dice.isNotEmpty()) detail.text.substringBefore(" : ") else detail.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.dice.isNotEmpty()) {
                        val hits = detail.dice.count { it.second }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                detail.dice.forEach { (value, hit) -> Die(value, hit, size = 16) }
                            }
                            Text(
                                "$hits/${detail.dice.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (hits > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (detail.units.isNotEmpty()) UnitRefRow(detail.units, images)
                }
            }
        }
    }
}

/** Unit icons with counts, wrapping; up to twelve types. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitRefRow(units: List<UnitRef>, images: ImageCache?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
        units.take(12).forEach { ref ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnitIcon(images, ref.sample.type, ref.sample.owner, size = 20)
                Text("×${ref.count}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
            }
        }
        if (units.size > 12) Text("…", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun TreeRow(
    title: String,
    subtitle: String?,
    expanded: Boolean,
    level: Int,
    color: androidx.compose.ui.graphics.Color,
    onToggle: () -> kotlin.Unit,
    bold: Boolean = true,
    flag: android.graphics.Bitmap? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = (level * 12).dp, top = 3.dp, bottom = 3.dp),
    ) {
        Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.width(14.dp),
        )
        if (flag != null) {
            Image(flag.asImageBitmap(), contentDescription = null, modifier = Modifier.height(14.dp).widthIn(max = 24.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (bold) FontWeight.Bold else null,
                color = color,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Flag, round, player and step of the nation whose turn it is; a colored bar shows its map color. */
@Composable
internal fun PlayerHeader(
    status: GameStatus,
    gameName: String,
    resourceLine: String,
    images: ImageCache,
    playerColor: androidx.compose.ui.graphics.Color?,
    separator: String,
    large: Boolean = false,
    /** Only flag, nation and round; step and resources live elsewhere on the phone layout. */
    compact: Boolean = false,
    onFlagTap: (() -> kotlin.Unit)? = null,
) {
    val flag = remember(status.playerName, images) {
        if (status.playerName.isBlank()) null
        else images.getNow(
            "flags/${status.playerName}.png",
            listOf("flags/${status.playerName}.png", "flags/${status.playerName}_small.png", "flags/${status.playerName}_large.png"),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (playerColor != null) {
            Box(Modifier.width(5.dp).height(if (large) 36.dp else 30.dp).background(playerColor, MaterialTheme.shapes.extraSmall))
            Spacer(Modifier.width(8.dp))
        }
        val flagModifier = Modifier
            .height(if (large || compact) 28.dp else 20.dp)
            .widthIn(max = 48.dp)
            .then(if (onFlagTap != null) Modifier.clickable(onClick = onFlagTap) else Modifier)
        if (flag != null) {
            Image(flag.asImageBitmap(), contentDescription = status.playerName, modifier = flagModifier)
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                if (compact) status.playerName.ifBlank { gameName }
                else if (status.round > 0) "Round ${status.round}$separator${status.playerName}" else gameName,
                style = if (large || compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (compact) {
                if (status.round > 0) {
                    Text("Round ${status.round}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.stepName.isNotBlank()) {
                    Icon(stepIcon(status.stepName), contentDescription = status.stepDisplayName, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    listOf(status.stepDisplayName, resourceLine).filter { it.isNotBlank() }.joinToString(separator),
                    style = if (large) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
