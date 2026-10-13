package org.triplea.mobile.protocol

import kotlinx.serialization.KSerializer

/** One sample of every message type, used by the round trip and the golden file. */
internal object Samples {
    private const val UNIT = "2f1c6a4e-6d1b-4f0e-9a51-3c1f5c7d9e10"
    private const val UNIT2 = "7b9e2c11-0a4d-4c55-8f3e-1d2a3b4c5d6e"
    private val dice = DiceDto(listOf(DieDto(0, 2, "HIT"), DieDto(4, 2, "MISS")), 1, 0.66, "Russians")

    val asks: List<Ask> = listOf(
        Ask.Purchase("Russians", bid = false, keepCurrentPurchase = false),
        Ask.Repair("Russians", bid = false, allowedPlayers = listOf("Russians")),
        Ask.Tech("Russians"),
        Ask.Move("Russians", nonCombat = false, step = "russianCombatMove"),
        Ask.AirCantLand("Russians", listOf("sea zone 1"), movePhase = true),
        Ask.UnitsCantFight(listOf("blue north 2")),
        Ask.Battle("Russians", mapOf("NORMAL" to listOf("blue north 2"))),
        Ask.Place("Russians", bid = false),
        Ask.EndTurn("Russians"),
        Ask.Politics("Russians", firstRun = true, actions = listOf("politicalActionAttachment_Russians_toWar")),
        Ask.UserAction("Russians", firstRun = true, actions = listOf("userActionAttachment_Russians_bribe")),
        Ask.Confirm("Attack submarines", "Attack submarines in sea zone 1?", territory = "sea zone 1"),
        Ask.SelectUnits(listOf(UNIT, UNIT2), "Bombing target", "Select the unit to bomb", max = 1),
        Ask.Casualties(
            listOf(UNIT, UNIT2), count = 1, message = "Russians select casualties", dice = dice, hit = "Russians",
            defaultKilled = listOf(UNIT), defaultDamaged = emptyList(), battleId = UNIT2, allowMultipleHitsPerUnit = false,
        ),
        Ask.SelectTerritory(listOf("sea zone 1"), "Rocket attack", "Where should rockets attack?", noneAllowed = true),
        Ask.Retreat(UNIT2, submerge = false, battleTerritory = "blue north 2", possible = listOf("red north 2"), message = "Retreat?"),
        Ask.Scramble("Italians", "sea zone 2", mapOf("blue north 1" to ScrambleOptionDto(listOf(UNIT), listOf(UNIT2)))),
        Ask.ConfirmCasualties(UNIT2, "Russians lose 1 infantry"),
        Ask.AcceptAction("Italians", "Accept peace?", politics = true),
        Ask.Kamikaze("Italians", mapOf("sea zone 2" to listOf(UNIT)), resource = "suicideAttackTokens", max = 2),
        Ask.PickTerritoryAndUnits("Russians", listOf("red capitol"), listOf(UNIT), unitsPerPick = 1),
    )

    val replies: List<Reply> = listOf(
        Reply.Done,
        Reply.YesNo(true),
        Reply.Purchase(mapOf("buyInfantry" to 2)),
        Reply.Repair(mapOf(UNIT to mapOf("repairFactory" to 1))),
        Reply.Tech(category = null, advances = listOf("heavyBomber"), rolls = 1, newTokens = 0, whoPays = mapOf("Russians" to 5)),
        Reply.Move(MoveDto(listOf(UNIT), listOf("red north 2", "blue north 2"), seaTransports = mapOf(UNIT to UNIT2))),
        Reply.Undo(0),
        Reply.Battle("blue north 2", bombingRaid = false, battleType = "NORMAL"),
        Reply.Place(listOf(UNIT), "red capitol"),
        Reply.Choice("politicalActionAttachment_Russians_toWar"),
        Reply.Units(listOf(UNIT)),
        Reply.Casualties(listOf(UNIT), emptyList(), autoCalculated = false),
        Reply.TerritoryChoice("red north 2"),
        Reply.Scramble(mapOf("blue north 1" to listOf(UNIT2))),
        Reply.Kamikaze(mapOf("sea zone 2" to mapOf(UNIT to 1))),
        Reply.Pick("red capitol", listOf(UNIT)),
    )

