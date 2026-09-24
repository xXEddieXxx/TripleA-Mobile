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
import games.strategy.triplea.attachments.TerritoryAttachment
import games.strategy.triplea.ui.mapdata.MapData
import games.strategy.triplea.util.TuvCostsCalculator
import games.strategy.triplea.util.TuvUtils
import games.strategy.triplea.util.UnitSeparator
import org.triplea.geom.Polygon
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.UnitImageNames

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
)

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
    val relationships: List<RelationshipLine>,
    /** Whether any nation has political actions (a diplomacy phase) in this game. */
    val hasPolitics: Boolean,
    /** Whether any nation has user actions (map specific special actions). */
    val hasUserActions: Boolean,
) {
    val byName: Map<String, TerritorySnapshot> = territories.associateBy { it.name }

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

        /** Builds a snapshot under the game data read lock. */
        fun build(session: LocalGameSession, version: Int): MapSnapshot {
            val gameData: GameData = session.gameData
            val mapData: MapData = session.mapData
            val unitWidth = (mapData.defaultUnitWidth * mapData.defaultUnitScale).toInt().coerceAtLeast(8)
            val territories = ArrayList<TerritorySnapshot>()
            var battleSites: Set<String> = emptySet()
            val stats = ArrayList<PlayerStats>()
            var hasVictoryCities = false
            val drawNames = mapData.drawTerritoryNames()
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
                    )
                }
                stats += computeStats(gameData)
            }
            val history = gameData.acquireReadLock().use { readHistory(gameData) }
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
                    relationships = lines
                    hasPolitics = players.any { PoliticalActionAttachment.getPoliticalActionAttachments(it).isNotEmpty() }
                    hasUserActions = players.any { UserActionAttachment.getUserActionAttachments(it).isNotEmpty() }
                }
            }
            return MapSnapshot(version, territories, battleSites, stats, hasVictoryCities, history, relationships, hasPolitics, hasUserActions)
        }

        private const val MAX_HISTORY_BLOCKS = 300

        /**
         * Flattens the engine's history tree (Round > Step > Event > detail) into step blocks.
         * The rendering data the engine attaches to nodes (dice rolls, unit lists, the battle
         * territory, move and placement descriptions) is kept so the history can show icons and dice.
         */
        private fun readHistory(gameData: GameData): List<HistoryBlock> = runCatching {
            val blocks = ArrayList<HistoryBlock>()
            val root = gameData.history.root
            for (roundNode in root.childList()) {
                val roundNo = (roundNode as? Round)?.roundNo ?: 0
                for (stepNode in roundNode.childList()) {
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
                    for (eventNode in step.childList()) {
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
