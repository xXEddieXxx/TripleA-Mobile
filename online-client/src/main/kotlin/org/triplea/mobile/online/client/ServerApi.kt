package org.triplea.mobile.online.client

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.triplea.mobile.protocol.AccountDto
import org.triplea.mobile.protocol.BlockRequest
import org.triplea.mobile.protocol.ChangePasswordRequest
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ChatReadRequest
import org.triplea.mobile.protocol.CreateGameRequest
import org.triplea.mobile.protocol.DefenderModeRequest
import org.triplea.mobile.protocol.DeleteAccountRequest
import org.triplea.mobile.protocol.DeviceRequest
import org.triplea.mobile.protocol.EmailConfirmRequest
import org.triplea.mobile.protocol.EmailRequest
import org.triplea.mobile.protocol.ErrorDto
import org.triplea.mobile.protocol.FeaturesDto
import org.triplea.mobile.protocol.ForgotPasswordRequest
import org.triplea.mobile.protocol.FriendDto
import org.triplea.mobile.protocol.FriendRequest
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.JoinRequest
import org.triplea.mobile.protocol.LeaderboardDto
import org.triplea.mobile.protocol.LeaveRequest
import org.triplea.mobile.protocol.LoginRequest
import org.triplea.mobile.protocol.NotificationsRequest
import org.triplea.mobile.protocol.ProfileDto
import org.triplea.mobile.protocol.ProfileUpdateRequest
import org.triplea.mobile.protocol.ProtocolJson
import org.triplea.mobile.protocol.RefreshRequest
import org.triplea.mobile.protocol.ReadyRequest
import org.triplea.mobile.protocol.RegisterRequest
import org.triplea.mobile.protocol.SeatUpdateRequest
import org.triplea.mobile.protocol.ReportRequest
import org.triplea.mobile.protocol.ResetPasswordRequest
import org.triplea.mobile.protocol.StandingOrdersDto
import org.triplea.mobile.protocol.TokensDto
import org.triplea.mobile.protocol.TurnsDto
import org.triplea.mobile.protocol.UpdateGameRequest
import org.triplea.mobile.protocol.VacationRequest

/** A non-2xx answer of the server; [message] is the server's ErrorDto text. */
class ServerException(val status: Int, message: String) : RuntimeException(message)

/**
 * The game server's REST API. Calls block; make them off the main thread. Authenticated calls
 * refresh the tokens once on a 401 and retry once.
 */
