package org.triplea.mobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Optional
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Unit
import games.strategy.engine.data.UnitType
import org.triplea.mobile.app.game.BattleState
import org.triplea.mobile.app.game.BattleStrengths
import org.triplea.mobile.app.game.CasualtyNoticeRequest
import org.triplea.mobile.app.game.CasualtyRequest
import org.triplea.mobile.app.game.ConfirmRequest
import org.triplea.mobile.app.game.RetreatRequest
import org.triplea.mobile.app.game.SelectUnitsRequest
import org.triplea.mobile.app.game.UiRequest
import org.triplea.mobile.app.game.RoundLosses
import org.triplea.mobile.app.game.SideStrength
import org.triplea.mobile.app.render.ImageCache

/**
 * The battle window as a front line the eye can read at a glance: attackers on the left,
 * defenders on the right, facing each other. One row per strength value (the number in the
 * middle), weakest at the top. Dice appear next to the units they were fired at: the attacker's
 * dice just left of the defenders, the defender's dice just right of the attackers; hits are red
 * and come first. Under each side its losses, round by round.
 *
 * Losses are chosen right on the front line by tapping units; the explanation of the current
 * moment is behind the "?" button, so the window shows as little text as possible.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BattleWindow(
    battle: BattleState,
    images: ImageCache?,
    onDismiss: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
    notice: CasualtyNoticeRequest? = null,
    /** The engine is waiting for this player to choose their losses in this battle. */
    casualtyRequest: CasualtyRequest? = null,
    /** A retreat, bombardment, raid or target question about this battle, answered in the footer. */
    question: UiRequest<*>? = null,
    strengths: BattleStrengths? = null,
    colorOf: (String) -> Color? = { null },
    showHelp: Boolean = false,
    onDismissHelp: () -> kotlin.Unit = {},
    /** Landscape phones: smaller tiles, tighter rows, losses next to the names, so nothing scrolls. */
    compact: Boolean = false,
) {
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showHint by rememberSaveable { mutableStateOf(false) }
    val attackerColor = colorOf(battle.attacker) ?: MaterialTheme.colorScheme.primary
    val defenderColor = colorOf(battle.defender) ?: MaterialTheme.colorScheme.tertiary
    val attackGroups = remember(battle.attackingUnits, strengths) { groupByStrength(battle.attackingUnits, strengths?.attackers) }
    val defendGroups = remember(battle.defendingUnits, strengths) { groupByStrength(battle.defendingUnits, strengths?.defenders) }
    val strengthsPresent = remember(attackGroups, defendGroups, battle.lastDiceByStrength) {
        (attackGroups.keys + defendGroups.keys + battle.lastDiceByStrength.keys).filter { it >= 0 }.sorted()
    }
    val attackerRolled = battle.lastDiceSide == battle.attacker && battle.lastDice.isNotEmpty()
    val defenderRolled = battle.lastDiceSide == battle.defender && battle.lastDice.isNotEmpty()
    val status = battleStatus(battle, casualtyRequest, notice)
    // losses are chosen right on the front line: tapping a unit of the side that was hit marks it
    val choice = remember(casualtyRequest) { casualtyRequest?.let { CasualtyChoice(it) } }
    val hitSide = casualtyRequest?.hit?.name
    // the badge with the chosen losses goes on the first tile (lowest strength) of a unit type
    val badgeStrength: Map<Pair<String, String>, Int> = remember(attackGroups, defendGroups, hitSide) {
        val groups = if (hitSide == battle.attacker) attackGroups else defendGroups
        val first = HashMap<Pair<String, String>, Int>()
        groups.entries.sortedBy { it.key }.forEach { (strength, list) ->
            list.forEach { g -> first.putIfAbsent(g.type.name to g.owner.name, strength) }
        }
        first
    }
    val tileState: (UnitGroup, Int, Boolean) -> TileState? = { group, strength, attackerSide ->
        val side = if (attackerSide) battle.attacker else battle.defender
        if (choice == null || side != hitSide) null
        else {
            val key = group.type.name to group.owner.name
            TileState(
                chosen = if (badgeStrength[key] == strength) choice.chosen(key.first, key.second) else 0,
                onTap = { choice.tap(key.first, key.second) },
            )
        }
    }

    val tile = if (compact) 28 else 34
    Card(
        modifier = modifier.widthIn(max = if (compact) 680.dp else 560.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column {
            // ---- header: where, which round; "?" for the explanation
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = if (compact) 2.dp else 8.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val roundText = if (battle.ended) "Round ${battle.round} · over" else "Round ${battle.round}"
                if (compact) {
                    Text(battle.territory, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        roundText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else Column(Modifier.weight(1f)) {
                    Text(battle.territory, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        roundText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { showHint = !showHint }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(
                        "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (showHint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showHint) {
                StatusLine(status, Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }

            // everything between the header and the footer scrolls when the window is taller
            // than the screen allows, so the buttons never leave the screen
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
            ) {
                if (showHint && showHelp) BattleHelpCard(onDismissHelp)

                // ---- the two sides (in the compact layout with their losses right next to the name)
                val attackerLost = battle.lossesByRound.flatMap { it.attackerLost }
                val defenderLost = battle.lossesByRound.flatMap { it.defenderLost }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SideHeader(battle.attacker, attackerColor, strengths?.attackers, images, left = true, modifier = Modifier.weight(1f), losses = if (compact) attackerLost else null)
                    // the direction of the attack: from the attacker towards the defender
                    Text(
                        "›››",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = attackerColor,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    SideHeader(battle.defender, defenderColor, strengths?.defenders, images, left = false, modifier = Modifier.weight(1f), losses = if (compact) defenderLost else null)
                }

                // ---- the front line: one row per strength, attackers facing defenders
                if (strengthsPresent.isEmpty()) {
                    Text("—", modifier = Modifier.padding(8.dp).align(Alignment.CenterHorizontally))
                }
                strengthsPresent.forEach { strength ->
                    StrengthRow(
                        strength = strength,
                        attackers = attackGroups[strength].orEmpty(),
                        defenders = defendGroups[strength].orEmpty(),
                        attackerDice = if (attackerRolled) battle.lastDiceByStrength[strength].orEmpty() else emptyList(),
                        defenderDice = if (defenderRolled) battle.lastDiceByStrength[strength].orEmpty() else emptyList(),
                        attackerColor = attackerColor,
                        defenderColor = defenderColor,
                        images = images,
                        attackerTile = { g -> tileState(g, strength, true) },
                        defenderTile = { g -> tileState(g, strength, false) },
                        tile = tile,
                        compact = compact,
                    )
                }

                // ---- losses so far, round by round, under each side (compact: next to the names)
                if (!compact && battle.lossesByRound.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        LossTally(battle.lossesByRound, attacker = true, images = images, left = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        LossTally(battle.lossesByRound, attacker = false, images = images, left = false, modifier = Modifier.weight(1f))
                    }
                }

                // ---- log for experts
                if (battle.log.isNotEmpty()) {
                    Text(
                        if (showLog) "▾ log" else "▸ log (${battle.log.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).clickable { showLog = !showLog },
                    )
                    if (showLog) {
                        Column(Modifier.heightIn(max = 160.dp).padding(bottom = 4.dp)) {
                            battle.log.takeLast(12).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ---- footer, kept in view: every question of the battle is answered here
            when {
                question is RetreatRequest -> {
                    HorizontalDivider()
                    RetreatFooter(question)
                }
                question is ConfirmRequest -> {
                    HorizontalDivider()
                    QuestionFooter(question)
                }
                question is SelectUnitsRequest -> {
                    HorizontalDivider()
                    TargetFooter(question, images)
                }
                choice != null && casualtyRequest != null -> {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Losses ${choice.total}/${choice.killsNeeded}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (choice.complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (choice.damagedDefaults.isNotEmpty()) "${choice.damagedDefaults.size} damaged" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        FilledIconButton(enabled = choice.complete, onClick = { choice.confirm() }) {
                            Icon(Icons.Filled.Check, contentDescription = "confirm losses")
                        }
                    }
                }
                notice != null -> {
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { notice.complete(true) }, contentPadding = PaddingValues(horizontal = 18.dp)) {
                            Text(if (battle.ended) "Continue" else "Next round")
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ questions in the footer

/** Retreat (or submerge) with the possible targets; the check on the right keeps fighting, the default. */
@Composable
private fun RetreatFooter(request: RetreatRequest) {
    var menu by remember { mutableStateOf(false) }
    val canSubmerge = request.submerge && request.battleTerritory in request.possibleTerritories
    val targets = request.possibleTerritories.filter { !(request.submerge && it == request.battleTerritory) }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (canSubmerge) "Submerge or retreat?" else "Retreat?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (canSubmerge) {
            FilledTonalButton(onClick = { request.complete(Optional.of(request.battleTerritory)) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Submerge") }
            Spacer(Modifier.width(4.dp))
        }
        if (targets.size == 1) {
            FilledTonalButton(onClick = { request.complete(Optional.of(targets[0])) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("To ${targets[0].name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (targets.isNotEmpty()) {
            FilledTonalButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Retreat to\u2026") }
            if (menu) {
                // the same dialog frame as everywhere else; the X returns to the battle
                AppDialog(title = "Retreat to", onDismiss = { menu = false }, buttons = {}) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        targets.forEach { territory ->
                            OptionRow(title = territory.name, onClick = { menu = false; request.complete(Optional.of(territory)) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        // the check keeps fighting: the default answer to the retreat question
        FilledIconButton(onClick = { request.complete(Optional.empty()) }) {
            Icon(Icons.Filled.Check, contentDescription = "keep fighting")
        }
    }
}

/** A yes/no question of the battle: attack submarines, bombard, raid. Check = yes, X = no (default). */
@Composable
private fun QuestionFooter(request: ConfirmRequest) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${request.title}?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        FilledTonalIconButton(onClick = { request.complete(true) }) {
            Icon(Icons.Filled.Check, contentDescription = "yes")
        }
        Spacer(Modifier.width(2.dp))
        FilledIconButton(onClick = { request.complete(false) }) {
            Icon(Icons.Filled.Close, contentDescription = "no")
        }
    }
}

/** The target of a bombing raid: tap a unit; the X takes the first one (default). */
@Composable
private fun TargetFooter(request: SelectUnitsRequest, images: ImageCache?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(request.title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            request.candidates.groupBy { it.type to it.owner }.forEach { (key, units) ->
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .clickable { request.complete(units.take(request.max.coerceAtLeast(1))) }
                        .padding(2.dp),
                ) {
                    UnitIcon(images, key.first, key.second, size = 30)
                    if (units.size > 1) {
                        Text("\u00d7${units.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
        FilledIconButton(onClick = { request.complete(emptyList()) }) {
            Icon(Icons.Filled.Close, contentDescription = "default target")
        }
    }
}

// ------------------------------------------------------------------ what is going on

private enum class Tone { WAIT, ACT, DONE }

private class BattleStatus(val text: String, val tone: Tone)

/** One sentence about the current moment of the battle, and whether the player must act. */
private fun battleStatus(b: BattleState, request: CasualtyRequest?, notice: CasualtyNoticeRequest?): BattleStatus = when {
    b.ended -> BattleStatus(b.endMessage.ifBlank { "The battle is over." }, Tone.DONE)
    request != null -> BattleStatus(
        "${request.hit.name}: ${request.count} hit(s). Tap the units to lose; the weakest are preselected. Confirm with the check.",
        Tone.ACT,
    )
    notice != null -> BattleStatus(
        "Round over. Next round: both sides fire again until one side is gone or retreats.",
        Tone.ACT,
    )
    b.lastDice.isNotEmpty() && b.lastDiceSide.isNotBlank() -> BattleStatus(
        "${b.lastDiceSide}: ${b.lastDice.size} dice, ${b.lastDiceHits} hit(s). A die at or below the strength is a hit.",
        Tone.WAIT,
    )
    b.currentStep.isNotBlank() -> BattleStatus("${b.currentStep}…", Tone.WAIT)
    else -> BattleStatus("The battle begins: every round each unit rolls one die.", Tone.WAIT)
}

@Composable
private fun StatusLine(status: BattleStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status.tone) {
        Tone.WAIT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Tone.ACT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Tone.DONE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    InfoNote(status.text, modifier = modifier, container = container, content = content)
}

/** A short lesson for new players, shown until they tap "Got it". */
@Composable
private fun BattleHelpCard(onDismiss: () -> kotlin.Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("How a battle works", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            listOf(
                "Attackers stand on the left, defenders on the right. Every round each unit rolls one die; the number in the middle is the strength, and a die at or below it is a hit (red).",
                "Dice appear next to the units they hit: the attacker's dice by the defenders, the defender's dice by the attackers.",
                "A chain between units means the one on its left (artillery) supports the ones on its right, so they fight above their own value.",
                "Whoever is hit taps the units to lose; the weakest are preselected. The check confirms.",
                "Rounds repeat until one side is gone or the attacker retreats. Under each side you see its losses per round.",
            ).forEach {
                Row(Modifier.padding(top = 4.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Got it") }
        }
    }
}

// ------------------------------------------------------------------ the front line

/**
 * Flag, name and power of one side, above its own units; mirrored for the right side. With
 * [losses] the units lost so far stand next to the name as faded icons with the total.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SideHeader(
    player: String,
    color: Color,
    side: SideStrength?,
    images: ImageCache?,
    left: Boolean,
    modifier: Modifier = Modifier,
    losses: List<Unit>? = null,
) {
    val flag = remember(player, images) { images?.getNow("flags/$player.png", listOf("flags/$player.png", "flags/${player}_small.png")) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (left) Arrangement.Start else Arrangement.End,
        modifier = modifier,
    ) {
        val bar: @Composable () -> kotlin.Unit = { Box(Modifier.width(4.dp).height(22.dp).background(color, MaterialTheme.shapes.extraSmall)) }
        val flagImage: @Composable () -> kotlin.Unit = {
            if (flag != null) Image(flag.asImageBitmap(), contentDescription = player, modifier = Modifier.height(16.dp).widthIn(max = 28.dp))
        }
        val name: @Composable RowScope.() -> kotlin.Unit = {
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (left) Alignment.Start else Alignment.End) {
                Text(
                    player,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (left) TextAlign.Start else TextAlign.End,
                )
                if (side != null) {
                    Text(
                        "power ${side.totalPower}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        val tally: @Composable () -> kotlin.Unit = {
            if (!losses.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "−${losses.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), maxItemsInEachRow = 5) {
                    losses.groupBy { it.type to it.owner }.forEach { (key, list) ->
                        Box(Modifier.alpha(0.5f)) {
                            UnitIcon(images, key.first, key.second, size = 20)
                            if (list.size > 1) Text("×${list.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
            }
        }
        if (left) {
            bar(); Spacer(Modifier.width(6.dp)); flagImage(); Spacer(Modifier.width(6.dp)); name(); tally()
        } else {
            tally(); Spacer(Modifier.width(6.dp)); name(); Spacer(Modifier.width(6.dp)); flagImage(); Spacer(Modifier.width(6.dp)); bar()
        }
    }
}

/**
 * One strength value: the attackers of that strength on the left, the defender's dice that hit
 * them, the strength, the attacker's dice that hit the defenders, the defenders on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrengthRow(
    strength: Int,
    attackers: List<UnitGroup>,
    defenders: List<UnitGroup>,
    attackerDice: List<Pair<Int, Boolean>>,
    defenderDice: List<Pair<Int, Boolean>>,
    attackerColor: Color,
    defenderColor: Color,
    images: ImageCache?,
    attackerTile: (UnitGroup) -> TileState? = { null },
    defenderTile: (UnitGroup) -> TileState? = { null },
    tile: Int = 34,
    compact: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 1.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // attackers, pushed towards the middle
        Surface(
            color = attackerColor.copy(alpha = if (attackers.isEmpty()) 0.04f else 0.14f),
            shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 4.dp, bottomEnd = 4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = if (compact) 2.dp else 4.dp).heightIn(min = tile.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    SideTiles(attackers, images, attackerTile, tile)
                }
            }
        }
        // the defender's dice hit the attackers, so they sit right next to the attackers; the
        // slots keep their width even without dice so the two sides never shift
        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) { DiceStack(defenderDice, if (compact) 13 else 15) }
        // the strength in the middle: what a die must roll at or below
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape, modifier = Modifier.padding(horizontal = 3.dp)) {
            Text(
                "$strength",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(26.dp).padding(vertical = 3.dp),
            )
        }
        // the attacker's dice hit the defenders, so they sit right next to the defenders
        Box(Modifier.width(38.dp), contentAlignment = Alignment.Center) { DiceStack(attackerDice, if (compact) 13 else 15) }
        // defenders
        Surface(
            color = defenderColor.copy(alpha = if (defenders.isEmpty()) 0.04f else 0.14f),
            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = if (compact) 2.dp else 4.dp).heightIn(min = tile.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    SideTiles(defenders, images, defenderTile, tile)
                }
            }
        }
    }
}

/** Dice stacked in a narrow block next to the units they hit; hits first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiceStack(dice: List<Pair<Int, Boolean>>, size: Int = 15) {
    if (dice.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.widthIn(max = 34.dp),
    ) {
        dice.sortedByDescending { it.second }.take(12).forEach { (value, hit) -> Die(value, hit, size = size) }
    }
}

/** The losses of one side, round by round: "R1" and the lost units as faded icons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LossTally(losses: List<RoundLosses>, attacker: Boolean, images: ImageCache?, left: Boolean, modifier: Modifier = Modifier) {
    val rounds = losses.filter { (if (attacker) it.attackerLost else it.defenderLost).isNotEmpty() }
    val total = rounds.sumOf { (if (attacker) it.attackerLost else it.defenderLost).size }
    Column(modifier, horizontalAlignment = if (left) Alignment.Start else Alignment.End) {
        Text(
            if (total == 0) "no losses" else "−$total",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (total == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        rounds.takeLast(4).asReversed().forEach { round ->
            val units = if (attacker) round.attackerLost else round.defenderLost
            // one line per round, newest first
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp, if (left) Alignment.Start else Alignment.End),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                units.groupBy { it.type to it.owner }.forEach { (key, list) ->
                    Box(Modifier.alpha(0.55f)) {
                        UnitIcon(images, key.first, key.second, size = 22)
                        if (list.size > 1) {
                            Text("×${list.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The unit groups of one side in one strength row, in support order: units without support,
 * then the supporting unit (artillery), a chain, and the units it supports. The chain only
 * appears when something is supported.
 */
@Composable
private fun SideTiles(groups: List<UnitGroup>, images: ImageCache?, tileFor: (UnitGroup) -> TileState?, size: Int) {
    val supported = groups.filter { it.supporter != null }
    val supporterTypes = supported.mapNotNull { it.supporter }.toSet()
    val supporters = groups.filter { it.supporter == null && it.type in supporterTypes }
    val others = groups.filter { it.supporter == null && it.type !in supporterTypes }
    others.forEach { UnitTile(it, images, tileFor(it), size = size) }
    supporters.forEach { UnitTile(it, images, tileFor(it), size = size) }
    if (supported.isNotEmpty()) {
        Box(Modifier.heightIn(min = size.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Link,
                contentDescription = "supports",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size * 0.42f).toInt().dp),
            )
        }
    }
    supported.forEach { UnitTile(it, images, tileFor(it), size = size) }
}

/** How a tile takes part in choosing losses: how many of its units are marked, and what a tap does. */
class TileState(val chosen: Int, val onTap: () -> kotlin.Unit)

/**
 * An icon with the count in the corner; several dice per unit are shown as "2×". While losses
 * are chosen, the tiles of the side that was hit are tappable: the marked units get a red badge
 * and fade.
 */
@Composable
private fun UnitTile(group: UnitGroup, images: ImageCache?, tile: TileState? = null, size: Int = 34) {
    val marked = tile != null && tile.chosen > 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .then(
                if (tile != null) Modifier
                    .background(MaterialTheme.colorScheme.error.copy(alpha = if (marked) 0.22f else 0.08f), RoundedCornerShape(6.dp))
                    .clickable(onClick = tile.onTap)
                else Modifier,
            ),
    ) {
        Box {
            Box(Modifier.alpha(if (marked) 0.5f else 1f)) { UnitIcon(images, group.type, group.owner, size = size) }
            if (group.count > 1) {
                Surface(color = Color(0xE6202020), shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.BottomEnd)) {
                    Text("×${group.count}", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
            if (group.rolls > 1 && !marked) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("${group.rolls}×", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
            if (marked) {
                Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.TopStart)) {
                    Text("−${tile.chosen}", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
        }
    }
}

private data class UnitGroup(val type: UnitType, val owner: GamePlayer, val strength: Int, val rolls: Int, val count: Int, val boost: Int = 0, val supporter: UnitType? = null)

private data class GroupKey(val type: UnitType, val owner: GamePlayer, val strength: Int, val rolls: Int, val boost: Int, val supporter: UnitType?)

/** Groups units by strength, then by type; units boosted by support or terrain form their own group. */
private fun groupByStrength(units: List<Unit>, side: SideStrength?): Map<Int, List<UnitGroup>> =
    units.groupBy { unit -> GroupKey(unit.type, unit.owner, side?.strength?.get(unit) ?: -1, side?.rolls?.get(unit) ?: 1, side?.boost?.get(unit) ?: 0, side?.supporter?.get(unit)) }
        .map { (key, list) -> UnitGroup(key.type, key.owner, key.strength, key.rolls, list.size, key.boost, key.supporter) }
        .sortedWith(compareBy<UnitGroup> { it.strength }.thenBy { it.type.name })
        .groupBy { it.strength }

/** A die face with pips; hits are drawn red like in the desktop client. */
@Composable
fun Die(value: Int, hit: Boolean, size: Int = 22) {
    val face = if (hit) Color(0xFFD32F2F) else Color(0xFFF5F5F5)
    val pip = if (hit) Color.White else Color(0xFF212121)
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width
        val radius = s * 0.18f
        drawRoundRect(color = face, size = Size(s, s), cornerRadius = CornerRadius(radius, radius))
        drawRoundRect(color = Color(0x66000000), size = Size(s, s), cornerRadius = CornerRadius(radius, radius), style = Stroke(width = s * 0.05f))
        val r = s * 0.09f
        val a = s * 0.25f
        val b = s * 0.5f
        val c = s * 0.75f
        val pips: List<Offset> = when (value.coerceIn(1, 6)) {
            1 -> listOf(Offset(b, b))
            2 -> listOf(Offset(a, a), Offset(c, c))
            3 -> listOf(Offset(a, a), Offset(b, b), Offset(c, c))
            4 -> listOf(Offset(a, a), Offset(c, a), Offset(a, c), Offset(c, c))
            5 -> listOf(Offset(a, a), Offset(c, a), Offset(b, b), Offset(a, c), Offset(c, c))
            else -> listOf(Offset(a, a), Offset(c, a), Offset(a, b), Offset(c, b), Offset(a, c), Offset(c, c))
        }
        pips.forEach { drawCircle(color = pip, radius = r, center = it) }
    }
}
