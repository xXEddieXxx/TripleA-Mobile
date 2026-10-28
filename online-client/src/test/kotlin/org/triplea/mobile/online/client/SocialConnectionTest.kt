package org.triplea.mobile.online.client

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.triplea.mobile.protocol.ChatError
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ChatSend
import org.triplea.mobile.protocol.DefenderNotice
import org.triplea.mobile.protocol.DefenderNoticeClosed
import org.triplea.mobile.protocol.ENGINE_BASE_TAG
import org.triplea.mobile.protocol.FriendsChanged
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.GameVersion
import org.triplea.mobile.protocol.Hello
import org.triplea.mobile.protocol.LobbyUpdate
import org.triplea.mobile.protocol.PROTOCOL_VERSION
import org.triplea.mobile.protocol.Presence
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.ServerMessage
import org.triplea.mobile.protocol.TokensDto

class SocialConnectionTest {
    private val server = MockWebServer()
    private val fromClient = LinkedBlockingQueue<String>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val received = LinkedBlockingQueue<ServerMessage>()
    private val upgradeTokens = LinkedBlockingQueue<String>()
    private val stopped = LinkedBlockingQueue<String>()
    private val connects = java.util.concurrent.atomic.AtomicInteger()

    @Volatile private var refusedUpgrades = 0

    @Volatile private var refreshCode = 200

    @Volatile private var failedRefreshes = 0
    private lateinit var connection: SocialConnection

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
                        else if (refreshCode != 200) MockResponse.Builder().code(refreshCode).body("""{"error":"Invalid refresh token"}""").build()
                        else MockResponse.Builder().body("""{"accessToken":"a2","refreshToken":"r2","expiresInSeconds":900,"username":"alice"}""").build()
                    else -> MockResponse.Builder().code(404).build()
                }
        }
        server.start()
        val store = MemoryTokenStore().apply { tokens = TokensDto("a1", "r1", 900, "alice") }
        connection = SocialConnection(ServerApi(server.url("/").toString(), store), { received += it }, { stopped += it }, { connects.incrementAndGet() })
    }

    @AfterEach
    fun stop() {
        connection.close()
        sockets.forEach { it.close(1001, "test over") }
        server.close()
    }

    @Test
    fun `defender notices reach the listener and every connect is announced before the servers messages`() {
        val socket = open()
        assertThat(connects.get()).isEqualTo(1)
        socket.send(ProtocolJson.encode(DefenderNotice("g1", 7, "Italians", "casualties", 1_800_000_000_000)))
        socket.send(ProtocolJson.encode(DefenderNoticeClosed("g1", 7)))

        assertThat(List(2) { received.poll(10, TimeUnit.SECONDS) })
            .containsExactly(DefenderNotice("g1", 7, "Italians", "casualties", 1_800_000_000_000), DefenderNoticeClosed("g1", 7))
        // a drop and reconnect announces itself again, so the app forgets notices the server will re-send
        socket.close(1001, "drop")
        sockets.poll(10, TimeUnit.SECONDS)!!
        val deadline = System.currentTimeMillis() + 10_000
        while (connects.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertThat(connects.get()).isEqualTo(2)
    }

    @Test
    fun `hello first, then social messages reach the listener and game messages do not`() {
        val socket = open()
        val game = GameInfoDto("g1", "minimap", "Minimap", "lobby", emptyList(), 0, 0, null, null)

        socket.send(ProtocolJson.encode(GameVersion("g1", 3)))
        socket.send(ProtocolJson.encode(ChatMessage(2, "lobby", "bob", "hi", 5)))
        socket.send(ProtocolJson.encode(Presence("bob", true)))
        socket.send(ProtocolJson.encode(LobbyUpdate(game)))
        socket.send(ProtocolJson.encode(ChatError("lobby", "Too many messages")))
        socket.send(ProtocolJson.encode(FriendsChanged("bob")))

        assertThat(List(5) { received.poll(10, TimeUnit.SECONDS) }).containsExactly(
            ChatMessage(2, "lobby", "bob", "hi", 5),
            Presence("bob", true),
            LobbyUpdate(game),
            ChatError("lobby", "Too many messages"),
            FriendsChanged("bob"),
        )
        assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `chat goes out after hello, and the socket comes back after a drop`() {
        val socket = open()
        assertThat(connection.send(ChatSend("lobby", "hello"))).isTrue()
        assertThat(ProtocolJson.decodeClientMessage(fromClient.poll(10, TimeUnit.SECONDS)!!)).isEqualTo(ChatSend("lobby", "hello"))

        socket.close(1001, "server restart")

        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(ProtocolJson.decodeClientMessage(fromClient.poll(10, TimeUnit.SECONDS)!!)).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
    }

    @Test
    fun `nothing is sent before the socket is open`() {
        assertThat(connection.send(ChatSend("lobby", "too early"))).isFalse()
    }

    @Test
    fun `a 4401 close renews the token before reconnecting`() {
        val socket = open()

        socket.close(4401, "token expired")

        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(upgradeTokens).containsExactly("Bearer a1", "Bearer a2")
    }

    @Test
    fun `a refresh that fails with a server error refreshes again on the next connect`() {
        refusedUpgrades = 1
        failedRefreshes = 1

        connection.open()

        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        // the expired a1 is not tried again: the retry renews first
        assertThat(upgradeTokens).containsExactly("Bearer a1", "Bearer a2")
    }

    @Test
    fun `a refused refresh stops reconnecting and says so`() {
        refusedUpgrades = 1
        refreshCode = 401

        connection.open()

        assertThat(stopped.poll(10, TimeUnit.SECONDS)).isEqualTo("please log in again")
        Thread.sleep(2_500) // longer than the first backoff
        assertThat(upgradeTokens).containsExactly("Bearer a1")
    }

    @Test
    fun `close stops reconnecting`() {
        open()

        connection.close()

        Thread.sleep(2_500)
        assertThat(upgradeTokens).hasSize(1)
        assertThat(stopped).isEmpty()
    }

    @Test
    fun `pause closes the socket without reconnecting until resume opens one again`() {
        open()

        connection.pause()

        Thread.sleep(2_500) // longer than the first backoff
        assertThat(upgradeTokens).describedAs("no reconnect while paused").hasSize(1)
        assertThat(connection.send(ChatSend("lobby", "away"))).isFalse()
        connection.resume()
        assertThat(sockets.poll(10, TimeUnit.SECONDS)).isNotNull
        assertThat(ProtocolJson.decodeClientMessage(fromClient.poll(10, TimeUnit.SECONDS)!!)).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        assertThat(sockets.poll(2, TimeUnit.SECONDS)).describedAs("only one new socket").isNull()
        assertThat(stopped).isEmpty()
    }

    @Test
    fun `chat is merged by id, sorted and capped`() {
        fun m(id: Long) = ChatMessage(id, "lobby", "bob", "m$id", id)

        assertThat(mergeChat(listOf(m(1), m(3)), listOf(m(2), m(3), m(5)), 3)).containsExactly(m(2), m(3), m(5))
    }

    private fun open(): WebSocket {
        connection.open()
        val socket = sockets.poll(10, TimeUnit.SECONDS)!!
        assertThat(ProtocolJson.decodeClientMessage(fromClient.poll(10, TimeUnit.SECONDS)!!)).isEqualTo(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG))
        // the client marks itself connected right after Hello
        val deadline = System.currentTimeMillis() + 5_000
        while (!connection.send(ChatSend("lobby", "probe")) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        fromClient.poll(10, TimeUnit.SECONDS)
        return socket
    }

    private inner class ServerSide : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            fromClient += text
        }
    }
}
