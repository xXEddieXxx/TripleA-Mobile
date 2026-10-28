package org.triplea.mobile.app.online

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.triplea.mobile.app.AppServices
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.game.GameController
import org.triplea.mobile.online.client.GameConnection
import org.triplea.mobile.online.client.ServerApi
import org.triplea.mobile.online.client.SocialConnection
import org.triplea.mobile.online.client.mergeChat
import org.triplea.mobile.protocol.ChatError
import org.triplea.mobile.protocol.ChatMessage
import org.triplea.mobile.protocol.ChatSend
import org.triplea.mobile.protocol.Clock
import org.triplea.mobile.protocol.DefenderNotice
import org.triplea.mobile.protocol.DefenderNoticeClosed
import org.triplea.mobile.protocol.FriendDto
import org.triplea.mobile.protocol.FriendsChanged
import org.triplea.mobile.protocol.GameInfoDto
import org.triplea.mobile.protocol.LobbyUpdate
import org.triplea.mobile.protocol.Presence
import org.triplea.mobile.protocol.ServerMessage

/**
 * The logged-in user's social state (debug builds' online mode): chat per channel, friends' presence
 * a counter that bumps on every lobby change, and the open defender notices (the banner above every screen).
 * Open while the Online screen or an online game is shown.
 */
object Social {
    @Volatile var api: ServerApi? = null
        private set
    @Volatile private var connection: SocialConnection? = null

    /** Chat per channel (`lobby`, `game:<id>`), sorted by id: the server may deliver ids out of order. */
    private val _chat = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chat = _chat.asStateFlow()

    /** Friends whose Presence said online / offline since the last friends list was loaded. */
    private val _presence = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val presence = _presence.asStateFlow()

    /** Bumped on every LobbyUpdate (except of a running game) and FriendsChanged: the screens reload their lists. */
    private val _lobbyVersion = MutableStateFlow(0)
    val lobbyVersion = _lobbyVersion.asStateFlow()

    /** A running game's LobbyUpdate (the server sends one when the turn passes): the Online screen replaces its card. */
    private val _gameUpdates = MutableSharedFlow<GameInfoDto>(extraBufferCapacity = 64)
    val gameUpdates = _gameUpdates.asSharedFlow()

    /** The server's clock minus the phone's, learned from a running turn clock in a received game; 0 until one is seen. */
    @Volatile var clockSkewMs = 0L
        private set

    /** Learns [clockSkewMs] from [games] just received: a running clock's deadline minus its rest is the server's now. */
    fun learnSkew(games: List<GameInfoDto>) {
        val at = System.currentTimeMillis()
        // an expired clock (rest 0) would understate it; a vacation (deadline beyond now plus rest) overstates it, so the smallest wins
        // shortcut: a sample over 1 h off is taken for a vacation (its deadline moves by days), so a phone clock more than 1 h wrong stays uncorrected
        games.filter { it.turnDeadline != null && (it.turnRemainingMs ?: 0) > 0 }
            .map { it.turnDeadline!! - it.turnRemainingMs!! - at }
            .filter { abs(it) <= MAX_SKEW_MS }
            .minOrNull()
            ?.let { clockSkewMs = it }
    }

    /** The last refused send; channel "" when the socket stopped for good (shown in every chat). */
    private val _chatError = MutableStateFlow<ChatError?>(null)
    val chatError = _chatError.asStateFlow()

    /** Users this user blocks; their messages are hidden at once (the server stops sending new ones). */
    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked = _blocked.asStateFlow()

    /** Unread private messages per `dm:` channel: the friends list's counts plus live messages since. */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread = _unread.asStateFlow()

    /** Each accepted friend's private chat channel, from the last friends list; null until one is loaded. */
    private val _channels = MutableStateFlow<Map<String, String>?>(null)
    val channels = _channels.asStateFlow()

    /** The latest Clock per game id (games with a time control); the server sends one on every change. */
    private val _clocks = MutableStateFlow<Map<String, Clock>>(emptyMap())
    val clocks = _clocks.asStateFlow()

