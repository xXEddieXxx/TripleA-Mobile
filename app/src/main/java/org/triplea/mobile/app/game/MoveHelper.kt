package org.triplea.mobile.app.game

import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.delegate.AbstractMoveDelegate
import games.strategy.triplea.delegate.Matches
import games.strategy.triplea.delegate.data.PlaceableUnits
import games.strategy.triplea.delegate.move.validation.AirMovementValidator
import games.strategy.triplea.delegate.move.validation.MoveValidator
import games.strategy.triplea.Properties
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate
import games.strategy.triplea.delegate.remote.IMoveDelegate
import games.strategy.triplea.ui.panel.move.MovableUnitsFilter
import games.strategy.triplea.delegate.TransportTracker
import games.strategy.triplea.util.TransportUtils
import games.strategy.triplea.UnitUtils
import org.triplea.mobile.LocalGameSession

/** A move the user is about to confirm. */
class MovePlan(
    val description: MoveDescription,
    val from: Territory,
    val to: Territory,
    val allUnitsCanMove: Boolean,
    val warning: String?,
    /**
     * Air units of this move that cannot get back to a friendly landing spot. Only set when the
     * game's "Kamikaze Airplanes" rule lets such a move happen; the planes are lost at the end of
     * the turn, so the user is asked before the move goes through.
     */
    val lostAir: List<Unit> = emptyList(),
)

/** A move or placement made in the current phase, as listed in the desktop "undo" panel. */
class MadeMove(
    val index: Int,
    /** "Caucasus -> Ukraine S.S.R." or, for placements, the territory. */
    val label: String,
    val units: List<Unit>,
    /** Territories of the route in order (start to end); placements have only the target. */
    val routeTerritories: List<String>,
    val canUndo: Boolean,
    val reasonCantUndo: String?,
)

/** Read-only queries against the engine used by the move and placement phases. */
object MoveHelper {

    /** The moves of the current move phase, oldest first. */
    fun movesMade(session: LocalGameSession): List<MadeMove> = runCatching {
        session.gameData.acquireReadLock().use {
            session.getCurrentRemoteDelegate(IMoveDelegate::class.java).movesMade.mapIndexed { index, move ->
                val canUndo = move.canUndo
                MadeMove(
                    index = index,
                    label = move.moveLabel,
                    units = move.units.toList(),
                    routeTerritories = move.route.allTerritories.map { it.name },
                    canUndo = canUndo,
                    reasonCantUndo = if (canUndo) null else runCatching { move.reasonCantUndo }.getOrNull(),
                )
            }
        }
    }.getOrDefault(emptyList())

    /** The placements of the current placement phase, oldest first. */
    fun placementsMade(session: LocalGameSession): List<MadeMove> = runCatching {
        session.gameData.acquireReadLock().use {
            session.getCurrentRemoteDelegate(IAbstractPlaceDelegate::class.java).movesMade.mapIndexed { index, placement ->
                val canUndo = placement.canUndo
                MadeMove(
                    index = index,
                    label = placement.moveLabel,
                    units = placement.units.toList(),
                    routeTerritories = listOf(placement.end.name),
                    canUndo = canUndo,
                    reasonCantUndo = if (canUndo) null else runCatching { placement.reasonCantUndo }.getOrNull(),
                )
            }
        }
    }.getOrDefault(emptyList())

    /** Undoes the move with the given index of [movesMade]; returns an error text or null. */
    fun undoMoveAt(session: LocalGameSession, index: Int): String? = runCatching {
        session.getCurrentRemoteDelegate(IMoveDelegate::class.java).undoMove(index)
    }.getOrElse { it.message }

    fun undoPlacementAt(session: LocalGameSession, index: Int): String? = runCatching {
        session.getCurrentRemoteDelegate(IAbstractPlaceDelegate::class.java).undoMove(index)
    }.getOrElse { it.message }

    fun territory(session: LocalGameSession, name: String): Territory? =
        session.gameData.map.getTerritoryOrNull(name)

    /**
     * Units of the player in the territory that can still be moved: units with movement left, and
     * cargo aboard a transport. Loading uses up the movement of a 1-move infantry, but the engine
     * lets cargo unload regardless of its own movement (the transport carries it), so it stays
     * selectable for an unload or amphibious assault in the same turn.
     */
    fun movableUnits(session: LocalGameSession, player: GamePlayer, territory: Territory): List<Unit> {
        session.gameData.acquireReadLock().use {
            return territory.units.filter {
                it.owner == player &&
                    !Matches.unitIsInfrastructure().test(it) &&
                    (Matches.unitHasMovementLeft().test(it) || (territory.isWater && Matches.unitIsBeingTransported().test(it)))
            }
        }
    }

