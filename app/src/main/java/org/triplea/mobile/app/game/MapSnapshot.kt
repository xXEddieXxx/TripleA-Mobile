package org.triplea.mobile.app.game

import android.graphics.Path
import games.strategy.engine.data.GameData
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.delegate.Die
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.data.PlacementDescription
import games.strategy.engine.history.Event
import games.strategy.engine.history.EventChild
import games.strategy.engine.history.Round
import games.strategy.engine.history.Step
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.attachments.AbstractConditionsAttachment
import games.strategy.triplea.attachments.AbstractPlayerRulesAttachment
import games.strategy.triplea.attachments.ICondition
import games.strategy.triplea.attachments.RulesAttachment
import games.strategy.triplea.attachments.TerritoryAttachment
import games.strategy.triplea.attachments.TriggerAttachment
import games.strategy.triplea.Constants
import games.strategy.triplea.Properties
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge
import games.strategy.engine.data.GamePlayer
import org.triplea.java.collections.IntegerMap
import games.strategy.triplea.attachments.TerritoryEffectAttachment
import games.strategy.engine.data.TerritoryEffect
import games.strategy.triplea.delegate.TerritoryEffectHelper
import games.strategy.triplea.ui.mapdata.MapData
import games.strategy.triplea.delegate.TechTracker
import games.strategy.triplea.util.TuvCostsCalculator
import games.strategy.triplea.util.TuvUtils
import games.strategy.triplea.util.UnitSeparator
import org.triplea.geom.Polygon
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.UnitImageNames
import org.triplea.util.FileNameUtils
import java.lang.ref.SoftReference

/** A group of identical units drawn as one icon with a counter. */
class UnitStack(
    val territoryName: String,
    val typeName: String,
    val ownerName: String,
    val count: Int,
    val x: Int,
    val y: Int,
    val imagePaths: List<String>,
    val damagedCount: Int,
    /** The engine units drawn as this stack, in drawing order. */
    val units: List<Unit>,
)

/**
 * A territory effect (weather, terrain): its map point for the marker, its icon, and a short
 * summary of what it does to units ("defence +1: infantry · no blitz: tank").
 */
class EffectMarker(val name: String, val x: Int, val y: Int, val imagePaths: List<String>, val summary: String)

class TerritorySnapshot(
    val name: String,
    val paths: List<Path>,
    val bounds: android.graphics.RectF,
    val isWater: Boolean,
    val ownerName: String,
    val fillColor: Int?,
    val centerX: Int,
    val centerY: Int,
    val stacks: List<UnitStack>,
    /** PU production of the territory (0 for water and worthless land). */
    val production: Int,
    val isVictoryCity: Boolean,
    /** Where the map wants the name drawn (top left of the text), if the map says so. */
    val nameX: Int?,
    val nameY: Int?,
    /** Where the map wants the production value drawn, if the map says so. */
    val puX: Int?,
    val puY: Int?,
    val drawName: Boolean,
    val effects: List<EffectMarker> = emptyList(),
)

/** A national objective of one nation: what it asks for, what it pays, and whether it is met now. */
class ObjectiveLine(val player: String, val title: String, val description: String, val value: Int, val achieved: Boolean)

/** A unit type of one owner in a history entry: a sample unit for the icon and how many. */
class UnitRef(val sample: Unit, val count: Int)

/** What a history event is about; decides its icon and the history filter it belongs to. */
enum class HistoryKind { BATTLE, MOVE, PURCHASE, PLACE, OTHER }

/**
 * One detail line of a history event. A dice roll carries its dice (value and whether it hit),
 * a casualty or retreat line the units involved, so the history can show them as icons.
 */
class HistoryDetail(
    val text: String,
    val dice: List<Pair<Int, Boolean>> = emptyList(),
    val units: List<UnitRef> = emptyList(),
)