    val notes: List<Note> = listOf(
        Note.PhaseStarted("Russians", "russianPurchase"),
        Note.Error("Not enough PUs"),
        Note.Message("Victory", "Russians win"),
        Note.TechResults(listOf(0, 5), hits = 1, remainder = 0, advances = listOf("heavyBomber")),
    )

    val events: List<Event> = listOf(
        Event.Message("Battle", "Russians attack"),
        Event.GameEnded("Russians win"),
        Event.GameEnded("Game aborted by alice", aborted = true),
        Event.StepChanged("russianPurchase", "Purchase", "Russians", 1),
        Event.BattleStarted(UNIT2, "blue north 2", "Russians attack Italians", listOf(UNIT), listOf(UNIT2), "Russians", "Italians", "NORMAL"),
        Event.BattleSteps(UNIT2, listOf("Russians fire", "Italians fire")),
        Event.BattleStep(UNIT2, "Russians fire"),
        Event.BattleEnded(UNIT2, "Russians win"),
        Event.DiceRolled(dice, "Russians fire"),
        Event.Casualties(UNIT2, "Italians select casualties", dice, "Italians", killed = listOf(UNIT2), damaged = emptyList()),
        Event.UnitsDied(UNIT2, "Italians", listOf(UNIT2)),
        Event.UnitsChanged(UNIT2, "Russians", removed = listOf(UNIT), added = listOf(UNIT2)),
        Event.BombingResults(UNIT2, listOf(DieDto(3, 0, "IGNORED")), cost = 4),
        Event.Retreat("retreats", "Russians retreat", "Russians retreat", "Russians"),
        Event.UnitsRetreated(UNIT2, listOf(UNIT)),
        Event.UnitsMoved("Russians", listOf(UNIT), listOf("red north 2", "blue north 2")),
        Event.PlaySound("battle_land", "Russians"),
    )

    val serverMessages: List<ServerMessage> =
        listOf(
            UpdateRequired(PROTOCOL_VERSION, ENGINE_BASE_TAG),
            GameVersion("g1", 7),
            Rejected(3, "unknown territory nowhere"),
            PlaceableResult(4, "red capitol", listOf(UNIT), maxUnits = 3),
            PlaceableResult(5, "red north 1", emptyList(), 0, error = "No factory"),
            Presence("bob", online = true),
            LobbyUpdate(lobbyGame),
            LobbyUpdate(lobbyGame, deleted = true),
            ChatMessage(42, "lobby", "bob", "gl hf", 1_800_000_000_000),
            ChatError("game:Ab3dEf6hIj9k", "Too many messages, wait a moment"),
            Clock("g1", mapOf("alice" to 180_000, "bob" to 95_500), running = "alice", deadline = 1_800_000_180_000, questionId = 42),
            Clock("g1", mapOf("alice" to 0)),
            WatchRefused("g1", "Spectators are not allowed in this game"),
            FriendsChanged("bob"),
        ) +
            asks.mapIndexed { i, ask -> Question(i + 1L, "g1", "Russians", 7, ask, deadline = if (i == 0) 1_800_000_000_000 else null) } +
            notes.map { Notice("g1", "Russians", 7, it) } +
            events.map { GameEvent("g1", 7, it) }

