package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.triplea.delegate.data.PlaceableUnits
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.PlaceableResult

/**
 * Host side: which of the query's units can be placed in its territory, asked of the active place
 * delegate. Runs on the connection's thread while the placement question is open; read-only
 * delegate queries from another thread are allowed, as for the desktop's network players. The
 * caller checks that [PlaceableQuery.questionId] is the open placement question of that player.
 */
fun answerPlaceable(query: PlaceableQuery, data: GameData, delegates: DelegateAccess): PlaceableResult =
    try {
        val names = Names(data)
        val (units, territory) = data.acquireReadLock().use { names.units(query.units) to names.territory(query.territory) }
        val delegate = delegates.current() as? IAbstractPlaceDelegate ?: invalid("no placement in progress")
        val result = delegate.getPlaceableUnits(units, territory)
        if (result.isError) {
            PlaceableResult(query.questionId, query.territory, emptyList(), 0, result.errorMessage)
        } else {
            PlaceableResult(query.questionId, query.territory, result.units.wireIds(), result.maxUnits)
        }
    } catch (e: RuntimeException) {
        // read-only query: any failure (unknown ids, no delegate, engine error) is the device's answer
        PlaceableResult(query.questionId, query.territory, emptyList(), 0, e.message ?: e.javaClass.simpleName)
    }

/** Device side: the engine's view of a [PlaceableResult] in the device's copy of the game. */
fun PlaceableResult.toPlaceableUnits(data: GameData): PlaceableUnits =
    error?.let { PlaceableUnits(it) } ?: PlaceableUnits(Names(data).units(units), maxUnits)
