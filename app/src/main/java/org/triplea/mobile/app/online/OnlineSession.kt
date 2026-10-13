package org.triplea.mobile.app.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing
import games.strategy.engine.message.IRemote
import games.strategy.triplea.ResourceLoader
import games.strategy.triplea.ui.mapdata.MapData
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.app.game.GameSession
import org.triplea.mobile.app.game.MoveRequest
import org.triplea.mobile.app.game.PlaceRequest
import org.triplea.mobile.app.game.UiRequest
import org.triplea.mobile.online.UndoRequest
import org.triplea.mobile.online.client.GameConnection
import org.triplea.mobile.online.client.ServerApi

/**
 * A game hosted by the server. The device holds a copy (the connection's latest save) and answers
 * the server's questions; the active delegate is a proxy that reads from the copy and sends undo
 * and placement queries to the server. Call [open][GameConnection.open] on [connection] to start.
 * A [spectating] session watches the game read-only, a few saves late, and is never asked anything.
 */
class OnlineSession(
    val api: ServerApi,
    val gameId: String,
    ui: HumanPlayerUi,
    events: GameEventListener,
    listener: GameConnection.Listener,
    val spectating: Boolean = false,
) : GameSession {
    @Volatile private var over = false

    val connection = GameConnection(
        api,
        gameId,
        ui,
        events,
        object : GameConnection.Listener by listener {
            override fun connectionState(state: GameConnection.State, detail: String?) {
                if (state == GameConnection.State.CLOSED || state == GameConnection.State.UPDATE_REQUIRED) over = true
                listener.connectionState(state, detail)
            }
        },
        spectate = spectating,
    )

    override val gameData: GameData get() = connection.data ?: error("the game is not loaded yet")
    override val mapData: MapData get() = assets().mapData
    override val resourceLoader: ResourceLoader get() = assets().loader
    override val isGameOver: Boolean get() = over

    /** The server announced the end of the game. */
    fun gameEnded() {
        over = true
    }

    private class Assets(val mapName: String, val loader: ResourceLoader, val mapData: MapData)

    private var assets: Assets? = null

    /** The installed map's images and data, as MobileLaunchAction builds them; rebuilt only for another map. */
    @Synchronized
    private fun assets(): Assets {
        val mapName = gameData.mapName
        assets?.takeIf { it.mapName == mapName }?.let { return it }
        val mapPath = InstalledMapsListing.searchAllMapsForMapName(mapName)
            .orElseThrow { IllegalStateException("The map $mapName is not installed on this device") }
        val loader = ResourceLoader(listOfNotNull(mapPath, MobileEngine.getEngineAssetsFolder().orElse(null)))
        return Assets(mapName, loader, MapData(loader)).also { assets = it }
    }

    override fun <T : IRemote> currentDelegate(type: Class<T>): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args -> delegateCall(proxy, method, args.orEmpty()) })

    @Suppress("UNCHECKED_CAST")
    private fun delegateCall(proxy: Any, method: Method, args: Array<out Any?>): Any? =
        when (method.name) {
            "toString" -> "online ${method.declaringClass.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            // the copy's delegate of the current step was loaded with the save, its move list included
            "getMovesMade" ->
                try {
                    method.invoke(gameData.sequence.step.delegate, *args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            "undoMove" -> undo(args[0] as Int)
            "getPlaceableUnits" -> connection.placeable(args[1] as Territory, (args[0] as Collection<GameUnit>).toList())
            else -> throw UnsupportedOperationException(method.name)
        }

    private var undoneRequest: UiRequest<*>? = null

    /** Ends the open move or placement question with an undo for the server; the next question's save brings the new list. */
    @Synchronized
    private fun undo(index: Int): String? {
        // another question (a battle dialog over the move phase) must not be answered with an undo
        val request = GameController.pending.value?.takeIf { it is MoveRequest || it is PlaceRequest } ?: return "Nothing to undo now"
        // one undo per question: the move list only changes with the server's next save
        if (request === undoneRequest) return "Undo already sent"
        undoneRequest = request
        request.fail(UndoRequest(index))
        return null
    }

    /** Online, the copy is what the device has; this lets the player keep a local save. */
    override fun saveGame(file: Path) = MobileEngine.saveGame(gameData, file)

    override fun stop() = connection.close()
}