class ServerApi(baseUrl: String, private val tokens: TokenStore, internal val client: OkHttpClient = OkHttpClient()) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    val webSocketUrl: String = base.newBuilder().addPathSegment("ws").build().toString().replaceFirst("http", "ws")

    /** When the current access token expires, epoch ms; unknown (0) for tokens read back from the store. */
    @Volatile private var expiresAt = 0L

    /** The server refuses registration unless [ageConfirmed] (16 or older); an [email] gets a confirmation code ([confirmEmail]). */
    fun register(username: String, password: String, ageConfirmed: Boolean = false, email: String? = null): TokensDto =
        store(call(post("/auth/register", RegisterRequest.serializer(), RegisterRequest(username, password, ageConfirmed, email)), TokensDto.serializer()))

    /** Forgot password: mails a reset code to the confirmed address of username or e-mail [login]; the server answers the same for unknown accounts. */
    fun forgotPassword(login: String) {
        client.newCall(post("/auth/forgot", ForgotPasswordRequest.serializer(), ForgotPasswordRequest(login)).build()).execute().use { checked(it) }
    }

    /** Sets [newPassword] with the mailed [code] and logs in; every other session of the account ends. */
    fun resetPassword(login: String, code: String, newPassword: String): TokensDto =
        store(call(post("/auth/reset", ResetPasswordRequest.serializer(), ResetPasswordRequest(login, code, newPassword)), TokensDto.serializer()))

    fun account(): AccountDto = authed(AccountDto.serializer()) { get("/account") }

    /** Mails a code to [email]; the address replaces the account's once [confirmEmail] gets it. 403 for a wrong [password]. */
    fun requestEmail(email: String, password: String) = done { post("/account/email", EmailRequest.serializer(), EmailRequest(email, password)) }

    fun confirmEmail(code: String) = done { post("/account/email/confirm", EmailConfirmRequest.serializer(), EmailConfirmRequest(code)) }

    /** Battle decisions by e-mail on or off; on needs a confirmed address (400). */
    fun setNotifyByEmail(on: Boolean) = done { put("/account/notifications", NotificationsRequest.serializer(), NotificationsRequest(on)) }

    /** 403 for a wrong [oldPassword]. The server revokes every token, so the new pair is stored. */
    fun changePassword(oldPassword: String, newPassword: String): TokensDto =
        store(authed(TokensDto.serializer()) { post("/account/password", ChangePasswordRequest.serializer(), ChangePasswordRequest(oldPassword, newPassword)) })

    fun login(username: String, password: String): TokensDto =
        store(call(post("/auth/login", LoginRequest.serializer(), LoginRequest(username, password)), TokensDto.serializer()))

    fun logout() = tokens.clear()


    fun games(): List<GameInfoDto> = authed(ListSerializer(GameInfoDto.serializer())) { get("/games") }

    fun game(id: String): GameInfoDto = authed(GameInfoDto.serializer()) { get("/games/$id") }

    fun createGame(request: CreateGameRequest): GameInfoDto =
        authed(GameInfoDto.serializer()) { post("/games", CreateGameRequest.serializer(), request) }

    /** Lobby games the user may see (public, friends-only of a friend, and those they hold a seat in). */
    fun lobby(): List<GameInfoDto> = authed(ListSerializer(GameInfoDto.serializer())) { get("/lobby") }

    /** Takes the OPEN seat [nation] of lobby game [id]; link games need [inviteCode]. */
    fun join(id: String, nation: String, inviteCode: String? = null): GameInfoDto =
        authed(GameInfoDto.serializer()) { post("/games/$id/join", JoinRequest.serializer(), JoinRequest(nation, inviteCode)) }

    fun leave(id: String, nation: String) = done { post("/games/$id/leave", LeaveRequest.serializer(), LeaveRequest(nation)) }

    /** Sets Ready on all of the user's seats; the game starts (status running) once every human is ready. */
    fun ready(id: String, ready: Boolean): GameInfoDto =
        authed(GameInfoDto.serializer()) { post("/games/$id/ready", ReadyRequest.serializer(), ReadyRequest(ready)) }

    /** The creator sets who plays [nation] in lobby game [id] (HUMAN with a username invites a friend); every Ready is reset. */
    fun putSeat(id: String, nation: String, kind: String, username: String? = null): GameInfoDto =
        authed(GameInfoDto.serializer()) { put("/games/$id/seats/$nation", SeatUpdateRequest.serializer(), SeatUpdateRequest(kind, username)) }

    /** The creator changes the options of lobby game [id]; a null field stays. Every Ready is reset. */
    fun patchGame(id: String, update: UpdateGameRequest): GameInfoDto =
        authed(GameInfoDto.serializer()) {
            Request.Builder().url(url("/games/$id")).patch(ProtocolJson.json.encodeToString(UpdateGameRequest.serializer(), update).toRequestBody(JSON_TYPE))
        }

    /** What the server offers (no login needed): e-mail is false without SMTP. */
    fun features(): FeaturesDto = call(get("/server/features"), FeaturesDto.serializer())

    /** Deletes the creator's lobby game. */
    fun deleteGame(id: String) = done { get("/games/$id").delete() }

    /** The open questions for the user's seats and their unread private messages (the turn notification polls it). */
    fun turns(): TurnsDto = authed(TurnsDto.serializer()) { get("/me/turns") }

    /** This device's push token for the logged-in user (after login and on every new token). */
    fun registerDevice(token: String) = done { put("/me/devices", DeviceRequest.serializer(), DeviceRequest(token)) }

    /** Logout: the server forgets this device's token. */
    fun unregisterDevice(token: String) = done { Request.Builder().url(base.newBuilder().addPathSegments("me/devices").addPathSegment(token).build()).delete() }

    fun orders(id: String): StandingOrdersDto = authed(StandingOrdersDto.serializer()) { get("/games/$id/orders") }

    /** Replaces the user's standing orders in game [id]; its defenderMode is ignored, see [setDefenderMode]. */
    fun setOrders(id: String, orders: StandingOrdersDto) = done { put("/games/$id/orders", StandingOrdersDto.serializer(), orders) }

    /** [mode]: live, hybrid or standing. */
    fun setDefenderMode(id: String, mode: String) = done { put("/games/$id/defender-mode", DefenderModeRequest.serializer(), DefenderModeRequest(mode)) }

    /** Correspondence games: the user's clock stops for [days] (at most 14 per game). */
    fun vacation(id: String, days: Int) = done { post("/games/$id/vacation", VacationRequest.serializer(), VacationRequest(days)) }

    /** "I'm back": the seat the AI plays since the user's clock ran out goes back to them. */
    fun back(id: String) = done { Request.Builder().url(url("/games/$id/back")).post(ByteArray(0).toRequestBody()) }

    /**
     * Gives up a running game: the AI keeps all of the user's seats for good. Once no team but one has a player left,
     * that team wins; once no player is left in a game against the AI, it ends without a winner.
     */
    fun resign(id: String) = done { Request.Builder().url(url("/games/$id/resign")).post(ByteArray(0).toRequestBody()) }

    fun friends(): List<FriendDto> = authed(ListSerializer(FriendDto.serializer())) { get("/friends") }

    /** Asks [username] to be friends; asking back accepts their request. */
    fun requestFriend(username: String) = done { post("/friends/requests", FriendRequest.serializer(), FriendRequest(username)) }

    fun acceptFriend(username: String) = done { Request.Builder().url(url("/friends/$username/accept")).post(ByteArray(0).toRequestBody()) }

    /** Ends a friendship, withdraws or declines a request. */
    fun removeFriend(username: String) = done { get("/friends/$username").delete() }

    fun blocks(): List<String> = authed(ListSerializer(String.serializer())) { get("/blocks") }

    fun block(username: String) = done { post("/blocks", BlockRequest.serializer(), BlockRequest(username)) }

    fun unblock(username: String) = done { get("/blocks/$username").delete() }

    fun report(username: String, reason: String, chatMessageId: Long? = null) =
        done { post("/reports", ReportRequest.serializer(), ReportRequest(username, reason, chatMessageId)) }

    /** Recent chat history of [channel] (`lobby`, `game:<id>` or `dm:<a>:<b>`), newest first. */
    fun chat(channel: String): List<ChatMessage> = authed(ListSerializer(ChatMessage.serializer())) { get("/chat/$channel") }

    /** The user has read [channel] up to message [lastReadId]; private messages after it count as unread. */
    fun markRead(channel: String, lastReadId: Long) =
        done { post("/chat/$channel/read", ChatReadRequest.serializer(), ChatReadRequest(lastReadId)) }

    /**
     * Deletes the account after checking [password] (403 if wrong), then forgets the tokens. The server
     * answers only after the user's running games have been stopped and resumed with AI seats, which can
     * take minutes: this call waits up to [DELETE_TIMEOUT_MINUTES] instead of the usual 10 s.
     */
    fun deleteAccount(password: String) {
        done(slowClient) { Request.Builder().url(url("/account")).delete(ProtocolJson.json.encodeToString(DeleteAccountRequest.serializer(), DeleteAccountRequest(password)).toRequestBody(JSON_TYPE)) }
        tokens.clear()
    }

    /**
     * The latest save of game [id] and its version (header X-Game-Version). With [spectate] the one spectators
     * may see: [version] if given (404 once the server no longer keeps it), else the newest allowed.
     */
    fun downloadSave(id: String, spectate: Boolean = false, version: Long? = null): Pair<Long, ByteArray> =
        authed({ response ->
            val saved = response.header("X-Game-Version")?.toLongOrNull() ?: throw ServerException(response.code, "save without a version")
            saved to boundedBytes(response)
        }) {
            val url = url("/games/$id/save").newBuilder().apply {
                if (spectate) addQueryParameter("spectate", "true")
                if (spectate && version != null) addQueryParameter("version", version.toString())
            }
            Request.Builder().url(url.build())
        }

    /** The final save of ended game [id] (gzipped like [downloadSave]); 404 when the caller may not see it or it is no longer kept. */
    fun downloadReplay(id: String): ByteArray = authed({ boundedBytes(it) }) { get("/games/$id/replay") }

    /** Any user's profile with stats and recent games; 404 for unknown or deleted users. */
    fun profile(username: String): ProfileDto = authed(ProfileDto.serializer()) { get("/users/$username/profile") }

    /** Sets the caller's icon (from the allow-list) and status; null keeps one. Answers the caller's profile; 400 for a refused value. */
    fun updateProfile(icon: String? = null, status: String? = null): ProfileDto =
        authed(ProfileDto.serializer()) { put("/account/profile", ProfileUpdateRequest.serializer(), ProfileUpdateRequest(icon, status)) }

    /** "Top players": the 50 highest ratings of players with at least 5 rated games. */
    fun leaderboard(): LeaderboardDto = authed(LeaderboardDto.serializer()) { get("/leaderboard") }

    /** The access token for the WebSocket; refreshed first when it is known to expire within a minute. */
    fun accessToken(): String {
        val current = tokens.tokens ?: throw ServerException(401, LOG_IN_AGAIN)
        if (expiresAt != 0L && System.currentTimeMillis() > expiresAt - 60_000) return refresh(current.accessToken).accessToken
        return current.accessToken
    }

    /**
     * New tokens after [usedAccessToken] was refused. When another thread renewed them meanwhile,
     * those are returned; a failed refresh logs the user out.
     */
    @Synchronized
    internal fun refresh(usedAccessToken: String): TokensDto {
        val current = tokens.tokens ?: throw ServerException(401, LOG_IN_AGAIN)
        if (current.accessToken != usedAccessToken) return current
        return try {
            store(call(post("/auth/refresh", RefreshRequest.serializer(), RefreshRequest(current.refreshToken)), TokensDto.serializer()))
        } catch (e: ServerException) {
            // only a refused refresh token logs out; a server hiccup (5xx, 429) keeps the tokens
            if (e.status !in setOf(400, 401, 403)) throw e
            tokens.clear()
            throw ServerException(401, LOG_IN_AGAIN)
        }
    }

    private fun store(issued: TokensDto): TokensDto {
        tokens.tokens = issued
        expiresAt = System.currentTimeMillis() + issued.expiresInSeconds * 1000
        return issued
    }

    private fun <T> authed(serializer: KSerializer<T>, request: () -> Request.Builder): T =
        authed({ ProtocolJson.json.decodeFromString(serializer, it.body.string()) }, request = request)

    private val slowClient by lazy {
        client.newBuilder().readTimeout(DELETE_TIMEOUT_MINUTES, TimeUnit.MINUTES).callTimeout(DELETE_TIMEOUT_MINUTES, TimeUnit.MINUTES).build()
    }

    /** An authenticated call whose answer has no body (204). */
    private fun done(http: OkHttpClient = client, request: () -> Request.Builder) = authed({ }, http, request)

    private fun <T> authed(read: (Response) -> T, http: OkHttpClient = client, request: () -> Request.Builder): T {
        val token = tokens.tokens?.accessToken ?: throw ServerException(401, LOG_IN_AGAIN)
        http.newCall(request().header("Authorization", "Bearer $token").build()).execute().use { response ->
            if (response.code != 401) return read(checked(response))
        }
        val renewed = refresh(token)
        return http.newCall(request().header("Authorization", "Bearer ${renewed.accessToken}").build()).execute().use { read(checked(it)) }
    }

    private fun <T> call(request: Request.Builder, serializer: KSerializer<T>): T =
        client.newCall(request.build()).execute().use { ProtocolJson.json.decodeFromString(serializer, checked(it).body.string()) }

    private fun boundedBytes(response: Response): ByteArray {
        if (response.body.contentLength() > MAX_SAVE_BYTES) throw IOException("save too large")
        val source = response.body.source()
        if (source.request(MAX_SAVE_BYTES + 1)) throw IOException("save too large")
        return source.readByteArray()
    }

    private fun checked(response: Response): Response {
        if (response.isSuccessful) return response
        val text = response.body.string()
        val message = runCatching { ProtocolJson.json.decodeFromString(ErrorDto.serializer(), text).error }.getOrNull() ?: "HTTP ${response.code}"
        throw ServerException(response.code, message)
    }

    private fun get(path: String) = Request.Builder().url(url(path))

    private fun <T> post(path: String, serializer: KSerializer<T>, body: T) =
        Request.Builder().url(url(path)).post(ProtocolJson.json.encodeToString(serializer, body).toRequestBody(JSON_TYPE))

    private fun <T> put(path: String, serializer: KSerializer<T>, body: T) =
        Request.Builder().url(url(path)).put(ProtocolJson.json.encodeToString(serializer, body).toRequestBody(JSON_TYPE))

    private fun url(path: String) = base.newBuilder().addPathSegments(path.removePrefix("/")).build()

    private companion object {
        const val LOG_IN_AGAIN = "please log in again"
        const val MAX_SAVE_BYTES = 64L * 1024 * 1024
        const val DELETE_TIMEOUT_MINUTES = 3L
        val JSON_TYPE = "application/json".toMediaType()
    }
}
