package org.triplea.mobile.app.game

import games.strategy.engine.data.GameData
import games.strategy.engine.message.IRemote
import games.strategy.triplea.ResourceLoader
import games.strategy.triplea.ui.mapdata.MapData
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing
import java.nio.file.Path
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.MobileEngine

/** What the UI needs from a running game, local or online. */
interface GameSession {
    val gameData: GameData
    val mapData: MapData
    val resourceLoader: ResourceLoader
    val isGameOver: Boolean

    /** The active step's delegate as [type] (move, place, ...). */
    fun <T : IRemote> currentDelegate(type: Class<T>): T

    fun saveGame(file: Path)

    fun stop()
}

/**
 * A finished online game's final save, shown read-only in the history view: no engine runs, nobody is asked.
 * Throws when the game's map is not installed on this device.
 */
class ReplaySession(override val gameData: GameData) : GameSession {
    override val resourceLoader: ResourceLoader = ResourceLoader(
        listOfNotNull(
            InstalledMapsListing.searchAllMapsForMapName(gameData.mapName)
                .orElseThrow { IllegalStateException("The map ${gameData.mapName} is not installed on this device") },
            MobileEngine.getEngineAssetsFolder().orElse(null),
        ),
    )
    override val mapData: MapData = MapData(resourceLoader)
    override val isGameOver: Boolean = true

    override fun <T : IRemote> currentDelegate(type: Class<T>): T = throw UnsupportedOperationException("a replay has no active delegate")

    override fun saveGame(file: Path) = MobileEngine.saveGame(gameData, file)

    override fun stop() = kotlin.Unit
}

/** A game whose engine runs on this device. */
class LocalSession(val local: LocalGameSession) : GameSession {
    override val gameData: GameData get() = local.gameData
    override val mapData: MapData get() = local.mapData
    override val resourceLoader: ResourceLoader get() = local.resourceLoader
    override val isGameOver: Boolean get() = local.isGameOver

    override fun <T : IRemote> currentDelegate(type: Class<T>): T = local.getCurrentRemoteDelegate(type)

    override fun saveGame(file: Path) = local.saveGame(file)

    override fun stop() = local.stop()
}