/** One event of the game history (a move, a purchase, a battle...) with its detail lines. */
class HistoryEvent(
    val text: String,
    val details: List<HistoryDetail>,
    val kind: HistoryKind = HistoryKind.OTHER,
    /** The units the event is about (moved, bought, placed), for icons. */
    val units: List<UnitRef> = emptyList(),
    /** The territory of a battle, if the event is one. */
    val territory: String? = null,
    /** The territories of a move (start to end) or the one of a placement, for a short headline. */
    val route: List<String> = emptyList(),
    /** The node's child indices from the history root (round, step, event): its address for `HistoryView.gotoNode`. */
    val path: IntArray = IntArray(0),
) {
    /** Dice rolled in this event (battles), attacker and defender alike. */
    val diceCount: Int get() = details.sumOf { it.dice.size }
    val hitCount: Int get() = details.sumOf { d -> d.dice.count { it.second } }
}

/** The events of one step (e.g. "Germans Combat Move"), in play order. */
class HistoryBlock(val round: Int, val title: String, val player: String, val events: List<HistoryEvent>)

/** The relationship between two nations, e.g. Germans - Russians: War. */
class RelationshipLine(val player1: String, val player2: String, val type: String, val war: Boolean, val allied: Boolean)

/** One row of the statistics table: the desktop client's "Stats" tab. */
class PlayerStats(
    val name: String,
    val resources: String,
    val pus: Int,
    val production: Int,
    val territories: Int,
    val units: Int,
    val tuv: Int,
    val victoryCities: Int,
    /** The technologies the nation has researched, in the map's order. */
    val technologies: List<String> = emptyList(),
)

