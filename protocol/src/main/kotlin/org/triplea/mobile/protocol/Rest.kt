package org.triplea.mobile.protocol

import kotlinx.serialization.Serializable

// REST bodies between the app and the game server (accounts, maps, games, saves).

/** v3: [email] is optional; when given, the server mails a confirmation code to it (see [EmailConfirmRequest]). */
@Serializable
data class RegisterRequest(val username: String, val password: String, val ageConfirmed: Boolean = false, val email: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokensDto(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val username: String)

/** A game of an installed map; [nations] in the game's player order. */
@Serializable
data class MapGameDto(val mapName: String, val gameName: String, val nations: List<String>)

/**
 * [kind]: HUMAN, OPEN (a human seat nobody holds yet), EASY_AI, FAST_AI, HARD_AI or DOES_NOTHING;
 * [username] is set for HUMAN, and for an AI seat that plays for its player (their clock ran out).
 * [ready]: the holder pressed Ready (lobby games). [resigned]: the player left the game (gave up, missed too many
 * turns in a row or deleted the account); the AI keeps the seat for good.
 * [team] (v3, running and ended games): the nation's alliance at the start (its own name without one); seats of one
 * team win or lose together.
 */
@Serializable
data class SeatDto(
    val nation: String,
    val kind: String,
    val username: String? = null,
    val ready: Boolean = false,
    val resigned: Boolean = false,
    val team: String? = null,
)

/**
 * [type]: blitz (base 3 to 10 minutes, increment 0 to 30 s per answered question), rapid (15 to 60
 * minutes, 0 to 60 s) or correspondence ([baseMs] of 1 to 14 days per turn, reset at the start of each
 * of the player's turns; no increment, with vacation mode).
 */
@Serializable
data class TimeControlDto(val type: String, val baseMs: Long, val incrementMs: Long = 0)

/**
 * HUMAN seats with a username are invitations (filled, not ready), OPEN seats wait for someone to join.
 * [visibility]: public (everyone's lobby), friends (the creator's friends) or link (only with the invite code).
 * [timeControl]: null for no clocks. When a clock runs out the phase ends; with [aiTakesOver] the AI
 * then plays the seat until the player returns, and after [resignAfterMissedTurns] missed turns in a
 * row (0: never) the player is resigned and the AI keeps the seat. With [allowSpectators] users who can see
 * the game may watch it while it runs, with a delay.
 * v3: [rated] games change the players' Elo ratings when they finish; they need a [timeControl] (else 400) and, at
 * Ready, human players on two teams (else 409).
 */
@Serializable
data class CreateGameRequest(
    val mapName: String,
    val gameName: String,
    val seats: List<SeatDto>,
    val visibility: String = "link",
    val timeControl: TimeControlDto? = null,
    val aiTakesOver: Boolean = true,
    val resignAfterMissedTurns: Int = 3,
    val allowSpectators: Boolean = true,
    val rated: Boolean = false,
)

/** Correspondence games: [days] of vacation (at most 14 per game in total); the player's clock does not run meanwhile. */
@Serializable
data class VacationRequest(val days: Int)

/**
 * A player's standing orders in one game: the answers out of turn when they are not asked (null: the
 * engine default answer). [casualties]: cheapest_first (by production cost) or aircraft_last;
 * [scramble] and [retreat]: never; [offers] (political and user-action offers): decline or ask. never
 * and decline answer at once, also in hybrid mode; ask always asks, as in live mode. [defenderMode]:
 * live, hybrid or standing; only read here, PUT /games/{id}/defender-mode sets it.
 */
@Serializable
data class StandingOrdersDto(
    val casualties: String? = null,
    val scramble: String? = null,
    val offers: String? = null,
    val retreat: String? = null,
    val defenderMode: String = "hybrid",
)

/**
 * How out-of-turn questions reach the player in one game. [mode]: live (always asked, no clock), hybrid
 * (asked while they are online, within a 60 s window on their own clock; otherwise the standing order
 * answers) or standing (the standing order answers at once).
 */
@Serializable
data class DefenderModeRequest(val mode: String)

/**
 * GET /me/turns: an open question for one of the caller's seats, so the app can tell them it is their
 * turn without a connection. [questionId] changes with every question; [deadline] as in Question.
 */
@Serializable
data class TurnDto(val gameId: String, val questionId: Long, val nation: String, val deadline: Long? = null)

/** GET /me/turns (v3): the open questions for the caller's seats and the number of unread private messages. */
@Serializable
data class TurnsDto(val turns: List<TurnDto>, val unreadDirect: Int = 0)

/**
 * [status]: lobby, running, finished or aborted; [version] is the number of the latest save.
 * [inviteCode] is set for link games, and only for their seat holders. [creator] is the creator's
 * username (only they may delete a lobby game; everyone else leaves it). [timeControl]: null without clocks.
 * [mapReady] (v3) is false while the server downloads the game's map; Ready is refused until then.
 * [error] (v3) says why an aborted game was aborted, e.g. "Map could not be downloaded: …"; null otherwise.
 * [aiTakesOver], [resignAfterMissedTurns] and [allowSpectators] (v3) as in [CreateGameRequest].
 * v3, running games only (null otherwise): [turn] is who is to move: the seat of the open question (e.g. a defender in a
 * battle), else the current step's player; [turnRemainingMs] their remaining clock time and
 * [turnDeadline] (epoch ms) when it runs out, while it runs (both null without a time control); [turnSince] (epoch ms)
 * when the current turn started.
 * [vsAi] (v3, running and ended games): every human started on one team, so they play together against the AI; such
 * games count in [ProfileDto.aiStats], not in the player-versus-player [ProfileDto.stats].
 * [rated] (v3) as in [CreateGameRequest]; [ratingChanges] (v3, finished rated games only): username → change of their
 * rating by this game.
 * [unlocks] (v3, finished games): username → what this game unlocked for them: the badge ids awarded at its finish
 * ([Achievements]), then the icon ids whose tier its win with a nation reached ([IconUnlocks]).
 */
@Serializable
data class GameInfoDto(
    val id: String,
    val mapName: String,
    val gameName: String,
    val status: String,
    val seats: List<SeatDto>,
    val version: Long,
    val round: Int,
    val step: String?,
    val currentPlayer: String?,
    val winners: List<String> = emptyList(),
    val visibility: String = "link",
    val inviteCode: String? = null,
    val creator: String = "",
    val timeControl: TimeControlDto? = null,
    val mapReady: Boolean = true,
    val error: String? = null,
    val aiTakesOver: Boolean = true,
    val resignAfterMissedTurns: Int = 3,
    val allowSpectators: Boolean = true,
    val turn: TurnInfoDto? = null,
    val turnRemainingMs: Long? = null,
    val turnDeadline: Long? = null,
    val turnSince: Long? = null,
    val vsAi: Boolean = false,
    val rated: Boolean = false,
    val ratingChanges: Map<String, Int> = emptyMap(),
    val unlocks: Map<String, List<String>> = emptyMap(),
)

/** v3: the player to move in a running game: [nation], its player's [username] (null if none) and whether the [ai] plays it. */
@Serializable
data class TurnInfoDto(val nation: String, val username: String? = null, val ai: Boolean = false)

/**
 * PATCH /games/{id} (v3, creator, lobby only): the options to change; null keeps one. [noTimeControl]
 * removes the clocks (then [timeControl] must be null). Every change resets everyone's Ready.
 * [rated]: true needs a time control after the change (400 otherwise).
 */
@Serializable
data class UpdateGameRequest(
    val visibility: String? = null,
    val timeControl: TimeControlDto? = null,
    val noTimeControl: Boolean = false,
    val aiTakesOver: Boolean? = null,
    val resignAfterMissedTurns: Int? = null,
    val allowSpectators: Boolean? = null,
    val rated: Boolean? = null,
)

/**
 * PUT /games/{id}/seats/{nation} (v3, creator, lobby only): who plays that nation; [kind] as in [CreateGameRequest]
 * ([username] for HUMAN). The other seats stay; every Ready is reset.
 */
@Serializable
data class SeatUpdateRequest(val kind: String, val username: String? = null)

/** POST /chat/{channel}/read (v3): the caller has read [channel] up to message [lastReadId]. */
@Serializable
data class ChatReadRequest(val lastReadId: Long)

/** Takes OPEN seat [nation] of a lobby game; [inviteCode] is needed for link games. */
@Serializable
data class JoinRequest(val nation: String, val inviteCode: String? = null)

/** Gives up seat [nation] of a lobby game; it becomes OPEN. */
@Serializable
data class LeaveRequest(val nation: String)

/**
 * Sets Ready on all of the caller's seats in a lobby game; the game starts when every human is ready. v3: `ready: true`
 * gets 409 while fewer than 2 human players are planned (distinct seat holders plus OPEN seats).
 */
@Serializable
data class ReadyRequest(val ready: Boolean)

@Serializable
data class ErrorDto(val error: String)

/**
 * [status]: accepted, incoming (they asked) or outgoing (you asked); [online] only for accepted friends.
 * v3, for accepted friends only: [unread] private messages from them, [activity] ("playing <game> · round N"
 * while they are online with a running game, else null), [lastSeen] (epoch ms their last connection
 * closed; null if never) and [channel], the private chat with them (`dm:<smaller user id>:<larger user id>`).
 */
@Serializable
data class FriendDto(
    val username: String,
    val status: String,
    val online: Boolean,
    val unread: Int = 0,
    val activity: String? = null,
    val lastSeen: Long? = null,
    val channel: String? = null,
)

@Serializable
data class FriendRequest(val username: String)

@Serializable
data class BlockRequest(val username: String)

/** [reason] 1 to 1000 characters; [chatMessageId] when a chat message is reported. */
@Serializable
data class ReportRequest(val username: String, val reason: String, val chatMessageId: Long? = null)

@Serializable
data class DeleteAccountRequest(val password: String)

/** v3, `GET /server/features`: [email] is false when the server cannot send mail (no confirmation, no password reset). */
@Serializable
data class FeaturesDto(val email: Boolean)

/** v3, `GET /account`: the caller's account; [email] is the confirmed address, null if none; [notifyByEmail]: open battle decisions are mailed. */
@Serializable
data class AccountDto(val username: String, val email: String? = null, val notifyByEmail: Boolean = false)

/** v3, `PUT /me/devices`: registers or refreshes this device's push [token] for the caller; `DELETE /me/devices/{token}` removes it. */
@Serializable
data class DeviceRequest(val token: String, val platform: String = "android")

/** v3, `PUT /account/notifications`: battle decisions by e-mail; true needs a confirmed address (400 otherwise). */
@Serializable
data class NotificationsRequest(val notifyByEmail: Boolean)

/**
 * v3, `POST /account/email`: mails a 6-digit code to [email]; the address is stored once the code is confirmed.
 * [password] is the account's current one (403 if wrong): a stolen token alone cannot redirect the reset mails.
 */
@Serializable
data class EmailRequest(val email: String, val password: String)

/** v3, `POST /account/email/confirm`. */
@Serializable
data class EmailConfirmRequest(val code: String)

/** v3, `POST /auth/forgot`: [login] is a username or a confirmed e-mail address. */
@Serializable
data class ForgotPasswordRequest(val login: String)

/** v3, `POST /auth/reset`: the code from the forgot-password mail; answered with new tokens. */
@Serializable
data class ResetPasswordRequest(val login: String, val code: String, val newPassword: String)

/** v3, `POST /account/password`: answered with new tokens; every older token of the account is revoked. */
@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

/**
 * v3, `GET /users/{username}/profile`: [icon] is an id of the icon allow-list (`profile-icons.txt`, e.g.
 * `flag:Germans`, `unit:fighter`, default `emblem:default`); [status] 0 to 60 characters. [recentGames]: at most 10
 * ended games, newest first, only those the caller may replay. [stats]: games against other players only;
 * [aiStats]: games where all humans played together against the AI ([GameInfoDto.vsAi]), kept apart so wins against
 * the AI do not raise the win rate. [rating] (v3): the Elo rating (1200 at the start); [ratedGames]: finished rated
 * games (provisional while fewer than 20). [achievements] (v3): the badges earned, oldest first; [winsByNation]: wins with
 * each of [Achievements.NATIONS] (all eight, zeros included), which unlock the nation's unit icons ([IconUnlocks]); null
 * from an older server (the app then treats every icon as unlocked). [maps] (v3): one entry per scenario of
 * [Achievements.SCENARIOS] on which the user finished a game (absent: not played).
 */
@Serializable
data class ProfileDto(
    val username: String,
    val icon: String,
    val status: String,
    val stats: StatsDto,
    val recentGames: List<RecentGameDto> = emptyList(),
    val aiStats: StatsDto = StatsDto(),
    val rating: Int = 1200,
    val ratedGames: Int = 0,
    val achievements: List<AchievementDto> = emptyList(),
    val winsByNation: Map<String, Int>? = null,
    val maps: List<MapProgressDto> = emptyList(),
)

/** v3: a badge of [Achievements.catalog] a user earned, and when ([unlockedAt], epoch ms). */
@Serializable
data class AchievementDto(val id: String, val unlockedAt: Long)

/**
 * v3: a user's progress on scenario [key] ([Scenario.key]): [wins] games won there and the nations of [Scenario.nations]
 * they won with ([wonWith], sorted). The scenario's full nation list comes from the catalog.
 */
@Serializable
data class MapProgressDto(val key: String, val wins: Int, val wonWith: List<String>)

/**
 * v3: counted over the games the user held a seat in that ended after they started: [played] = [won] + [lost] +
 * [resigned] + [abandoned] (aborted after the start) + [draws] (finished without a winner). A resignation counts as
 * resigned even if the user's side won later. [winRate] is won / (won + lost + resigned), 0 without any.
 * [mostPlayedMap] and [mostPlayedNation]: null without games.
 */
@Serializable
data class StatsDto(
    val played: Int = 0,
    val won: Int = 0,
    val lost: Int = 0,
    val resigned: Int = 0,
    val abandoned: Int = 0,
    val winRate: Double = 0.0,
    val mostPlayedMap: String? = null,
    val mostPlayedNation: String? = null,
    val draws: Int = 0,
)

/**
 * v3: an ended game of a profile. [nations]: the ones the user played; [result]: won, lost, resigned, abandoned or draw;
 * [endedAt] epoch ms; [replay]: its final save is still kept (`GET /games/{id}/replay`, 365 days after the end);
 * [vsAi]: a game against the AI ([GameInfoDto.vsAi]). [ratingChange]: the change of the user's rating by this game,
 * null for an unrated one.
 */
@Serializable
data class RecentGameDto(
    val id: String,
    val mapName: String,
    val gameName: String,
    val nations: List<String>,
    val result: String,
    val endedAt: Long,
    val replay: Boolean,
    val vsAi: Boolean = false,
    val ratingChange: Int? = null,
)

/** v3, `PUT /account/profile`: null keeps a field. [icon] must be on the allow-list (else 400); [status] is trimmed, 0 to 60 characters, no control characters. */
@Serializable
data class ProfileUpdateRequest(val icon: String? = null, val status: String? = null)

/** v3, `GET /leaderboard`: the 50 highest ratings of players with at least 5 rated games, highest first. */
@Serializable
data class LeaderboardDto(val entries: List<LeaderboardEntryDto>)

/** One player of [LeaderboardDto]; [icon] as in [ProfileDto]. */
@Serializable
data class LeaderboardEntryDto(val username: String, val icon: String, val rating: Int, val ratedGames: Int)
