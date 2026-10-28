package org.triplea.mobile.online.client

import games.strategy.engine.GameOverException
import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.framework.GameDataManager
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.delegate.data.PlaceableUnits
import games.strategy.triplea.ui.PlaceData
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUiAdapter
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.online.UndoRequest
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.ClientMessage
import org.triplea.mobile.protocol.Clock
import org.triplea.mobile.protocol.DefenderNoticeClosed
import org.triplea.mobile.protocol.ENGINE_BASE_TAG
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.GameVersion
import org.triplea.mobile.protocol.Hello
import org.triplea.mobile.protocol.PROTOCOL_VERSION
import org.triplea.mobile.protocol.PlaceableQuery
import org.triplea.mobile.protocol.PlaceableResult
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Reply
import org.triplea.mobile.protocol.ServerMessage
import org.triplea.mobile.protocol.TokensDto
import org.triplea.mobile.protocol.UpdateRequired
import org.triplea.mobile.protocol.Watch
import org.triplea.mobile.protocol.WatchRefused

class GameConnectionTest {
    private val server = MockWebServer()
    private val fromClient = LinkedBlockingQueue<String>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val allSockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private val copies = LinkedBlockingQueue<Long>()
    private val states = LinkedBlockingQueue<Pair<GameConnection.State, String?>>()
    private val upgradeTokens = LinkedBlockingQueue<String>()
    private val backoffs = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private val saveRequested = CountDownLatch(1)

    @Volatile private var saveGate: CountDownLatch? = null

    @Volatile private var saveCode = 200

    @Volatile private var refusedUpgrades = 0

