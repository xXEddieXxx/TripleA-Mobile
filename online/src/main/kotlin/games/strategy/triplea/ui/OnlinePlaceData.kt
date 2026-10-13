package games.strategy.triplea.ui

import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit

// The desktop engine, which the server compiles this module against, has a package-private
// PlaceData constructor; this file shares its package.
internal fun newPlaceData(units: Collection<GameUnit>, at: Territory) = PlaceData(units, at)
