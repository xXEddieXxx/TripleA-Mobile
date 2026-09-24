package org.triplea.mobile.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.file.Files
import org.triplea.mobile.app.AppServices

/** One chapter of the guide: title, one line summary and a composable body with text and pictures. */
private class Chapter(val title: String, val summary: String, val body: @Composable ColumnScope.() -> Unit)

/** The nation whose icons illustrate the guide; every bundled map has this set. */
private const val ICON_NATION = "Germans"

private class UnitStat(val unit: String, val name: String, val attack: String, val defense: String, val move: String, val cost: String)

private val CLASSIC_UNITS = listOf(
    UnitStat("infantry", "Infantry", "1", "2", "1", "3"),
    UnitStat("artillery", "Artillery", "2", "2", "1", "4"),
    UnitStat("armour", "Armour", "3", "3", "2", "5"),
    UnitStat("aaGun", "AA gun", "–", "1 vs air", "1", "5"),
    UnitStat("fighter", "Fighter", "3", "4", "4", "10"),
    UnitStat("bomber", "Bomber", "4", "1", "6", "12"),
    UnitStat("transport", "Transport", "0", "0", "2", "7"),
    UnitStat("submarine", "Submarine", "2", "1", "2", "6"),
    UnitStat("destroyer", "Destroyer", "2", "2", "2", "8"),
    UnitStat("carrier", "Carrier", "1", "2", "2", "14"),
    UnitStat("battleship", "Battleship", "4", "4", "2", "20"),
    UnitStat("factory", "Factory", "–", "–", "0", "15"),
)