    @Volatile private var failedRefreshes = 0
    private val cancelled = CountDownLatch(1)
    private val expired = CountDownLatch(1)
    private val ui = TestUi()
    private lateinit var connection: GameConnection
    private lateinit var spectator: () -> GameConnection
    private val saveQueries = LinkedBlockingQueue<String>()

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/ws" -> {
                        upgradeTokens += request.headers["Authorization"]!!
                        if (refusedUpgrades-- > 0) MockResponse.Builder().code(401).build()
                        else MockResponse.Builder().webSocketUpgrade(ServerSide()).build()
                    }
                    "/auth/refresh" ->
                        if (failedRefreshes-- > 0) MockResponse.Builder().code(503).body("""{"error":"Unavailable"}""").build()
                        else MockResponse.Builder().body("""{"accessToken":"a2","refreshToken":"r2","expiresInSeconds":900,"username":"alice"}""").build()
                    "/games/g1/save" -> {
                        saveRequested.countDown()
                        saveQueries += request.url.query.orEmpty()
                        saveGate?.await(20, TimeUnit.SECONDS)
                        if (saveCode != 200) MockResponse.Builder().code(saveCode).body("""{"error":"Internal error"}""").build()
                        else MockResponse.Builder().addHeader("X-Game-Version", "3").body(Buffer().write(save)).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
        }
        server.start()
        val store = MemoryTokenStore().apply { tokens = TokensDto("a1", "r1", 900, "alice") }
        val listener = object : GameConnection.Listener {
            override fun copyChanged(data: GameData, version: Long) {
                copies += version
            }

            override fun questionCancelled() = cancelled.countDown()

            // as GameController does: the waiting UI request ends without an answer
            override fun questionExpired() {
                ui.expired = true
                ui.holdMove?.countDown()
                expired.countDown()
            }

            override fun connectionState(state: GameConnection.State, detail: String?) {
                states += state to detail
            }
        }
        val api = ServerApi(server.url("/").toString(), store)
        connection = GameConnection(api, "g1", ui, object : GameEventListener {}, listener, backoff = { backoffs += it; backoffSeconds(it) })
        spectator = { GameConnection(api, "g1", ui, object : GameEventListener {}, listener, spectate = true) }
    }

    @AfterEach
    fun stop() {
        connection.close()
        allSockets.forEach { it.close(1001, "test over") }
        server.close()
    }

    @Test
    fun `hello first, then a new version loads the save`() {
        val socket = open()

        socket.sendMessage(GameVersion("other game", 9))
        socket.sendMessage(GameVersion("g1", 3))

        assertThat(copies.poll(20, TimeUnit.SECONDS)).isEqualTo(3)
        assertThat(connection.data!!.playerList.getPlayerId("Russians")).isNotNull
        assertThat(copies).isEmpty()
    }

    @Test
    fun `a question is answered by the ui`() {
        val socket = open()

        socket.sendMessage(Question(5, "g1", "Russians", 3, Ask.Purchase("Russians", false, false)))

        val answer = next() as Answer
        assertThat(answer.questionId).isEqualTo(5)
        assertThat((answer.reply as Reply.Purchase).rules.values).containsExactly(1)
        assertThat(copies).containsExactly(3)
    }

    @Test
    fun `placeable is answered while the placement question is open`() {
        val socket = open()
        socket.sendMessage(Question(6, "g1", "Russians", 3, Ask.Place("Russians", false)))

        val query = next() as PlaceableQuery
        assertThat(query.questionId).isEqualTo(6)
        socket.sendMessage(PlaceableResult(6, query.territory, query.units.take(1), 1))

        assertThat(next()).isEqualTo(Answer(6, Reply.Done))
        assertThat(ui.placeable!!.errorMessage).isNull()
        assertThat(ui.placeable!!.units).hasSize(1)
        assertThat(ui.placeable!!.maxUnits).isEqualTo(1)
    }

    @Test
    fun `an undo in getMove becomes Reply Undo`() {
        ui.undoMove = true
        val socket = open()

        socket.sendMessage(Question(8, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))

        assertThat(next()).isEqualTo(Answer(8, Reply.Undo(0)))
    }

    @Test
    fun `update required stops the connection`() {
        val socket = open()

        socket.sendMessage(UpdateRequired(PROTOCOL_VERSION + 1, ENGINE_BASE_TAG))

        assertThat(generateSequence { states.poll(10, TimeUnit.SECONDS) }.first { it.first == GameConnection.State.UPDATE_REQUIRED }).isNotNull
        assertThat(sockets.poll(2, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `the end of the game cancels the open question`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        // the runner is blocked in the question: the cancel must not wait for it
        socket.sendMessage(GameEvent("g1", 3, Event.GameEnded("Game aborted by alice")))

        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
        ui.holdMove!!.countDown()
    }

    @Test
    fun `a clocked question closes on a clock of a newer question or its own stop, never on a stale clock`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        val now = System.currentTimeMillis()
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000), "alice", now + 60_000, questionId = 7))
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove"), deadline = now + 60_000))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        // a vacation moves the deadline of the same question's clock: it stays open
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000), "alice", now + 3 * 86_400_000L, questionId = 7))
        // out of order: an older question's stop, another player's clock read before the question changed, a
        // Clock without a question (vacation while nothing ran); none of them closes question 7
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000), null, null, questionId = 6))
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000, "bob" to 5_000), "bob", now, questionId = 6))
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000), null, null))
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000, "bob" to 5_000), "bob", now))
        assertThat(expired.await(1, TimeUnit.SECONDS)).isFalse()

        // the server answered it: its clock stopped
        socket.sendMessage(Clock("g1", mapOf("alice" to 0), null, null, questionId = 7))
        assertThat(expired.await(10, TimeUnit.SECONDS)).isTrue()
        socket.sendMessage(Clock("g1", mapOf("alice" to 0), "alice", now, questionId = 8))
        socket.sendMessage(Question(8, "g1", "Russians", 3, Ask.Purchase("Russians", false, false), deadline = now))
        // the first message after the expiry answers question 8: none went out for 7
        assertThat((next() as Answer).questionId).isEqualTo(8)
    }

    @Test
    fun `a clock of a newer question closes the open one`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        val now = System.currentTimeMillis()
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000), "alice", now + 60_000, questionId = 7))
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove"), deadline = now + 60_000))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        // the stop of 7 was missed, the start of 9 (another player's) comes
        socket.sendMessage(Clock("g1", mapOf("alice" to 0, "bob" to 5_000), "bob", now, questionId = 9))
        assertThat(expired.await(10, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a defender's question off the clock closes with its notice`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        val now = System.currentTimeMillis()
        // a clocked game: the device's time does not close it, and no clock names the window
        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000)))
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove"), deadline = now - 10_000))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        socket.sendMessage(DefenderNoticeClosed("other game", 7))
        socket.sendMessage(DefenderNoticeClosed("g1", 6))
        assertThat(expired.await(1, TimeUnit.SECONDS)).isFalse()

        socket.sendMessage(DefenderNoticeClosed("g1", 7))
        assertThat(expired.await(10, TimeUnit.SECONDS)).isTrue()

        // a reloaded game may close the notice and send the same question again: it is still answered
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Purchase("Russians", false, false)))
        assertThat((next() as Answer).questionId).isEqualTo(7)
    }

    @Test
    fun `a notice closed while its question downloads never reaches the ui`() {
        saveGate = CountDownLatch(1)
        val socket = open()
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(saveRequested.await(10, TimeUnit.SECONDS)).isTrue()

        // the window ends while the save downloads (after a return from the background)
        socket.sendMessage(DefenderNoticeClosed("g1", 7))
        Thread.sleep(500) // the close arrives before the download ends
        saveGate!!.countDown()
        socket.sendMessage(Question(8, "g1", "Russians", 3, Ask.Purchase("Russians", false, false)))

        assertThat((next() as Answer).questionId).isEqualTo(8)
        assertThat(ui.moves.get()).describedAs("question 7 never shown").isZero()
    }

    @Test
    fun `pause closes the socket and cancels the question until resume connects again`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        connection.pause()

        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(sockets.poll(3, TimeUnit.SECONDS)).describedAs("no reconnect while paused").isNull()
        // a start waiting for its first copy ends on RECONNECTING
        assertThat(states).noneMatch { it.first == GameConnection.State.RECONNECTING }
        connection.resume()
        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(next()).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        ui.holdMove!!.countDown()
    }

    @Test
    fun `without clocks the deadline closes the question, and only that one`() {
        val socket = open()
        val now = System.currentTimeMillis()
        // answered at once; its timer must not close the next question
        socket.sendMessage(Question(5, "g1", "Russians", 3, Ask.Purchase("Russians", false, false), deadline = now + 300))
        assertThat((next() as Answer).questionId).isEqualTo(5)
        ui.holdMove = CountDownLatch(1)
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()
        assertThat(expired.await(3, TimeUnit.SECONDS)).isFalse()
        ui.holdMove!!.countDown()
        assertThat(next()).isEqualTo(Answer(7, Reply.Done))

        // a deadline already past closes it at once
        ui.holdMove = CountDownLatch(1)
        socket.sendMessage(Question(9, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove"), deadline = now - 10_000))
        assertThat(expired.await(10, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a dropped socket reconnects and drops the cancelled answer`() {
        ui.holdMove = CountDownLatch(1)
        val socket = open()
        socket.sendMessage(Question(7, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(ui.inMove.await(20, TimeUnit.SECONDS)).isTrue()

        socket.close(1001, "going away")

        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(next()).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        ui.holdMove!!.countDown() // the UI answers only after the reconnect
        assertThat(fromClient.poll(2, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `a question dropped during its download never reaches the ui, the re-sent one is answered`() {
        saveGate = CountDownLatch(1)
        val socket = open()
        socket.sendMessage(Question(9, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(saveRequested.await(10, TimeUnit.SECONDS)).isTrue()

        socket.close(1001, "going away")
        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue()
        saveGate!!.countDown()

        val second = sockets.poll(10, TimeUnit.SECONDS)!!
        assertThat(next()).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        second.sendMessage(Question(9, "g1", "Russians", 3, Ask.Move("Russians", false, "russianCombatMove")))
        assertThat(next()).isEqualTo(Answer(9, Reply.Done))
        assertThat(ui.moves.get()).isEqualTo(1)
    }

    @Test
    fun `a save that cannot be downloaded is reported and the client reconnects`() {
        saveCode = 500
        val socket = open()

        socket.sendMessage(Question(10, "g1", "Russians", 3, Ask.Purchase("Russians", false, false)))

        val reported = generateSequence { states.poll(10, TimeUnit.SECONDS) }.first { it.first == GameConnection.State.RECONNECTING }
        assertThat(reported.second).isEqualTo("could not load the game: Internal error")
        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(next()).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
    }

    @Test
    fun `a save that cannot load backs off longer each time although the server keeps talking`() {
        saveCode = 500
        var socket = open()
        socket.sendMessage(GameVersion("g1", 3))
        socket = sockets.poll(10, TimeUnit.SECONDS)!!

        socket.sendMessage(Clock("g1", mapOf("alice" to 60_000)))
        socket.sendMessage(GameVersion("g1", 3))
        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull

        // neither the clock nor the GameVersion reset the backoff: the second wait is for 1 failure, not 0
        assertThat(backoffs).startsWith(0, 1)
    }

    @Test
    fun `a refused upgrade refreshes the token and reconnects with the new one`() {
        refusedUpgrades = 1

        open()

        assertThat(upgradeTokens).containsExactly("Bearer a1", "Bearer a2")
    }

    @Test
    fun `a refresh that fails with a server error refreshes again on the next connect`() {
        refusedUpgrades = 1
        failedRefreshes = 1

        open()

        // the expired a1 is not tried again: the retry renews first
        assertThat(upgradeTokens).containsExactly("Bearer a1", "Bearer a2")
    }

    @Test
    fun `a 4401 close refreshes the token before the next connect`() {
        val socket = open()

        socket.close(4401, "token expired")

        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(upgradeTokens).containsExactly("Bearer a1", "Bearer a2")
    }

    @Test
    fun `a spectator watches, loads the spectators' save and is never asked`() {
        connection = spectator()
        val socket = open()
        assertThat(next()).isEqualTo(Watch("g1"))

        socket.sendMessage(Question(5, "g1", "Russians", 3, Ask.Purchase("Russians", false, false)))
        socket.sendMessage(GameVersion("g1", 3))

        assertThat(copies.poll(20, TimeUnit.SECONDS)).isEqualTo(3)
        // the announced version, so the held events match the copy
        assertThat(saveQueries).containsExactly("spectate=true&version=3")
        assertThat(fromClient.poll(1, TimeUnit.SECONDS)).describedAs("no answer").isNull()

        socket.sendMessage(WatchRefused("g1", "This game cannot be watched"))
        assertThat(generateSequence { states.poll(10, TimeUnit.SECONDS) }.first { it.first == GameConnection.State.CLOSED })
            .isEqualTo(GameConnection.State.CLOSED to "This game cannot be watched")
        assertThat(sockets.poll(2, TimeUnit.SECONDS)).describedAs("no reconnect").isNull()
    }

    private fun open(): WebSocket {
        connection.open()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        assertThat(next()).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        return socket
    }

    private fun next(): ClientMessage = ProtocolJson.decodeClientMessage(fromClient.poll(20, TimeUnit.SECONDS)!!)

    private fun WebSocket.sendMessage(message: ServerMessage) = send(ProtocolJson.encode(message))

    private inner class ServerSide : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            allSockets += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            fromClient += text
        }
    }

    /** Buys one unit, asks which units fit at placement, and undoes or holds the move on request. */
    private inner class TestUi : HumanPlayerUiAdapter() {
        @Volatile var undoMove = false
        @Volatile var holdMove: CountDownLatch? = null
        @Volatile var expired = false
        val inMove = CountDownLatch(1)
        val moves = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var placeable: PlaceableUnits? = null

        override fun getPurchase(player: GamePlayer, bid: Boolean, keepCurrentPurchase: Boolean): Optional<IntegerMap<ProductionRule>> {
            val rule = player.productionFrontier.rules.first { it.results.totalValues() > 0 }
            return Optional.of(IntegerMap<ProductionRule>().apply { put(rule, 1) })
        }

        override fun getMove(player: GamePlayer, nonCombat: Boolean, stepName: String): Optional<MoveDescription> {
            moves.incrementAndGet()
            if (undoMove) throw UndoRequest(0)
            inMove.countDown()
            holdMove?.await(20, TimeUnit.SECONDS)
            if (expired) throw GameOverException("question expired")
            return Optional.empty()
        }

        override fun getPlacement(player: GamePlayer, bid: Boolean): Optional<PlaceData> {
            val territory = player.data.map.territories.first { t -> t.units.any { it.owner == player } }
            placeable = connection.placeable(territory, territory.units.filter { it.owner == player })
            return Optional.empty()
        }

        override fun chooseBattle(player: GamePlayer, battles: BattleListing): Optional<FightBattleDetails> = Optional.empty()

        override fun waitForEndTurn(player: GamePlayer) {}

        override fun notifyError(error: String) {}

        override fun notifyMessage(message: String, title: String) {}

        override fun confirm(title: String, question: String): Boolean = true
    }

    private companion object {
        /** The minimap at its start, as the server would send it. */
        val save: ByteArray by lazy {
            MobileEngine.configure(Files.createTempDirectory("online-client-test"))
            val source = Path.of(GameConnectionTest::class.java.getResource("/maps/minimap")!!.toURI())
            val target = MobileEngine.getMapsFolder().resolve("minimap")
            Files.walk(source).use { paths ->
                for (path in paths.toList()) {
                    val dest = target.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) Files.createDirectories(dest) else Files.copy(path, dest)
                }
            }
            val data = MobileEngine.parseGame(MobileEngine.listInstalledGames()[0].xmlPath).orElseThrow()
            ByteArrayOutputStream().also { GameDataManager.saveGame(it, data) }.toByteArray()
        }
    }
}