    /**
     * Open defender notices by (game id, question id), from DefenderNotice until DefenderNoticeClosed; the banner above
     * every screen shows them. The server may send one twice; a close for an unknown one changes nothing.
     */
    private val _notices = MutableStateFlow<Map<Pair<String, Long>, DefenderNotice>>(emptyMap())
    val notices = _notices.asStateFlow()

    /**
     * Notices that already rang: a reconnect or a new [start] re-sends the open ones, which must not ring again.
     * Kept across [stop] too: the same user logging in again is not a new notice.
     * shortcut: a key whose close was missed while the socket was down stays until the process ends (a few longs per battle).
     */
    private val alerted: MutableSet<Pair<String, Long>> = ConcurrentHashMap.newKeySet()

    /** Opens the social socket for [server]; a no-op when it is already open for it. While [paused], [resume] opens it. */
    @Synchronized
    fun start(server: ServerApi) {
        if (api === server) return
        stop()
        api = server
        if (!paused) connect(server)
    }

    private fun connect(server: ServerApi) {
        lateinit var opened: SocialConnection
        // messages still in flight from a replaced connection are dropped
        opened = SocialConnection(
            server,
            { if (connection === opened) received(it) },
            { reason -> stopped(opened, reason) },
            // the server re-sends the open ones after the Hello; one closed while the socket was down disappears
            { if (connection === opened) _notices.value = emptyMap() },
        )
        connection = opened.also { it.open() }
    }

    // between background() + AWAY_DELAY_MS and foreground(): the social and the game socket are closed
    private var paused = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val away = Runnable { pause() }

    /**
     * The app is no longer seen (MainActivity stopped). Shortly after, both sockets close, so the server counts the player
     * offline: turns and battle decisions come by push, with the offline window. Short of Android's freezer (about 10 s),
     * which would hold the timer.
     * shortcut: the delay counts uptime, which stops while the phone sleeps; the server's ping timeout (about 45 s) then closes
     * the sockets instead. Upgrade to an alarm if players report the short online window after locking the phone.
     */
    fun background() {
        main.postDelayed(away, AWAY_DELAY_MS)
    }

    /** Seen again: the sockets reconnect; the server re-sends the open question, notices and clocks. */
    fun foreground() {
        main.removeCallbacks(away)
        resume()
    }

    @Synchronized
    private fun pause() {
        paused = true
        connection?.pause()
        // the server re-sends the open ones on reconnect; until then the banner shows none that may have closed meanwhile
        _notices.value = emptyMap()
        (GameController.session as? OnlineSession)?.connection?.pause()
    }

    /** A game started while the app is away pauses too; under the lock, so a [resume] in between cannot miss it. */
    @Synchronized
    fun pauseIfAway(game: GameConnection) {
        if (paused) game.pause()
    }

    @Synchronized
    private fun resume() {
        if (!paused) return
        paused = false
        (GameController.session as? OnlineSession)?.connection?.resume()
        val server = api ?: return
        // a start() while away has not opened one yet
        connection?.resume() ?: connect(server)
        // lobby changes and chat sent while away were missed: the lists reload, every chat loaded since login its newest page
        _lobbyVersion.update { it + 1 }
        val channels = _chat.value.keys
        val started = generation
        if (channels.isNotEmpty()) thread(isDaemon = true, name = "chat-catch-up") {
            channels.forEach { channel ->
                runCatching { server.chat(channel) }.onSuccess { page ->
                    // a stop() (log out) meanwhile: the page belongs to the old session
                    synchronized(this) { if (generation == started) add(channel, page) }
                }
            }
        }
    }

    /** The socket gave up (login gone, app too old): forget it, so the next [start] opens a new one. */
    @Synchronized
    private fun stopped(gone: SocialConnection, reason: String) {
        if (connection !== gone) return
        connection = null
        api = null
        _notices.value = emptyMap()
        _chatError.value = ChatError("", "Chat disconnected: $reason")
    }

    /** The server of the open social socket; throws when there is none, so callers show an error instead of doing nothing. */
    fun requireApi(): ServerApi = api ?: error("Not connected to the server")

    /** Bumped by every [stop]: a blocking load that started before one must not write into the new session. */
    @Volatile private var generation = 0