/** Immutable rendering data derived from the game state, rebuilt whenever the game data changes. */
class MapSnapshot(
    val version: Int,
    val territories: List<TerritorySnapshot>,
    /** Territories where hostile units met and a battle is pending. */
    val battleSites: Set<String>,
    val stats: List<PlayerStats>,
    val hasVictoryCities: Boolean,
    /** The most recent steps of the game history, in play order. */
    val history: List<HistoryBlock>,
    /** Every pair of nations; empty when the map's relations never change (no politics, no relationship triggers). */
    val relationships: List<RelationshipLine>,
    /** Whether any nation has political actions (a diplomacy phase) in this game. */
    val hasPolitics: Boolean,
    /** Whether any nation has user actions (map specific special actions). */
    val hasUserActions: Boolean,
    /** Whether the map wants its territory effects drawn as markers on the map (map.properties). */
    val showEffectMarkers: Boolean = false,
    /** The national objectives of every nation with their current state; empty when the map has none. */
    val objectives: List<ObjectiveLine> = emptyList(),
) {
    val byName: Map<String, TerritorySnapshot> = territories.associateBy { it.name }

    /**
     * A land territory smaller than [maxSize] (a tiny island) within [reach] of a map point, for
     * taps that land in the water right next to it. The closest one wins; null when none qualifies.
     */
    fun tinyLandNear(mapX: Float, mapY: Float, maxSize: Float, reach: Float): TerritorySnapshot? {
        var best: TerritorySnapshot? = null
        var bestDistance = Float.MAX_VALUE
        for (territory in territories) {
            if (territory.isWater) continue
            val b = territory.bounds
            if (b.width() > maxSize || b.height() > maxSize) continue
            if (mapX < b.left - reach || mapX > b.right + reach || mapY < b.top - reach || mapY > b.bottom + reach) continue
            val dx = mapX - b.centerX()
            val dy = mapY - b.centerY()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = territory
            }
        }
        return best
    }

    /**
     * Finds the unit stack drawn at a map coordinate. [unitWidth] is the drawn icon size and
     * [slack] the extra margin (in map units) accepted around it so small icons stay tappable. When
     * icons overlap the topmost (last drawn) stack wins.
     */
    fun stackAt(mapX: Float, mapY: Float, unitWidth: Float, slack: Float): UnitStack? {
        var best: UnitStack? = null
        var bestDistance = Float.MAX_VALUE
        val reach = unitWidth * 4 + slack
        for (territory in territories) {
            if (territory.stacks.isEmpty()) continue
            val b = territory.bounds
            if (mapX < b.left - reach || mapX > b.right + reach || mapY < b.top - reach || mapY > b.bottom + reach) continue
            for (stack in territory.stacks) {
                val left = stack.x - slack
                val top = stack.y - slack
                val right = stack.x + unitWidth + slack
                val bottom = stack.y + unitWidth + slack
                if (mapX < left || mapX > right || mapY < top || mapY > bottom) continue
                val cx = stack.x + unitWidth / 2f
                val cy = stack.y + unitWidth / 2f
                val distance = (mapX - cx) * (mapX - cx) + (mapY - cy) * (mapY - cy)
                // later stacks are drawn on top, so prefer them on (near) ties
                if (distance <= bestDistance + unitWidth * unitWidth * 0.25f) {
                    best = stack
                    bestDistance = distance
                }
            }
        }
        return best
    }

    companion object {
        private val pathCache = HashMap<String, List<Path>>()
        private val boundsCache = HashMap<String, android.graphics.RectF>()

        /**
         * Builds a snapshot under the game data read lock. [gameData] defaults to the live game; a
         * `HistoryView` clone gives the map as it was at a point of the history (its history list and
         * objectives are left out: the live ones apply, and the objective cache is bound to the session).
         */
        fun build(session: LocalGameSession, version: Int, gameData: GameData = session.gameData): MapSnapshot {
            val live = gameData === session.gameData
            val mapData: MapData = session.mapData
            val unitWidth = (mapData.defaultUnitWidth * mapData.defaultUnitScale).toInt().coerceAtLeast(8)
            val territories = ArrayList<TerritorySnapshot>()
            var battleSites: Set<String> = emptySet()
            val stats = ArrayList<PlayerStats>()
            var hasVictoryCities = false
            val drawNames = mapData.drawTerritoryNames()
            val useEffectMarkers = runCatching { mapData.useTerritoryEffectMarkers() }.getOrDefault(false)
            gameData.acquireReadLock().use {
                battleSites = runCatching {
                    val tracker = gameData.battleDelegate.battleTracker
                    (tracker.pendingBattleSitesWithoutBombing + tracker.pendingBattleSitesWithBombing).map { it.name }.toSet()
                }.getOrDefault(emptySet())
                for (territory in gameData.map.territories) {
                    val name = territory.name
                    val polygons = mapData.getPolygons(name) ?: continue
                    val key = "${mapData.hashCode()}:$name"
                    val paths = pathCache.getOrPut(key) { polygons.map { toPath(it) } }
                    val bounds = boundsCache.getOrPut(key) {
                        val b = mapData.getBoundingRect(name)
                        android.graphics.RectF(
                            b.x.toFloat(),
                            b.y.toFloat(),
                            (b.x + b.width).toFloat(),
                            (b.y + b.height).toFloat(),
                        )
                    }
                    val owner = territory.owner
                    val impassable = TerritoryAttachment.get(territory).map { it.isImpassable }.orElse(false)
                    val fill: Int? = when {
                        territory.isWater -> null
                        impassable -> mapData.impassableColor().rgb
                        else -> mapData.getPlayerColor(owner.name).rgb
                    }
                    val center = mapData.getCenter(name)
                    val production = if (territory.isWater) 0 else TerritoryAttachment.getProduction(territory)
                    val victoryCity = TerritoryAttachment.get(territory).map { it.victoryCity }.orElse(0) > 0
                    if (victoryCity) hasVictoryCities = true
                    val namePoint = mapData.getNamePlacementPoint(territory).orElse(null)
                    val puPoint = mapData.getPuPlacementPoint(territory).orElse(null)

                    val stacks = ArrayList<UnitStack>()
                    val categories = UnitSeparator.getSortedUnitCategories(territory, mapData)
                    if (categories.isNotEmpty()) {
                        val points = runCatching { mapData.getPlacementPoints(territory) }.getOrNull()?.iterator()
                        var lastX = -1
                        var lastY = -1
                        val overflowLeft = runCatching { mapData.getPlacementOverflowToLeft(territory) }.getOrDefault(false)
                        for (category in categories) {
                            if (points != null && points.hasNext()) {
                                val p = points.next()
                                lastX = p.x
                                lastY = p.y
                            } else if (lastX < 0) {
                                lastX = center.x - unitWidth / 2
                                lastY = center.y - unitWidth / 2
                            } else {
                                lastX += if (overflowLeft) -unitWidth else unitWidth
                            }
                            val imagePaths = UnitImageNames.candidatePaths(
                                category.type,
                                category.owner,
                                category.hasDamageOrBombingUnitDamage(),
                                category.disabled,
                            )
                            stacks += UnitStack(
                                territoryName = name,
                                typeName = category.type.name,
                                ownerName = category.owner.name,
                                count = category.units.size,
                                x = lastX,
                                y = lastY,
                                imagePaths = imagePaths,
                                damagedCount = category.damaged,
                                units = category.units.toList(),
                            )
                        }
                    }
                    // territory effects (weather, terrain): the map's markers at its effect points
                    val effects: List<EffectMarker> = runCatching {
                        val found = TerritoryEffectHelper.getEffects(territory).toList()
                        if (found.isEmpty()) emptyList() else {
                            val points = mapData.getTerritoryEffectPoints(territory)
                            found.mapIndexed { index, effect ->
                                val point = points.getOrNull(index) ?: points.last()
                                val shift = if (index < points.size) 0 else (index - points.size + 1) * 24
                                EffectMarker(
                                    name = effect.name,
                                    x = point.x + shift,
                                    y = point.y,
                                    imagePaths = listOf("territoryEffects/${effect.name}_large.png", "territoryEffects/${effect.name}.png"),
                                    summary = effectSummary(gameData, effect),
                                )
                            }
                        }
                    }.getOrDefault(emptyList())
                    territories += TerritorySnapshot(
                        name = name,
                        paths = paths,
                        bounds = bounds,
                        isWater = territory.isWater,
                        ownerName = owner.name,
                        fillColor = fill,
                        centerX = center.x,
                        centerY = center.y,
                        stacks = stacks,
                        production = production,
                        isVictoryCity = victoryCity,
                        nameX = namePoint?.x,
                        nameY = namePoint?.y,
                        puX = puPoint?.x,
                        puY = puPoint?.y,
                        drawName = drawNames && mapData.shouldDrawTerritoryName(name),
                        effects = effects,
                    )
                }
                stats += computeStats(gameData)
            }
            val history = if (live) gameData.acquireReadLock().use { readHistory(gameData) } else emptyList()
            var relationships: List<RelationshipLine> = emptyList()
            var hasPolitics = false
            var hasUserActions = false
            gameData.acquireReadLock().use {
                runCatching {
                    val players = gameData.playerList.players.filter { !it.isNull }
                    val tracker = gameData.relationshipTracker
                    val lines = ArrayList<RelationshipLine>()
                    for (i in players.indices) {
                        for (j in i + 1 until players.size) {
                            val type = tracker.getRelationshipType(players[i], players[j])
                            val attachment = type.relationshipTypeAttachment
                            lines += RelationshipLine(players[i].name, players[j].name, type.name, attachment.isWar, attachment.isAllied)
                        }
                    }
                    hasPolitics = players.any { PoliticalActionAttachment.getPoliticalActionAttachments(it).isNotEmpty() }
                    // relations matter when they can change: political actions or triggers with a relationshipChange
                    val changing = hasPolitics ||
                        TriggerAttachment.collectForAllTriggersMatching(players.toSet(), TriggerAttachment.relationshipChangeMatch()).isNotEmpty()
                    if (changing) relationships = lines
                    hasUserActions = players.any { UserActionAttachment.getUserActionAttachments(it).isNotEmpty() }
                }
            }
            val objectives = if (live) gameData.acquireReadLock().use { readObjectives(session) } else emptyList()
            return MapSnapshot(version, territories, battleSites, stats, hasVictoryCities, history, relationships, hasPolitics, hasUserActions, useEffectMarkers, objectives)
        }

        private const val MAX_HISTORY_BLOCKS = 300

        /**
         * The national objectives of every nation, tested against the current game state with the
         * engine's own condition code (the same the end turn phase uses to pay them out). The text
         * comes from the map's objectives.properties when it has one, else from the rule itself.
         */
        private fun readObjectives(session: LocalGameSession): List<ObjectiveLine> = runCatching {
            val data = session.gameData
            if (!Properties.getNationalObjectives(data.properties)) return@runCatching emptyList()
            // the file and the conditions it names do not change during a game: parse once per session
            val parsed = objectiveCache?.get()?.takeIf { it.session === session }
                ?: loadObjectiveTexts(session).let { ParsedObjectives(session, it, listedObjectives(data, it)) }
                    .also { objectiveCache = SoftReference(it) }
            val bridge = ObjectiveDummyDelegateBridge(data)
            if (parsed.listed.isNotEmpty()) testObjectives(parsed, bridge) else ruleObjectives(data, parsed.texts, bridge)
        }.getOrDefault(emptyList())

        /** An objectives.properties entry: the nation whose group lists it, the attachment's owner, name and text. */
        private class Listed(val player: String, val owner: String, val name: String, val condition: ICondition, val text: String)

        /** objectives.properties of one game, parsed once; `all` is the condition graph the listed ones depend on. */
        private class ParsedObjectives(val session: LocalGameSession, val texts: Map<String, String>, val listed: List<Listed>) {
            val all: Set<ICondition> = AbstractConditionsAttachment.getAllConditionsRecursive(listed.mapTo(HashSet()) { it.condition }, null)
        }

        // soft, so a quit game's data does not stay pinned when memory is needed
        private var objectiveCache: SoftReference<ParsedObjectives>? = null

        /**
         * The objectives the map lists in objectives.properties for this game, like the desktop
         * objective panel: "Game.TABLEGROUP.01;Italians=nameA;nameB" fixes the order per nation,
         * "Game.Italians;nameA=text" gives the text. A name may be a rules, condition or trigger
         * attachment, so missions without a PU value (a free unit, a tech) are included too.
         */
        private fun listedObjectives(data: GameData, texts: Map<String, String>): List<Listed> {
            val prefix = FileNameUtils.replaceIllegalCharacters(data.gameName, '_').replace(" ", "_") + "."
            val entries = texts.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            val (groupKeys, textKeys) = entries.keys.filter { ';' in it }.partition { it.startsWith("TABLEGROUP.") }
            val listed = ArrayList<Listed>()
            for (groupKey in groupKeys.sorted()) {
                val player = groupKey.substringAfter(';')
                for (name in entries.getValue(groupKey).split(';')) {
                    // the text entry is keyed by the owner of the attachment, which may be another nation
                    val keys = listOf("$player;$name").filter { it in entries }.ifEmpty { textKeys.filter { it.endsWith(";$name") } }
                    for (entry in keys) {
                        val owner = entry.substringBefore(';')
                        val condition = AbstractPlayerRulesAttachment.getCondition(owner, name, data) ?: continue
                        listed += Listed(player, owner, name, condition, entries.getValue(entry))
                    }
                }
            }
            return listed
        }

        /** The listed objectives tested against the current game state. */
        private fun testObjectives(parsed: ParsedObjectives, bridge: ObjectiveDummyDelegateBridge): List<ObjectiveLine> {
            val tested = runCatching { AbstractConditionsAttachment.testAllConditionsRecursive(parsed.all, null, bridge) }
                .getOrDefault(emptyMap())
            return parsed.listed.map { line ->
                val shortName = line.name.removePrefix(Constants.RULES_OBJECTIVE_PREFIX).removePrefix(Constants.RULES_CONDITION_PREFIX)
                    .removePrefix(Constants.TRIGGER_ATTACHMENT_PREFIX).removePrefix(line.owner).trimStart('_', '-', ' ')
                ObjectiveLine(
                    player = line.player,
                    title = humanize(shortName),
                    description = line.text,
                    value = (line.condition as? RulesAttachment)?.objectiveValue ?: 0,
                    achieved = tested[line.condition] ?: false,
                )
            }
        }

        /** Fallback for maps without a desktop-style objectives.properties: every objectiveAttachment rule. */
        private fun ruleObjectives(data: GameData, texts: Map<String, String>, bridge: ObjectiveDummyDelegateBridge): List<ObjectiveLine> {
            val lines = ArrayList<ObjectiveLine>()
            for (player in data.playerList.players.filter { !it.isNull }) {
                val objectives = RulesAttachment.getNationalObjectives(player).sortedBy { it.name }
                if (objectives.isEmpty()) continue
                val tested = runCatching {
                    AbstractConditionsAttachment.testAllConditionsRecursive(HashSet<ICondition>(objectives), null, bridge)
                }.getOrDefault(emptyMap())
                for (objective in objectives) {
                    val fullName = objective.name ?: continue
                    val shortName = fullName.removePrefix(Constants.RULES_OBJECTIVE_PREFIX).trimStart('_', '-', ' ')
                    val value = objective.objectiveValue
                    val text = objectiveText(texts, player.name, fullName, shortName) ?: describeObjective(objective)
                    lines += ObjectiveLine(
                        player = player.name,
                        title = humanize(shortName),
                        description = text,
                        value = value,
                        achieved = tested[objective] ?: false,
                    )
                }
            }
            return lines
        }

        /** objectives.properties of the map: "Player.objectiveName=text" lines, HTML stripped. */
        private fun loadObjectiveTexts(session: LocalGameSession): Map<String, String> = runCatching {
            val file = session.resourceLoader.optionalResource("objectives.properties").orElse(null) ?: return@runCatching emptyMap()
            val props = java.util.Properties()
            java.io.InputStreamReader(java.nio.file.Files.newInputStream(file), Charsets.UTF_8).use { props.load(it) }
            props.entries.associate { (k, v) -> k.toString().trim() to GameController.stripHtml(v.toString()).replace(Regex("\\s+"), " ").trim() }
        }.getOrDefault(emptyMap())

        private fun objectiveText(texts: Map<String, String>, player: String, name: String, shortName: String): String? {
            if (texts.isEmpty()) return null
            val candidates = listOf("$player.$name", "$player.$shortName", name, shortName)
            candidates.firstNotNullOfOrNull { texts[it] }?.let { return it }
            // maps are not consistent about the key: accept anything that ends with the objective's name
            return texts.entries.firstOrNull { (k, _) -> k.endsWith(".$name") || k.endsWith(".$shortName") }?.value
        }

        /** "GermansControlCaucasus_2" -> "Germans Control Caucasus 2". */
        private fun humanize(name: String): String =
            name.replace('_', ' ').replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("\\s+"), " ").trim()

        /** What a rule asks for, read from its properties, when the map ships no text for it. */
        private fun describeObjective(rule: RulesAttachment): String {
            fun names(property: String): List<String> =
                (rule.getPropertyOrEmpty(property).orElse(null)?.value as? Array<*>)?.map { it.toString() }.orEmpty()
            val count = (rule.getPropertyOrEmpty("territoryCount").orElse(null)?.value as? Int) ?: -1
            val parts = ArrayList<String>()
            fun add(label: String, list: List<String>) {
                if (list.isEmpty()) return
                val need = if (count > 0 && count < list.size) "$count of " else ""
                parts += "$label $need${list.joinToString(", ")}"
            }
            add("own", names("directOwnershipTerritories"))
            add("allies own", names("alliedOwnershipTerritories"))
            add("units in", names("directPresenceTerritories"))
            add("allied units in", names("alliedPresenceTerritories"))
            add("enemy units in", names("enemyPresenceTerritories"))
            add("no own units in", names("directExclusionTerritories"))
            add("no allied units in", names("alliedExclusionTerritories"))
            add("no enemy units in", names("enemyExclusionTerritories"))
            (rule.getPropertyOrEmpty("atWarPlayers").orElse(null)?.value as? Collection<*>)?.takeIf { it.isNotEmpty() }?.let { players ->
                parts += "at war with " + players.joinToString(", ") { (it as? GamePlayer)?.name ?: it.toString() }
            }
            @Suppress("UNCHECKED_CAST")
            (rule.getPropertyOrEmpty("unitPresence").orElse(null)?.value as? IntegerMap<Any>)?.let { map ->
                val units = map.keySet().joinToString(", ") { "${map.getInt(it)} $it" }
                if (units.isNotBlank()) parts += "units: $units"
            }
            val invert = (rule.getPropertyOrEmpty("invert").orElse(null)?.value as? Boolean) ?: false
            val text = parts.joinToString("  ·  ")
            return if (invert && text.isNotBlank()) "not: $text" else text
        }

        /** What a territory effect does, in a few words: "attack +1: infantry · no blitz: tank". */
        private fun effectSummary(gameData: GameData, effect: TerritoryEffect): String = runCatching {
            val attachment = TerritoryEffectAttachment.get(effect)
            val types = gameData.unitTypeList.allUnitTypes.sortedBy { it.name }
            val parts = ArrayList<String>()
            fun combat(defending: Boolean, label: String) {
                types.groupBy { attachment.getCombatEffect(it, defending) }
                    .filterKeys { it != 0 }
                    .toSortedMap(compareByDescending { it })
                    .forEach { (bonus, units) ->
                        parts += "$label ${if (bonus > 0) "+" else ""}$bonus: " + units.joinToString(", ") { it.name }
                    }
            }
            combat(false, "attack")
            combat(true, "defence")
            attachment.movementCostModifier.entries.groupBy { it.value }.forEach { (cost, entries) ->
                parts += "move cost ${if (cost.signum() > 0) "+" else ""}$cost: " + entries.joinToString(", ") { it.key.name }
            }
            if (attachment.noBlitz.isNotEmpty()) parts += "no blitz: " + attachment.noBlitz.joinToString(", ") { it.name }
            if (attachment.unitsNotAllowed.isNotEmpty()) parts += "not allowed: " + attachment.unitsNotAllowed.joinToString(", ") { it.name }
            parts.joinToString("  ·  ")
        }.getOrDefault("")

        /**
         * Flattens the engine's history tree (Round > Step > Event > detail) into step blocks.
         * The rendering data the engine attaches to nodes (dice rolls, unit lists, the battle
         * territory, move and placement descriptions) is kept so the history can show icons and dice.
         */
        private fun readHistory(gameData: GameData): List<HistoryBlock> = runCatching {
            val blocks = ArrayList<HistoryBlock>()
            val root = gameData.history.root
            for ((roundIndex, roundNode) in root.childList().withIndex()) {
                val roundNo = (roundNode as? Round)?.roundNo ?: 0
                for ((stepIndex, stepNode) in roundNode.childList().withIndex()) {
                    val step = stepNode as? Step ?: continue
                    val stepName = runCatching { step.stepName }.getOrNull().orEmpty()
                    val stepKind = when {
                        stepName.contains("battle", true) -> HistoryKind.BATTLE
                        stepName.contains("purchase", true) || stepName.contains("bid", true) -> HistoryKind.PURCHASE
                        stepName.contains("place", true) -> HistoryKind.PLACE
                        stepName.contains("move", true) -> HistoryKind.MOVE
                        else -> HistoryKind.OTHER
                    }
                    val events = ArrayList<HistoryEvent>()
                    for ((eventIndex, eventNode) in step.childList().withIndex()) {
                        val event = eventNode as? Event ?: continue
                        val details = event.childList().mapNotNull { child ->
                            val eventChild = child as? EventChild ?: return@mapNotNull null
                            val data = eventChild.renderingData
                            HistoryDetail(
                                text = eventChild.title,
                                dice = (data as? DiceRoll)?.let { roll ->
                                    roll.rolls.map { die -> die.value + 1 to (die.type == Die.DieType.HIT) }
                                }.orEmpty(),
                                units = unitRefs(data),
                            )
                        }
                        val data = event.renderingData
                        val title = event.title
                        val kind = when {
                            data is Territory || title.startsWith("Battle in") || title.contains("bombing raid", true) || title.startsWith("Air Battle") -> HistoryKind.BATTLE
                            data is PlacementDescription -> HistoryKind.PLACE
                            data is MoveDescription -> HistoryKind.MOVE
                            else -> stepKind
                        }
                        events += HistoryEvent(
                            text = title,
                            details = details,
                            kind = kind,
                            units = unitRefs(data),
                            territory = (data as? Territory)?.name,
                            route = when (data) {
                                is MoveDescription -> runCatching { data.route.allTerritories.map { it.name } }.getOrDefault(emptyList())
                                is PlacementDescription -> runCatching { listOf(data.territory.name) }.getOrDefault(emptyList())
                                else -> emptyList()
                            },
                            path = intArrayOf(roundIndex, stepIndex, eventIndex),
                        )
                    }
                    if (events.isEmpty()) continue
                    blocks += HistoryBlock(roundNo, step.title, step.playerId.map { it.name }.orElse(""), events)
                }
            }
            blocks.takeLast(MAX_HISTORY_BLOCKS)
        }.getOrDefault(emptyList())

        /** The units in a history node's rendering data, grouped by type and owner. */
        private fun unitRefs(data: Any?): List<UnitRef> {
            val units: Collection<*> = when (data) {
                is MoveDescription -> data.units
                is PlacementDescription -> data.units
                is Unit -> listOf(data)
                is Collection<*> -> data
                else -> return emptyList()
            }
            return units.filterIsInstance<Unit>()
                .groupBy { it.type.name to it.owner.name }
                .map { (_, group) -> UnitRef(group.first(), group.size) }
        }

        /** Per player: resources, production, territories, units, total unit value and victory cities. */
        private fun computeStats(gameData: GameData): List<PlayerStats> = runCatching {
            val tuvCalculator = TuvCostsCalculator()
            val players = gameData.playerList.players.filter { !it.isNull }
            val production = HashMap<String, Int>()
            val territoryCount = HashMap<String, Int>()
            val victoryCities = HashMap<String, Int>()
            val unitsByOwner = HashMap<String, ArrayList<Unit>>()
            for (territory in gameData.map.territories) {
                val owner = territory.owner
                if (!owner.isNull && !territory.isWater) {
                    production.merge(owner.name, TerritoryAttachment.getProduction(territory), Int::plus)
                    territoryCount.merge(owner.name, 1, Int::plus)
                    val vc = TerritoryAttachment.get(territory).map { it.victoryCity }.orElse(0)
                    if (vc > 0) victoryCities.merge(owner.name, vc, Int::plus)
                }
                for (unit in territory.units) {
                    unitsByOwner.getOrPut(unit.owner.name) { ArrayList() }.add(unit)
                }
            }
            players.map { player ->
                val units = unitsByOwner[player.name] ?: emptyList<Unit>()
                PlayerStats(
                    name = player.name,
                    resources = player.resources.toString(),
                    pus = runCatching { player.resources.getQuantity("PUs") }.getOrDefault(0),
                    production = production[player.name] ?: 0,
                    territories = territoryCount[player.name] ?: 0,
                    units = units.size,
                    tuv = runCatching { TuvUtils.getTuv(units, tuvCalculator.getCostsForTuv(player)) }.getOrDefault(0),
                    victoryCities = victoryCities[player.name] ?: 0,
                    technologies = runCatching {
                        TechTracker.getCurrentTechAdvances(player, gameData.technologyFrontier).map { it.name }
                    }.getOrDefault(emptyList()),
                )
            }
        }.getOrDefault(emptyList())

        private fun toPath(polygon: Polygon): Path {
            val path = Path()
            if (polygon.npoints == 0) return path
            path.moveTo(polygon.xpoints[0].toFloat(), polygon.ypoints[0].toFloat())
            for (i in 1 until polygon.npoints) {
                path.lineTo(polygon.xpoints[i].toFloat(), polygon.ypoints[i].toFloat())
            }
            path.close()
            return path
        }
    }
}
