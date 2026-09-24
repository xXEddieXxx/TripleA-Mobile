package org.triplea.mobile.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Unit
import games.strategy.engine.data.UnitType
import org.triplea.mobile.app.game.BattleState
import org.triplea.mobile.app.game.BattleStrengths
import org.triplea.mobile.app.game.CasualtyNoticeRequest
import org.triplea.mobile.app.game.SideStrength
import org.triplea.mobile.app.render.ImageCache

/**
 * The battle window as a front line: one column per strength value, attackers above, defenders
 * below, weakest on the left and strongest on the right, so units face the enemies they are
 * matched against. Dice appear next to the units they were fired at: the attacker's dice sit
 * right above the defenders, the defender's dice right below the attackers (grouped by the
 * strength they were rolled at), hits are red and come first. Text is kept to a minimum; the log
 * is for experts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BattleWindow(
    battle: BattleState,
    images: ImageCache?,
    onDismiss: () -> kotlin.Unit,
    modifier: Modifier = Modifier,
    notice: CasualtyNoticeRequest? = null,
    strengths: BattleStrengths? = null,
    colorOf: (String) -> Color? = { null },
) {
    var showLog by rememberSaveable { mutableStateOf(false) }
    val attackerColor = colorOf(battle.attacker) ?: MaterialTheme.colorScheme.primary
    val defenderColor = colorOf(battle.defender) ?: MaterialTheme.colorScheme.tertiary
    val attackGroups = remember(battle.attackingUnits, strengths) { groupByStrength(battle.attackingUnits, strengths?.attackers) }
    val defendGroups = remember(battle.defendingUnits, strengths) { groupByStrength(battle.defendingUnits, strengths?.defenders) }
    val strengthsPresent = remember(attackGroups, defendGroups, battle.lastDiceByStrength) {
        (attackGroups.keys + defendGroups.keys + battle.lastDiceByStrength.keys).filter { it >= 0 }.sorted()
    }
    val attackerRolled = battle.lastDiceSide == battle.attacker && battle.lastDice.isNotEmpty()
    val defenderRolled = battle.lastDiceSide == battle.defender && battle.lastDice.isNotEmpty()

    Card(
        modifier = modifier.widthIn(max = 500.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // ---- header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(battle.territory, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append("Round ").append(battle.round)
                            val index = battle.steps.indexOf(battle.currentStep)
                            if (index >= 0) append("  ·  ").append(index + 1).append("/").append(battle.steps.size)
                            if (!battle.ended && battle.currentStep.isNotBlank()) append("  ·  ").append(battle.currentStep)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (battle.ended) "Close" else "Hide") }
            }

            // everything between the header and the Continue button scrolls when the window
            // is taller than the screen allows, so the buttons never leave the screen
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // ---- attacker line
            SideLabel("attacks", battle.attacker, attackerColor, battle.attackingUnits.size, strengths?.attackers, images, attackerRolled, battle)

            // ---- the front line
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
            ) {
                if (strengthsPresent.isEmpty()) {
                    Text("—", modifier = Modifier.padding(8.dp))
                }
                strengthsPresent.forEach { strength ->
                    StrengthColumn(
                        strength = strength,
                        attackers = attackGroups[strength].orEmpty(),
                        defenders = defendGroups[strength].orEmpty(),
                        attackerDice = if (attackerRolled) battle.lastDiceByStrength[strength].orEmpty() else emptyList(),
                        defenderDice = if (defenderRolled) battle.lastDiceByStrength[strength].orEmpty() else emptyList(),
                        attackerColor = attackerColor,
                        defenderColor = defenderColor,
                        images = images,
                    )
                }
            }

            // ---- defender line
            SideLabel("defends", battle.defender, defenderColor, battle.defendingUnits.size, strengths?.defenders, images, defenderRolled, battle)

            // ---- losses of the last round
            if (battle.lastCasualtyPlayer.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Box(Modifier.size(10.dp).background(colorOf(battle.lastCasualtyPlayer) ?: MaterialTheme.colorScheme.error, MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (battle.lastCasualties.isEmpty()) "${battle.lastCasualtyPlayer}: no losses" else "${battle.lastCasualtyPlayer} −${battle.lastCasualties.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        battle.lastCasualties.groupBy { it.type to it.owner }.forEach { (key, list) ->
                            Box(Modifier.alpha(0.55f)) {
                                UnitIcon(images, key.first, key.second, size = 24)
                                if (list.size > 1) {
                                    Text("×${list.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd))
                                }
                            }
                        }
                    }
                }
            }

            // ---- result
            if (battle.ended) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(battle.endMessage.ifBlank { "Battle over" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }

            // ---- log for experts
            if (battle.log.isNotEmpty()) {
                Text(
                    if (showLog) "▾ log" else "▸ log (${battle.log.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).clickable { showLog = !showLog },
                )
                if (showLog) {
                    Column(Modifier.heightIn(max = 160.dp)) {
                        battle.log.takeLast(12).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            }

            // ---- waiting for the player (kept in view: the rest above scrolls when the screen is small)
            if (notice != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice.message.ifBlank { "Casualties taken" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { notice.complete(true) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Continue") }
                    }
                }
            }

        }
    }
}

/** Name, flag, color, unit count and power of one side; "rolled n hits" while its dice are shown. */
@Composable
private fun SideLabel(
    verb: String,
    player: String,
    color: Color,
    unitCount: Int,
    side: SideStrength?,
    images: ImageCache?,
    rolled: Boolean,
    battle: BattleState,
) {
    val flag = remember(player, images) { images?.getNow("flags/$player.png", listOf("flags/$player.png", "flags/${player}_small.png")) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(4.dp).height(18.dp).background(color, MaterialTheme.shapes.extraSmall))
        Spacer(Modifier.width(6.dp))
        if (flag != null) {
            Image(flag.asImageBitmap(), contentDescription = player, modifier = Modifier.height(14.dp).widthIn(max = 24.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(player, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Text(verb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (rolled) {
            Text(
                "${battle.lastDiceHits} hit(s)",
                style = MaterialTheme.typography.labelMedium,
                color = if (battle.lastDiceHits > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (side != null && unitCount > 0) {
            Text(
                "$unitCount · power ${side.totalPower}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * One strength value: attackers of that strength above, the dice fired at them (the defender's
 * roll), the strength, the dice fired at the defenders (the attacker's roll), defenders below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrengthColumn(
    strength: Int,
    attackers: List<UnitGroup>,
    defenders: List<UnitGroup>,
    attackerDice: List<Pair<Int, Boolean>>,
    defenderDice: List<Pair<Int, Boolean>>,
    attackerColor: Color,
    defenderColor: Color,
    images: ImageCache?,
) {
    val width = (maxOf(attackers.size, defenders.size, 1) * 40 + 16).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
        // attackers
        Surface(color = attackerColor.copy(alpha = if (attackers.isEmpty()) 0.04f else 0.14f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (attackers.isEmpty()) Spacer(Modifier.size(32.dp))
                attackers.forEach { UnitTile(it, images) }
            }
        }
        // the defender's dice hit the attackers, so they sit right below the attackers
        DiceRow(defenderDice)
        // the strength in the middle: what a die must roll at or below
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.padding(vertical = 2.dp)) {
            Text("$strength", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
        }
        // the attacker's dice hit the defenders, so they sit right above the defenders
        DiceRow(attackerDice)
        // defenders
        Surface(color = defenderColor.copy(alpha = if (defenders.isEmpty()) 0.04f else 0.14f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (defenders.isEmpty()) Spacer(Modifier.size(32.dp))
                defenders.forEach { UnitTile(it, images) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiceRow(dice: List<Pair<Int, Boolean>>) {
    Box(Modifier.heightIn(min = 18.dp).padding(vertical = 1.dp), contentAlignment = Alignment.Center) {
        if (dice.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp), maxItemsInEachRow = 6) {
                // hits first, so the red dice are the ones next to the units they hit
                dice.sortedByDescending { it.second }.forEach { (value, hit) -> Die(value, hit, size = 15) }
            }
        }
    }
}

/** An icon with the count in the corner; several dice per unit are shown as "2×". */
@Composable
private fun UnitTile(group: UnitGroup, images: ImageCache?) {
    Box(Modifier.padding(horizontal = 2.dp)) {
        UnitIcon(images, group.type, group.owner, size = 34)
        if (group.count > 1) {
            Surface(color = Color(0xE6202020), shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("×${group.count}", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
        if (group.rolls > 1) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.align(Alignment.TopEnd)) {
                Text("${group.rolls}×", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
    }
}

private data class UnitGroup(val type: UnitType, val owner: GamePlayer, val strength: Int, val rolls: Int, val count: Int)

/** Groups units by strength, then by type; strength -1 when unknown. */
private fun groupByStrength(units: List<Unit>, side: SideStrength?): Map<Int, List<UnitGroup>> =
    units.groupBy { unit -> Triple(unit.type, unit.owner, (side?.strength?.get(unit) ?: -1) to (side?.rolls?.get(unit) ?: 1)) }
        .map { (key, list) -> UnitGroup(key.first, key.second, key.third.first, key.third.second, list.size) }
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