    @Synchronized
    fun stop() {
        generation++
        connection?.close()
        connection = null
        api = null
        _chat.value = emptyMap()
        _presence.value = emptyMap()
        _blocked.value = emptySet()
        _chatError.value = null
        _clocks.value = emptyMap()
        _notices.value = emptyMap()
        clockSkewMs = 0
        _unread.value = emptyMap()
        _channels.value = null
    }

    private fun received(message: ServerMessage) {
        when (message) {
            is ChatMessage -> {
                add(message.channel, listOf(message))
                if (message.channel.startsWith("dm:") && message.username != AppSettings.tokens.tokens?.username) {
                    _unread.update { it + (message.channel to (it[message.channel] ?: 0) + 1) }
                }
            }
            is ChatError -> _chatError.value = message
            is Presence -> _presence.update { it + (message.username to message.online) }
            is LobbyUpdate -> if (message.game.status == "running" && !message.deleted) {
                learnSkew(listOf(message.game))
                _gameUpdates.tryEmit(message.game)
            } else {
                _lobbyVersion.update { it + 1 }
            }
            // the Online screen's reload takes the friends list too
            is FriendsChanged -> _lobbyVersion.update { it + 1 }
            is Clock -> _clocks.update { it + (message.gameId to message) }
            is DefenderNotice -> {
                val key = message.gameId to message.questionId
                _notices.update { it + (key to message) }
                // once per question, also across reconnects; in the background the push does it
                if (alerted.add(key) && Notifications.inForeground()) Notifications.alert(AppServices.appContext)
            }
            is DefenderNoticeClosed -> {
                val key = message.gameId to message.questionId
                alerted.remove(key)
                _notices.update { it - key }
            }
            else -> {}
        }
    }

    fun add(channel: String, messages: List<ChatMessage>) =
        _chat.update { chats -> chats + (channel to mergeChat(chats[channel].orEmpty(), messages, MAX_MESSAGES)) }

    /** Blocking: loads the newest page of [channel]'s history. */
    fun loadHistory(channel: String) {
        val server = api ?: return
        add(channel, server.chat(channel))
    }

    /** Blocking: reloads the block list. */
    fun loadBlocks() {
        _blocked.value = (api ?: return).blocks().toSet()
    }

    /**
     * A fresh friends list: its channels and unread counts replace the live ones.
     * shortcut: a message landing between the server's count and this call is lost from the badge until the next reload.
     */
    fun friendsLoaded(friends: List<FriendDto>) {
        val accepted = friends.filter { it.channel != null }
        _channels.value = accepted.associate { it.username to it.channel!! }
        _unread.value = accepted.associate { it.channel!! to it.unread }
    }

    /**
     * Blocking: opens the socket for [server] and loads the friends list unless one is loaded (a direct route,
     * process death), or always with [refresh]. A [stop] during the fetch drops its answer.
     */
    fun loadFriends(server: ServerApi, refresh: Boolean = false) {
        start(server)
        val started = generation
        if (!refresh && _channels.value != null) return
        val friends = server.friends()
        synchronized(this) { if (generation == started) friendsLoaded(friends) }
    }

    /**
     * Blocking: [channel] is read up to its newest loaded message ([lastId]); its badge clears at once, and the
     * turn check counts that friend's next messages from zero.
     */
    fun markRead(channel: String, lastId: Long) {
        _unread.update { it - channel }
        (api ?: return).markRead(channel, lastId)
        // only once the server has it: otherwise the next check would count the still unread messages as new
        _channels.value?.entries?.firstOrNull { it.value == channel }?.let { TurnCheckWorker.markRead(AppServices.appContext, it.key) }
    }

    /** False when the socket is not connected. */
    fun send(channel: String, text: String): Boolean {
        if (_chatError.value?.channel == channel) _chatError.value = null
        return connection?.send(ChatSend(channel, text)) == true
    }

    /** Presence is only sent on changes: call before fetching the friends list, so a later Presence still wins. */
    fun resetPresence() {
        _presence.value = emptyMap()
    }

    private const val MAX_MESSAGES = 200
    private const val MAX_SKEW_MS = 60 * 60_000L
    private const val AWAY_DELAY_MS = 5_000L
}
