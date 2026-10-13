package org.triplea.mobile.online.client

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.triplea.mobile.protocol.StandingOrdersDto
import org.triplea.mobile.protocol.TokensDto
import org.triplea.mobile.protocol.UpdateGameRequest

class ServerApiTest {
    private val server = MockWebServer()
    private val store = MemoryTokenStore()
    private lateinit var api: ServerApi

    @BeforeEach
    fun start() {
        server.start()
        api = ServerApi(server.url("/").toString(), store)
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `login stores the tokens`() {
        server.enqueue(json(200, tokens("a1", "r1")))

        val issued = api.login("alice", "secret")

        assertThat(store.tokens).isEqualTo(issued).isEqualTo(TokensDto("a1", "r1", 900, "alice"))
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/auth/login")
        assertThat(request.body!!.utf8()).isEqualTo("""{"username":"alice","password":"secret"}""")
    }

    @Test
    fun `a 401 refreshes once and retries with the new token`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(401, """{"error":"expired"}"""))
        server.enqueue(json(200, tokens("a2", "r2")))
        server.enqueue(json(200, "[]"))

        assertThat(api.games()).isEmpty()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer a1")
        val refresh = server.takeRequest()
        assertThat(refresh.url.encodedPath).isEqualTo("/auth/refresh")
        assertThat(refresh.body!!.utf8()).isEqualTo("""{"refreshToken":"r1"}""")
        val retry = server.takeRequest()
        assertThat(retry.url.encodedPath).isEqualTo("/games")
        assertThat(retry.headers["Authorization"]).isEqualTo("Bearer a2")
        assertThat(store.tokens?.refreshToken).isEqualTo("r2")
    }

    @Test
    fun `a failed refresh clears the tokens`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(401, """{"error":"expired"}"""))
        server.enqueue(json(401, """{"error":"Invalid refresh token"}"""))

        assertThatThrownBy { api.games() }
            .isInstanceOfSatisfying(ServerException::class.java) { assertThat(it.status).isEqualTo(401) }
            .hasMessage("please log in again")
        assertThat(store.tokens).isNull()
    }

    @Test
    fun `a server error during refresh keeps the tokens`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(401, """{"error":"expired"}"""))
        server.enqueue(json(503, """{"error":"Maintenance"}"""))

        assertThatThrownBy { api.games() }
            .isInstanceOfSatisfying(ServerException::class.java) { assertThat(it.status).isEqualTo(503) }
        assertThat(store.tokens?.refreshToken).isEqualTo("r1")
    }

    @Test
    fun `accessToken refreshes a token that is about to expire`() {
        server.enqueue(json(200, """{"accessToken":"a1","refreshToken":"r1","expiresInSeconds":30,"username":"alice"}"""))
        server.enqueue(json(200, tokens("a2", "r2")))
        api.login("alice", "secret")

        assertThat(api.accessToken()).isEqualTo("a2")
        server.takeRequest()
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/auth/refresh")
    }

    @Test
    fun `a save over the size cap is refused`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(MockResponse.Builder().addHeader("X-Game-Version", "1").addHeader("Content-Length", (64L * 1024 * 1024 + 1).toString()).build())

        assertThatThrownBy { api.downloadSave("g1") }.hasMessage("save too large")
    }

    @Test
    fun `errors carry the server's text`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(404, """{"error":"No such game"}"""))

        assertThatThrownBy { api.game("g1") }
            .isInstanceOfSatisfying(ServerException::class.java) { assertThat(it.status).isEqualTo(404) }
            .hasMessage("No such game")
    }

    @Test
    fun `downloadSave reads the version header`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(MockResponse.Builder().addHeader("X-Game-Version", "7").body(Buffer().write(byteArrayOf(1, 2, 3))).build())

        val (version, bytes) = api.downloadSave("g1")

        assertThat(version).isEqualTo(7)
        assertThat(bytes).containsExactly(1, 2, 3)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/games/g1/save")
    }

    @Test
    fun `webSocketUrl maps http to ws`() {
        assertThat(ServerApi("http://h:8080", store).webSocketUrl).isEqualTo("ws://h:8080/ws")
        assertThat(ServerApi("https://h/", store).webSocketUrl).isEqualTo("wss://h/ws")
    }

    @Test
    fun `lobby calls use the server's routes and bodies`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        val info = """{"id":"g1","mapName":"m","gameName":"G","status":"lobby","seats":[{"nation":"Germans","kind":"HUMAN","username":"alice","ready":true}],"version":0,"round":0,"step":null,"currentPlayer":null}"""
        server.enqueue(json(200, info))
        server.enqueue(json(200, info))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())

        assertThat(api.join("g1", "Germans", "code1").seats.single().ready).isTrue()
        assertThat(api.ready("g1", true).status).isEqualTo("lobby")
        api.leave("g1", "Germans")
        api.deleteGame("g1")

        server.takeRequest().let {
            assertThat(it.url.encodedPath).isEqualTo("/games/g1/join")
            assertThat(it.body!!.utf8()).isEqualTo("""{"nation":"Germans","inviteCode":"code1"}""")
        }
        server.takeRequest().let { assertThat(it.body!!.utf8()).isEqualTo("""{"ready":true}""") }
        server.takeRequest().let { assertThat(it.url.encodedPath).isEqualTo("/games/g1/leave") }
        server.takeRequest().let {
            assertThat(it.method).isEqualTo("DELETE")
            assertThat(it.url.encodedPath).isEqualTo("/games/g1")
        }
    }

    @Test
    fun `seat edits, option changes and features`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        val info = """{"id":"g1","mapName":"m","gameName":"G","status":"lobby","seats":[],"version":0,"round":0,"step":null,"currentPlayer":null,"mapReady":false,"error":null,"allowSpectators":false}"""
        server.enqueue(json(200, info))
        server.enqueue(json(200, info))
        server.enqueue(json(200, """{"email":true}"""))

        assertThat(api.putSeat("g1", "Germans", "HUMAN", "alice").mapReady).isFalse()
        assertThat(api.patchGame("g1", UpdateGameRequest(visibility = "public", noTimeControl = true)).allowSpectators).isFalse()
        store.tokens = null
        assertThat(api.features().email).isTrue()

        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("PUT" to "/games/g1/seats/Germans")
            assertThat(it.body!!.utf8()).isEqualTo("""{"kind":"HUMAN","username":"alice"}""")
        }
        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("PATCH" to "/games/g1")
            assertThat(it.body!!.utf8()).isEqualTo("""{"visibility":"public","noTimeControl":true}""")
        }
        server.takeRequest().let {
            assertThat(it.url.encodedPath).isEqualTo("/server/features")
            assertThat(it.headers["Authorization"]).isNull()
        }
    }

    @Test
    fun `turns, standing orders, vacation and back`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(200, """{"turns":[{"gameId":"g1","questionId":5,"nation":"Germans"}],"unreadDirect":2}"""))
        server.enqueue(json(200, """{"scramble":"never","defenderMode":"standing"}"""))
        repeat(4) { server.enqueue(MockResponse.Builder().code(204).build()) }

        api.turns().let {
            assertThat(it.turns.single().questionId).isEqualTo(5)
            assertThat(it.unreadDirect).isEqualTo(2)
        }
        assertThat(api.orders("g1").defenderMode).isEqualTo("standing")
        api.setOrders("g1", StandingOrdersDto(casualties = "cheapest_first"))
        api.setDefenderMode("g1", "live")
        api.vacation("g1", 3)
        api.back("g1")

        server.takeRequest().let { assertThat(it.url.encodedPath).isEqualTo("/me/turns") }
        server.takeRequest().let { assertThat(it.url.encodedPath).isEqualTo("/games/g1/orders") }
        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("PUT" to "/games/g1/orders")
            assertThat(it.body!!.utf8()).contains(""""casualties":"cheapest_first"""")
        }
        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("PUT" to "/games/g1/defender-mode")
            assertThat(it.body!!.utf8()).isEqualTo("""{"mode":"live"}""")
        }
        server.takeRequest().let { assertThat(it.body!!.utf8()).isEqualTo("""{"days":3}""") }
        server.takeRequest().let { assertThat(it.method to it.url.encodedPath).isEqualTo("POST" to "/games/g1/back") }
    }

    @Test
    fun `friends, blocks and chat`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(200, """[{"username":"bob","status":"incoming","online":false}]"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(200, """["carol"]"""))
        server.enqueue(json(200, """[{"id":7,"channel":"game:g1","username":"bob","text":"gl","sentAt":1}]"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(204).build())

        assertThat(api.friends().single().status).isEqualTo("incoming")
        api.acceptFriend("bob")
        api.unblock("carol")
        assertThat(api.blocks()).containsExactly("carol")
        assertThat(api.chat("game:g1").single().text).isEqualTo("gl")
        api.report("bob", "spam", 7)
        api.markRead("dm:1:2", 9)

        server.takeRequest()
        server.takeRequest().let {
            assertThat(it.method).isEqualTo("POST")
            assertThat(it.url.encodedPath).isEqualTo("/friends/bob/accept")
        }
        server.takeRequest().let { assertThat(it.method to it.url.encodedPath).isEqualTo("DELETE" to "/blocks/carol") }
        server.takeRequest()
        server.takeRequest().let { assertThat(it.url.encodedPath).isEqualTo("/chat/game:g1") }
        server.takeRequest().let { assertThat(it.body!!.utf8()).isEqualTo("""{"username":"bob","reason":"spam","chatMessageId":7}""") }
        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("POST" to "/chat/dm:1:2/read")
            assertThat(it.body!!.utf8()).isEqualTo("""{"lastReadId":9}""")
        }
    }

    @Test
    fun `deleting the account forgets the tokens, a wrong password keeps them`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(json(403, """{"error":"Wrong password"}"""))
        server.enqueue(MockResponse.Builder().code(204).build())

        assertThatThrownBy { api.deleteAccount("nope") }.hasMessage("Wrong password")
        assertThat(store.tokens).isNotNull
        api.deleteAccount("secret")

        assertThat(store.tokens).isNull()
        server.takeRequest()
        server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo("DELETE" to "/account")
            assertThat(it.body!!.utf8()).isEqualTo("""{"password":"secret"}""")
        }
    }

    @Test
    fun `deleting the account waits longer than the client's read timeout`() {
        // the server answers only after the user's games were handed to AI seats
        val impatient = ServerApi(server.url("/").toString(), store, okhttp3.OkHttpClient.Builder().readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS).build())
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        server.enqueue(MockResponse.Builder().code(204).headersDelay(1_500, java.util.concurrent.TimeUnit.MILLISECONDS).build())

        impatient.deleteAccount("secret")

        assertThat(store.tokens).isNull()
    }

    @Test
    fun `e-mail, forgot and reset, change password`() {
        server.enqueue(json(200, tokens("a1", "r1")))
        server.enqueue(json(200, """{"username":"alice","email":"a@example.org"}"""))
        repeat(2) { server.enqueue(MockResponse.Builder().code(204).build()) }
        server.enqueue(json(200, tokens("a2", "r2")))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(json(400, """{"error":"Wrong or expired code"}"""))
        server.enqueue(json(200, tokens("a3", "r3")))

        api.register("alice", "secret123", ageConfirmed = true, email = "a@example.org")
        assertThat(api.account().email).isEqualTo("a@example.org")
        api.requestEmail("b@example.org", "secret123")
        api.confirmEmail("123456")
        api.changePassword("secret123", "secret456")
        assertThat(store.tokens?.accessToken).isEqualTo("a2")
        store.tokens = null
        api.forgotPassword("alice")
        assertThatThrownBy { api.resetPassword("alice", "000000", "secret789") }.hasMessage("Wrong or expired code")
        assertThat(store.tokens).isNull()
        api.resetPassword("alice", "123456", "secret789")
        assertThat(store.tokens?.accessToken).isEqualTo("a3")

        fun next(method: String, path: String, body: String?, auth: String?) = server.takeRequest().let {
            assertThat(it.method to it.url.encodedPath).isEqualTo(method to path)
            if (body != null) assertThat(it.body!!.utf8()).isEqualTo(body)
            assertThat(it.headers["Authorization"]).isEqualTo(auth)
        }
        next("POST", "/auth/register", """{"username":"alice","password":"secret123","ageConfirmed":true,"email":"a@example.org"}""", null)
        next("GET", "/account", null, "Bearer a1")
        next("POST", "/account/email", """{"email":"b@example.org","password":"secret123"}""", "Bearer a1")
        next("POST", "/account/email/confirm", """{"code":"123456"}""", "Bearer a1")
        next("POST", "/account/password", """{"oldPassword":"secret123","newPassword":"secret456"}""", "Bearer a1")
        next("POST", "/auth/forgot", """{"login":"alice"}""", null)
        next("POST", "/auth/reset", """{"login":"alice","code":"000000","newPassword":"secret789"}""", null)
        next("POST", "/auth/reset", """{"login":"alice","code":"123456","newPassword":"secret789"}""", null)
    }

    @Test
    fun `profiles and replays`() {
        store.tokens = TokensDto("a1", "r1", 900, "alice")
        val profile = """{"username":"bob","icon":"flag:Germans","status":"hi","stats":{"played":2,"won":1,"lost":1,"winRate":0.5},""" +
            """"recentGames":[{"id":"g1","mapName":"minimap","gameName":"Minimap","nations":["Germans"],"result":"won","endedAt":5,"replay":true}]}"""
        server.enqueue(json(200, profile))
        server.enqueue(json(200, profile.replace("bob", "alice")))
        server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(4, 5))).build())

        val bob = api.profile("bob")
        assertThat(bob.stats.won).isEqualTo(1)
        assertThat(bob.recentGames.single().replay).isTrue()
        assertThat(api.updateProfile(icon = "unit:fighter").username).isEqualTo("alice")
        assertThat(api.downloadReplay("g1")).containsExactly(4, 5)

        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/users/bob/profile")
        val put = server.takeRequest()
        assertThat(put.method to put.url.encodedPath).isEqualTo("PUT" to "/account/profile")
        assertThat(put.body!!.utf8()).isEqualTo("""{"icon":"unit:fighter"}""")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/games/g1/replay")
    }

    private fun tokens(access: String, refresh: String) =
        """{"accessToken":"$access","refreshToken":"$refresh","expiresInSeconds":900,"username":"alice"}"""

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
}
