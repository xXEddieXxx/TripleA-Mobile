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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import org.triplea.mobile.app.game.BattleState
import org.triplea.mobile.app.game.GameStatus
import org.triplea.mobile.app.game.HistoryBlock
import org.triplea.mobile.app.game.HistoryEvent
import org.triplea.mobile.app.game.HistoryKind
import org.triplea.mobile.app.game.UnitRef
import org.triplea.mobile.app.game.PlayerStats
import org.triplea.mobile.app.game.RelationshipLine
import org.triplea.mobile.app.game.TerritorySnapshot
import org.triplea.mobile.app.render.ImageCache

/*
 * The side panel content of the game screen: the phone's collapsible details panel and the
 * desktop like tabbed panel for tablets, plus the pieces both are built from.
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

/** Relationships of every nation, grouped like the desktop client's politics tab. */
@Composable
internal fun DiplomacyList(relationships: List<RelationshipLine>) {
    val players = remember(relationships) {
        (relationships.map { it.player1 } + relationships.map { it.player2 }).distinct()
    }
    players.forEach { player ->
        val mine = relationships.filter { it.player1 == player || it.player2 == player }
        val byType = mine.groupBy { it.type }
        Text(player, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        byType.entries.sortedBy { (_, lines) -> if (lines.first().war) 0 else if (lines.first().allied) 1 else 2 }.forEach { (type, lines) ->
            val others = lines.joinToString(", ") { if (it.player1 == player) it.player2 else it.player1 }
            Text(
                "$type: $others",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    lines.first().war -> MaterialTheme.colorScheme.error
                    lines.first().allied -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The tabs of the side panel; each shows one thing at a time. */
private enum class PanelTab(val label: String) {
    TURN("Turn"),
    ZONE("Zone"),
    STATS("Stats"),
    HISTORY("History"),
    DIPLOMACY("Diplomacy"),
}

/**
 * The side panel of every layout: one tab at a time. "Turn" is the nation whose turn it is with
 * round, phase and PUs plus the actions and moves of the phase; "Zone" the tapped territory and
 * the current selection; then statistics, the history and (when the map has it) diplomacy.
 */
@Composable
internal fun SidePanel(
    status: GameStatus,
    gameName: String,
    resourceLine: String,
    images: ImageCache,
    playerColor: Color?,
    hint: String,
    territory: TerritorySnapshot?,
    moveFrom: Territory?,
    moveUnits: List<Unit>,
    battle: BattleState?,
    stats: List<PlayerStats>,
    showVictoryCities: Boolean,
    history: List<HistoryBlock>,
    relationships: List<RelationshipLine>,
    menu: @Composable () -> kotlin.Unit,
    actions: @Composable ColumnScope.() -> kotlin.Unit,
    movesThisPhase: @Composable ColumnScope.() -> kotlin.Unit = {},
    /** True on the desktop layout, where the panel carries the flag row and the menu. */
    showHeader: Boolean = true,
    onFlagTap: (() -> kotlin.Unit)? = null,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember(relationships.isEmpty()) {
        if (relationships.isEmpty()) PanelTab.entries.filter { it != PanelTab.DIPLOMACY } else PanelTab.entries.toList()
    }
    if (tabIndex >= tabs.size) tabIndex = 0
    val tab = tabs[tabIndex]
    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PlayerHeader(status, gameName, resourceLine, images, playerColor, " · ", onFlagTap = onFlagTap)
                }
                menu()
            }
        }
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
                    if (!showHeader) {
                        PlayerHeader(status, gameName, resourceLine, images, playerColor, " · ", large = true, onFlagTap = onFlagTap)
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    }
                    if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        actions()
                    }
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
                PanelTab.HISTORY -> if (history.isEmpty()) Text("Nothing happened yet.", style = MaterialTheme.typography.bodySmall) else HistoryList(history, images)
                PanelTab.DIPLOMACY -> if (relationships.isEmpty()) Text("This map has no diplomacy.", style = MaterialTheme.typography.bodySmall) else DiplomacyList(relationships)
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
internal fun HistoryList(history: List<HistoryBlock>, images: ImageCache?) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
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
                HistoryEventRow(event, eventOpen, images) { open[eventKey] = !eventOpen }
            }
        }
    }
}

/** One event: its text, the units as icons; opens into its detail lines with dice and units. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryEventRow(event: HistoryEvent, expanded: Boolean, images: ImageCache?, onToggle: () -> kotlin.Unit) {
    val expandable = event.details.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
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