private val CHAPTERS = listOf(
    Chapter("The idea of the game", "Conquer territories, earn production, build an army.") {
        Paragraphs(
            """
            TripleA is a turn based strategy game in the style of Axis & Allies. Every nation takes its
            turn in a fixed order; one full cycle of all nations is a round.
            """,
        )
        FlagRow(listOf("Germans", "Russians", "Japanese", "British", "Americans"))
        Paragraphs(
            """
            Each land territory has a value in production units (PUs). At the end of your turn you collect
            the PUs of all territories you own. You spend them at the start of your next turn on new
            units, which are placed at your factories.
            """,
        )
        Illustration {
            TerritoryShape(color = Color(0xFFC7A25A), value = 3, modifier = Modifier.size(width = 84.dp, height = 56.dp))
            Spacer(Modifier.width(6.dp))
            Text("+", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(6.dp))
            TerritoryShape(color = Color(0xFFC7A25A), value = 2, modifier = Modifier.size(width = 70.dp, height = 56.dp))
            Spacer(Modifier.width(8.dp))
            Text("= 5 PUs", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text("→", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            UnitImage("factory", 40)
            Spacer(Modifier.width(4.dp))
            UnitImage("infantry", 40)
        }
        Caption("Own territories worth 3 and 2: five PUs per turn, enough for one infantry at the factory.")
        Paragraphs(
            """
            You win by holding the victory conditions of the map, usually a number of victory cities or
            the capitals of your enemies. The game announces the winner when the conditions are met.
            """,
        )
    },
    Chapter("A turn, phase by phase", "Purchase, combat move, battles, non-combat move, place, collect.") {
        PhaseStrip()
        Bullets(
            listOf(
                "Purchase: buy units with your PUs. They are not on the map yet; you place them at the end of the turn. The purchase screen can be hidden with the map button to look around first.",
                "Combat move: move units into enemy territories or sea zones to attack. Only moves that lead to a fight belong here. Every unit can move at most its movement value per turn.",
                "Battles: every territory with units of both sides is fought out. You choose the order of the battles; the engine rolls the dice.",
                "Non-combat move: move the units that did not fight, bring fighters and bombers home (aircraft that cannot land are lost) and reinforce your front line.",
                "Place units: put the units you bought on territories with a factory. A factory can place as many units as the territory's value, unless the map says otherwise.",
                "End turn: collect the PUs of your territories. The next nation is up.",
            ),
        )
        Paragraphs(
            """
            Some maps add a technology phase, a politics phase (declare war, make alliances) or special
            user actions. These only appear when the map uses them.
            """,
        )
    },
    Chapter("How combat works", "Dice, attack and defense values, casualties, retreat.") {
        Paragraphs(
            """
            Every unit has an attack value and a defense value. In a battle round each attacking unit rolls
            one die and hits when the roll is at or below its attack value; defenders do the same with
            their defense value.
            """,
        )
        DiceExample("Infantry attacks at 1: only a 1 is a hit", 1)
        DiceExample("Armour attacks at 3: 1, 2 and 3 hit", 3)
        DiceExample("Fighter defends at 4: 1 to 4 hit", 4)
        Caption("The classic values. Your map may differ; the purchase screen shows the exact numbers.")
        UnitTable()
        Paragraphs(
            """
            Hits become casualties: the owner chooses which units die, usually the cheapest ones first.
            The app suggests the engine's default choice; you can change it or accept it (there is a
            setting to always accept the defaults for faster battles).

            After each round the attacker may retreat to a territory the attackers came from. The battle
            ends when one side is destroyed or the attacker retreats. If the attacker wins with land units,
            the territory changes hands.
            """,
        )
        Illustration {
            UnitImage("artillery", 40)
            Text(" + ", style = MaterialTheme.typography.titleMedium)
            UnitImage("infantry", 40)
            Text("  infantry attacks at 2", style = MaterialTheme.typography.bodyMedium)
        }
        Illustration {
            UnitImage("battleship", 40)
            Text(" → ", style = MaterialTheme.typography.titleMedium)
            UnitImage("battleship_hit", 40)
            Text("  battleships take two hits", style = MaterialTheme.typography.bodyMedium)
        }
        Illustration {
            UnitImage("submarine", 40)
            Text("  strikes first, can submerge   ", style = MaterialTheme.typography.bodyMedium)
            UnitImage("destroyer", 40)
            Text("  cancels that", style = MaterialTheme.typography.bodyMedium)
        }
        Paragraphs(
            """
            Armour can blitz through empty enemy territories, anti-aircraft guns shoot at attacking
            aircraft before the battle, and bombers can bomb factories instead of fighting. The abilities
            of each unit are listed in the purchase screen.
            """,
        )
    },
    Chapter("Sea, air and transports", "Moving over water and the rules for aircraft.") {
        Illustration {
            UnitImage("transport", 44)
            Text("  carries  ", style = MaterialTheme.typography.bodyMedium)
            UnitImage("infantry", 36)
            UnitImage("infantry", 36)
            Text("  or  ", style = MaterialTheme.typography.bodyMedium)
            UnitImage("infantry", 36)
            UnitImage("armour", 36)
        }
        Paragraphs(
            """
            Land units cross water on transports. Move the transport into the sea zone next to the land
            units, move the units onto it, then move the transport and unload in a later turn or in the
            same combat move for an amphibious assault. A transport carries two infantry or one infantry
            plus one other land unit on most maps.
            """,
        )
        Illustration {
            UnitImage("carrier", 44)
            Text("  carries  ", style = MaterialTheme.typography.bodyMedium)
            UnitImage("fighter", 36)
            UnitImage("fighter", 36)
        }
        Paragraphs(
            """
            Aircraft carriers carry two fighters. Fighters and bombers have a long range but must be able
            to land at the end of the non-combat move on a friendly territory or carrier, otherwise they
            crash. Keep an eye on the movement left when you send them out.
            """,
        )
        Illustration {
            UnitImage("fighter", 40)
            Text("  4 moves: 2 out, 2 back home", style = MaterialTheme.typography.bodyMedium)
        }
        Paragraphs(
            """
            Sea zones can be attacked like territories. Submarines and destroyers have their own rules, and
            battleships and cruisers can bombard the coast during an amphibious assault.
            """,
        )
    },
    Chapter("Controls in this app", "Tapping, selecting units, moving, undoing.") {
        SelectionIllustration()
        Caption("Tap a unit icon once per unit you want to take; the counter shows 3 of 5 selected.")
        RouteIllustration()
        Caption("With units selected, tap the destination: the route is drawn and Move confirms it.")
        Bullets(
            listOf(
                "Drag to move the map, pinch to zoom. World maps scroll around endlessly sideways.",
                "Tap a unit icon to select one unit, tap again to take more; when all are taken the next tap clears the stack. Double-tap or long-press a territory for the unit menu with exact numbers.",
                "With units selected, tap the destination. Tap another territory to change it, or Clear to start over.",
                "Undo lists every move of the phase; each can be taken back on its own. Tapping a move shows its route on the map.",
                "Done ends the phase. You are asked first, because a finished phase cannot be reopened.",
                "The status line at the bottom shows the tapped territory, its owner and its value. The details panel (info symbol) shows the units there, statistics of all nations, the game history and, if the map has it, diplomacy.",
                "The battle window opens for every fight; Hide puts it away, the next battle opens it again.",
                "Save game, settings, game notes and this guide are in the menu at the top right. An autosave is written every round when your turn begins.",
            ),
        )
    },
    Chapter("Tips for your first games", "What experienced players do.") {
        Illustration {
            UnitImage("infantry", 40)
            UnitImage("infantry", 40)
            UnitImage("infantry", 40)
            UnitImage("artillery", 40)
            UnitImage("armour", 40)
            Text("  the classic buy", style = MaterialTheme.typography.bodyMedium)
        }
        Bullets(
            listOf(
                "Infantry is the cheapest way to hold territory; buy mostly infantry, add artillery and armour for the attack.",
                "Attack only with clearly superior forces. Two to one is comfortable, equal strength is a coin flip you will lose half the time.",
                "Do not leave your capital weakly defended; losing it means losing all your PUs.",
                "Every unit has to be in reach of a factory to be placed; plan where you need new units next turn.",
                "Aircraft are expensive but flexible: keep them where they can defend this turn and attack the next.",
                "Try the AI at Easy first, then Hard. Use the Undo list freely; nothing is final until Done.",
            ),
        )
    },
)

/** The "How to play" guide for new players. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How to play") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            item {
                Text(
                    "A short guide to the rules of TripleA and to the controls of this app. Tap a chapter to open it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            CHAPTERS.forEachIndexed { index, chapter ->
                item(key = index) { ChapterCard(chapter, initiallyOpen = index == 0) }
            }
        }
    }
}

/** The guide as a full screen dialog for use inside a running game. */
@Composable
fun HowToPlayDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { HowToPlayScreen(onBack = onClose) }
    }
}