    val clientMessages: List<ClientMessage> =
        listOf(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG), PlaceableQuery(4, "red capitol", listOf(UNIT)), ChatSend("lobby", "gl hf"), Watch("g1")) +
            replies.mapIndexed { i, reply -> Answer(i + 1L, reply) }

    private val seats = listOf(SeatDto("Russians", "HUMAN", "alice"), SeatDto("Germans", "FAST_AI", "bob", resigned = true))
    // getters: serverMessages above is initialised first
    private val lobbySeats
        get() = listOf(SeatDto("Russians", "HUMAN", "alice", ready = true), SeatDto("Germans", "OPEN"))
    private val lobbyGame
        get() = GameInfoDto("Ab3dEf6hIj9k", "minimap", "Minimap", "lobby", lobbySeats, 0, 0, null, null, visibility = "link", inviteCode = "Zx9Yw8Vu7Ts6", creator = "alice")

    /** One sample of every REST body. */
    val rest: List<RestSample<*>> = listOf(
        RestSample(RegisterRequest.serializer(), RegisterRequest("alice", "password123", ageConfirmed = true)),
        RestSample(LoginRequest.serializer(), LoginRequest("alice", "password123")),
        RestSample(RefreshRequest.serializer(), RefreshRequest("r3fr3sh")),
        RestSample(TokensDto.serializer(), TokensDto("acc3ss", "r3fr3sh", 900, "alice")),
        RestSample(MapGameDto.serializer(), MapGameDto("minimap", "Minimap", listOf("Russians", "Germans"))),
        RestSample(SeatDto.serializer(), seats[0]),
        RestSample(CreateGameRequest.serializer(), CreateGameRequest("minimap", "Minimap", seats)),
        RestSample(CreateGameRequest.serializer(), CreateGameRequest("minimap", "Minimap", lobbySeats, visibility = "public")),
        RestSample(GameInfoDto.serializer(), lobbyGame),
        RestSample(JoinRequest.serializer(), JoinRequest("Germans", inviteCode = "Zx9Yw8Vu7Ts6")),
        RestSample(LeaveRequest.serializer(), LeaveRequest("Germans")),
        RestSample(ReadyRequest.serializer(), ReadyRequest(true)),
        RestSample(
            GameInfoDto.serializer(),
            GameInfoDto("Ab3dEf6hIj9k", "minimap", "Minimap", "finished", seats, 12, 3, "russianPurchase", "Russians", listOf("Russians")),
        ),
        RestSample(ErrorDto.serializer(), ErrorDto("Unknown game")),
        RestSample(FriendDto.serializer(), FriendDto("bob", "accepted", online = true)),
        RestSample(FriendRequest.serializer(), FriendRequest("bob")),
        RestSample(BlockRequest.serializer(), BlockRequest("bob")),
        RestSample(ReportRequest.serializer(), ReportRequest("bob", "Insults in chat", chatMessageId = 42)),
        RestSample(DeleteAccountRequest.serializer(), DeleteAccountRequest("password123")),
        RestSample(ChatMessage.serializer(), ChatMessage(42, "game:Ab3dEf6hIj9k", "deleted user", "gg", 1_800_000_000_000)),
        RestSample(TimeControlDto.serializer(), blitz),
        RestSample(
            CreateGameRequest.serializer(),
            CreateGameRequest("minimap", "Minimap", seats, timeControl = TimeControlDto("correspondence", 86_400_000), aiTakesOver = false, resignAfterMissedTurns = 0, allowSpectators = false),
        ),
        RestSample(GameInfoDto.serializer(), lobbyGame.copy(timeControl = blitz)),
        RestSample(VacationRequest.serializer(), VacationRequest(3)),
        RestSample(StandingOrdersDto.serializer(), StandingOrdersDto()),
        RestSample(StandingOrdersDto.serializer(), StandingOrdersDto("cheapest_first", "never", "decline", "never", defenderMode = "standing")),
        RestSample(DefenderModeRequest.serializer(), DefenderModeRequest("live")),
        RestSample(TurnDto.serializer(), TurnDto("g1", 42, "Russians", 1_700_000_000_000)),
        RestSample(GameInfoDto.serializer(), lobbyGame.copy(mapReady = false)),
        RestSample(GameInfoDto.serializer(), lobbyGame.copy(status = "aborted", mapReady = false, error = "Map could not be downloaded: HTTP 404")),
        RestSample(GameInfoDto.serializer(), lobbyGame.copy(aiTakesOver = false, resignAfterMissedTurns = 0, allowSpectators = false)),
        RestSample(SeatUpdateRequest.serializer(), SeatUpdateRequest("HUMAN", "bob")),
        RestSample(SeatUpdateRequest.serializer(), SeatUpdateRequest("OPEN")),
        RestSample(UpdateGameRequest.serializer(), UpdateGameRequest(visibility = "public", timeControl = blitz, aiTakesOver = false, resignAfterMissedTurns = 5, allowSpectators = false)),
        RestSample(UpdateGameRequest.serializer(), UpdateGameRequest(noTimeControl = true)),
        RestSample(ChatReadRequest.serializer(), ChatReadRequest(42)),
        RestSample(FriendDto.serializer(), FriendDto("bob", "accepted", online = true, unread = 2, activity = "playing Minimap · round 4", lastSeen = 1_800_000_000_000, channel = "dm:3:7")),
        RestSample(TurnsDto.serializer(), TurnsDto(listOf(TurnDto("g1", 42, "Russians")), unreadDirect = 3)),
        RestSample(RegisterRequest.serializer(), RegisterRequest("alice", "password123", ageConfirmed = true, email = "alice@example.org")),
        RestSample(FeaturesDto.serializer(), FeaturesDto(email = true)),
        RestSample(EmailRequest.serializer(), EmailRequest("alice@example.org", "password123")),
        RestSample(EmailConfirmRequest.serializer(), EmailConfirmRequest("123456")),
        RestSample(ForgotPasswordRequest.serializer(), ForgotPasswordRequest("alice")),
        RestSample(ResetPasswordRequest.serializer(), ResetPasswordRequest("alice@example.org", "123456", "new-password")),
        RestSample(AccountDto.serializer(), AccountDto("alice", "alice@example.org")),
        RestSample(ChangePasswordRequest.serializer(), ChangePasswordRequest("password123", "new-password")),
        RestSample(
            GameInfoDto.serializer(),
            lobbyGame.copy(
                status = "running", timeControl = blitz, turn = TurnInfoDto("Russians", "alice"),
                turnRemainingMs = 180_000, turnDeadline = 1_800_000_180_000, turnSince = 1_800_000_000_000,
            ),
        ),
        RestSample(GameInfoDto.serializer(), lobbyGame.copy(status = "running", turn = TurnInfoDto("Germans", ai = true), turnSince = 1_800_000_000_000)),
        RestSample(
            ProfileDto.serializer(),
            ProfileDto(
                "bob", "flag:Germans", "Blitz only",
                StatsDto(played = 5, won = 2, lost = 1, resigned = 1, abandoned = 1, winRate = 0.5, mostPlayedMap = "minimap", mostPlayedNation = "Germans"),
                listOf(RecentGameDto("Ab3dEf6hIj9k", "minimap", "Minimap", listOf("Germans"), "won", 1_800_000_000_000, replay = true)),
            ),
        ),
        RestSample(ProfileDto.serializer(), ProfileDto("carol", "emblem:default", "", StatsDto())),
        RestSample(ProfileUpdateRequest.serializer(), ProfileUpdateRequest(icon = "unit:fighter", status = "Back on Monday")),
    )

    private val blitz get() = TimeControlDto("blitz", 300_000, incrementMs = 5_000)

    class RestSample<T>(val serializer: KSerializer<T>, val value: T) {
        fun encode(): String = ProtocolJson.json.encodeToString(serializer, value)

        fun roundTrip(): T = ProtocolJson.json.decodeFromString(serializer, encode())
    }
}