    /** Validates a move and reduces the units to those that can actually make it. */
    fun plan(
        session: LocalGameSession,
        player: GamePlayer,
        nonCombat: Boolean,
        from: Territory,
        to: Territory,
        units: List<Unit>,
    ): Result<MovePlan> = runCatching {
        val gameData = session.gameData
        val movesMade = session.getCurrentRemoteDelegate(IMoveDelegate::class.java).movesMade
        gameData.acquireReadLock().use {
            val route = MoveValidator.getBestRoute(from, to, gameData, player, units, false).orElse(null)
                ?: throw IllegalArgumentException("No route from ${from.name} to ${to.name}")
            val filter = MovableUnitsFilter(
                gameData,
                player,
                route,
                nonCombat,
                AbstractMoveDelegate.MoveType.DEFAULT,
                movesMade,
                emptyMap(),
            )
            val result = filter.filterUnitsThatCanMove(units)
            if (result.status == MovableUnitsFilter.FilterOperationResult.Status.NO_UNITS_CAN_MOVE) {
                throw IllegalArgumentException(
                    result.warningOrErrorMessage.orElse("None of the selected units can move there")
                )
            }
            val movingUnits = result.unitsWithDependents
            // Only a sea load (land units moving from land into a sea zone) needs a unit -> transport
            // mapping, like the desktop move panel. A loaded transport moving on, or units unloading,
            // must not be mapped onto other transports in the target zone.
            val landUnits = movingUnits.filter { Matches.unitIsLand().test(it) }
            val transportMapping: Map<Unit, Unit> = if (route.isSeaLoad && landUnits.isNotEmpty()) {
                val minCost = landUnits.minOf { it.unitAttachment.transportCost }
                val transports = to.units.filter {
                    Matches.unitIsSeaTransport().test(it) &&
                        Matches.alliedUnit(player).test(it) &&
                        TransportTracker.getAvailableCapacity(it) >= minCost
                }
                if (transports.isEmpty()) throw IllegalArgumentException("No transport with room in ${to.name}")
                val mapping = TransportUtils.mapTransports(route, movingUnits, transports)
                if (mapping.isEmpty()) throw IllegalArgumentException("The transports in ${to.name} cannot carry these units")
                mapping
            } else {
                emptyMap()
            }
            // Without the "Kamikaze Airplanes" rule the engine has already dropped air that cannot
            // land (like the desktop client). With it, the engine skips the check, so do it here to
            // warn the user about the planes that would be sacrificed.
            val lostAir = if (Properties.getKamikazeAirplanes(gameData.properties) && movingUnits.any { Matches.unitIsAir().test(it) }) {
                runCatching { AirMovementValidator.airThatCannotLand(movingUnits, route, player).toList() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            MovePlan(
                description = MoveDescription(movingUnits, route, transportMapping),
                from = from,
                to = to,
                allUnitsCanMove = result.status == MovableUnitsFilter.FilterOperationResult.Status.ALL_UNITS_CAN_MOVE,
                warning = result.warningOrErrorMessage?.orElse(null),
                lostAir = lostAir,
            )
        }
    }

    fun movesMadeCount(session: LocalGameSession): Int = runCatching {
        session.getCurrentRemoteDelegate(IMoveDelegate::class.java).movesMade.size
    }.getOrDefault(0)

    fun undoLastMove(session: LocalGameSession): String? = runCatching {
        val delegate = session.getCurrentRemoteDelegate(IMoveDelegate::class.java)
        val count = delegate.movesMade.size
        if (count == 0) "Nothing to undo" else delegate.undoMove(count - 1)
    }.getOrElse { it.message }

    /**
     * How many units the player can place this turn with the factories they own, or null when the
     * rules allow placement anywhere (unlimited). Mirrors the estimate the AI uses for purchases.
     */
    fun placementCapacity(session: LocalGameSession, player: GamePlayer): Int? = runCatching {
        val gameData = session.gameData
        gameData.acquireReadLock().use {
            val rules = player.rulesAttachment
            if (rules != null && rules.placementAnyTerritory) return@use null
            val factoryMatch = Matches.unitIsOwnedAndIsFactoryOrCanProduceUnits(player)
            var capacity = 0L
            for (territory in gameData.map.territories) {
                if (territory.owner != player) continue
                if (territory.units.none { factoryMatch.test(it) }) continue
                capacity += UnitUtils.getProductionPotentialOfTerritory(territory.units, territory, player, true, true)
            }
            capacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }.getOrNull()

    /** Units the player has bought and not placed yet. */
    fun unitsToPlace(session: LocalGameSession, player: GamePlayer): List<Unit> {
        session.gameData.acquireReadLock().use {
            return player.unitCollection.units.toList()
        }
    }

    fun placeableUnits(session: LocalGameSession, player: GamePlayer, territory: Territory): Result<PlaceableUnits> =
        runCatching {
            val units = unitsToPlace(session, player)
            session.getCurrentRemoteDelegate(IAbstractPlaceDelegate::class.java).getPlaceableUnits(units, territory)
        }

    fun undoLastPlacement(session: LocalGameSession): String? = runCatching {
        val delegate = session.getCurrentRemoteDelegate(IAbstractPlaceDelegate::class.java)
        val count = delegate.placementsMade
        if (count == 0) "Nothing to undo" else delegate.undoMove(count - 1)
    }.getOrElse { it.message }
}