@Composable
private fun ChapterCard(chapter: Chapter, initiallyOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    // only the heading opens and closes the chapter, so a tap into the text while reading
    // (or on an image) does not fold it away
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
            ) {
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(18.dp))
                Column {
                    Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                    Text(chapter.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (open) {
                Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) { chapter.body(this) }
            }
        }
    }
}

// ------------------------------------------------------------------ text helpers

@Composable
private fun Paragraphs(text: String) {
    text.trimIndent().split(Regex("\\n\\s*\\n")).forEach { paragraph ->
        Text(
            paragraph.lines().joinToString(" ") { it.trim() },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun Bullets(items: List<String>) {
    Column(Modifier.padding(vertical = 4.dp)) {
        items.forEach { bullet ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                Text(bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

/** A picture row on a soft background. */
@Composable
private fun Illustration(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).horizontalScroll(rememberScrollState()),
        ) { content() }
    }
}

// ------------------------------------------------------------------ images from the engine assets

@Composable
private fun rememberAssetBitmap(relativePath: String): Bitmap? = remember(relativePath) {
    runCatching {
        val file = AppServices.engineAssetsDir.resolve(relativePath)
        if (Files.exists(file)) BitmapFactory.decodeFile(file.toString()) else null
    }.getOrNull()
}

@Composable
private fun UnitImage(unit: String, size: Int) {
    val bitmap = rememberAssetBitmap("units/$ICON_NATION/$unit.png")
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = unit, modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit)
    } else {
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun FlagRow(nations: List<String>) {
    Illustration {
        nations.forEach { nation ->
            val bitmap = rememberAssetBitmap("flags/$nation.png")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 14.dp)) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = nation, modifier = Modifier.height(28.dp).clip(RoundedCornerShape(3.dp)))
                }
                Text(nation, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("… each in turn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ drawn illustrations

/** A territory blob with its PU value badge, as on the map. */
@Composable
private fun TerritoryShape(color: Color, value: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(size.width * 0.1f, size.height * 0.3f)
                lineTo(size.width * 0.45f, size.height * 0.05f)
                lineTo(size.width * 0.9f, size.height * 0.2f)
                lineTo(size.width * 0.95f, size.height * 0.7f)
                lineTo(size.width * 0.6f, size.height * 0.95f)
                lineTo(size.width * 0.15f, size.height * 0.8f)
                close()
            }
            drawPath(path, color)
            drawPath(path, Color(0x99000000), style = Stroke(width = 2f))
        }
        Surface(
            color = Color(0xAA141414),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(value.toString(), color = Color(0xFFFFE178), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
        }
    }
}

@Composable
private fun PhaseStrip() {
    val phases = listOf(
        Triple("Purchase", "factory", null),
        Triple("Combat move", "armour", "→"),
        Triple("Battle", null, "dice"),
        Triple("Non-combat", "fighter", "↩"),
        Triple("Place", "infantry", "▼"),
        Triple("Collect PUs", null, "PU"),
    )
    Illustration {
        phases.forEachIndexed { index, (label, unit, marker) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(74.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    when {
                        unit != null && marker == null -> UnitImage(unit, 40)
                        unit != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            UnitImage(unit, 30)
                            Text(marker!!, style = MaterialTheme.typography.titleMedium)
                        }
                        marker == "dice" -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { Die(3, true, 18); Die(5, false, 18) }
                        else -> Text(marker ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("${index + 1}. $label", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
            if (index < phases.size - 1) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiceExample(caption: String, hitsUpTo: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..6).forEach { Die(it, hit = it <= hitsUpTo, size = 22) }
        }
        Spacer(Modifier.width(10.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UnitTable() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(36.dp))
                Text("Unit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                listOf("Att", "Def", "Move", "Cost").forEach {
                    Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 3.dp))
            CLASSIC_UNITS.forEach { stat ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    UnitImage(stat.unit, 28)
                    Spacer(Modifier.width(8.dp))
                    Text(stat.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    listOf(stat.attack, stat.defense, stat.move, stat.cost).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
    }
}

/** A unit stack as drawn on the map, with the yellow selection frame and the "3/5" counter. */
@Composable
private fun SelectionIllustration() {
    val bitmap = rememberAssetBitmap("units/$ICON_NATION/infantry.png")
    Illustration {
        Box(Modifier.size(64.dp)) {
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFFFFD600), style = Stroke(width = 4f), topLeft = Offset(4f, 4f), size = Size(size.width - 8f, size.height - 8f))
            }
            Text(
                "3/5",
                color = Color(0xFFFFD600),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 2.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Tap: 1 selected", style = MaterialTheme.typography.bodySmall)
            Text("Tap again: 2, 3 …", style = MaterialTheme.typography.bodySmall)
            Text("All taken? next tap clears", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Two territories with the move arrow between them, as the map draws a planned move. */
@Composable
private fun RouteIllustration() {
    val bitmap = rememberAssetBitmap("units/$ICON_NATION/armour.png")
    Illustration {
        Box(Modifier.size(width = 250.dp, height = 90.dp)) {
            TerritoryShape(Color(0xFF9E9E9E), 2, Modifier.size(width = 96.dp, height = 70.dp).align(Alignment.CenterStart))
            TerritoryShape(Color(0xFFB86B4B), 3, Modifier.size(width = 96.dp, height = 70.dp).align(Alignment.CenterEnd))
            Canvas(Modifier.fillMaxSize()) {
                val start = Offset(size.width * 0.3f, size.height * 0.5f)
                val end = Offset(size.width * 0.72f, size.height * 0.5f)
                drawLine(Color.White, start, end, strokeWidth = 12f)
                drawLine(Color(0xFFDC281E), start, end, strokeWidth = 7f)
                val head = Path().apply {
                    moveTo(end.x + 10f, end.y)
                    lineTo(end.x - 16f, end.y - 14f)
                    lineTo(end.x - 16f, end.y + 14f)
                    close()
                }
                drawPath(head, Color.White, style = Stroke(width = 6f))
                drawPath(head, Color(0xFFDC281E))
                drawCircle(Color.White, radius = 9f, center = start)
                drawCircle(Color(0xFFDC281E), radius = 6f, center = start)
            }
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp).align(Alignment.CenterStart).padding(start = 6.dp))
            }
        }
    }
}
