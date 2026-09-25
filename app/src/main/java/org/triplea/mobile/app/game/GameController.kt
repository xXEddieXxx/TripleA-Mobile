package org.triplea.mobile.app.game

import android.util.Log
import games.strategy.engine.GameOverException
import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.remote.IPoliticsDelegate
import games.strategy.triplea.delegate.remote.IUserActionDelegate
import games.strategy.triplea.delegate.Die
import games.strategy.triplea.delegate.battle.IBattle
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.ui.PlaceData
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUiAdapter
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.MobileEngine
import org.triplea.mobile.PlayerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.triplea.mobile.app.AppSettings
import org.triplea.mobile.app.Haptics
import org.triplea.mobile.app.sound.SoundPlayer
import org.triplea.sound.SoundPath
import games.strategy.engine.data.GameStep

/** Round, step and active player as shown in the top bar. */
data class GameStatus(
    val round: Int = 0,
    val stepName: String = "",
    val stepDisplayName: String = "",
    val playerName: String = "",
    val isHumanTurn: Boolean = false,
)

/** What the UI shows for the battle currently being fought. */
/** What each side lost in one round of a battle. */
data class RoundLosses(val round: Int, val attackerLost: List<Unit> = emptyList(), val defenderLost: List<Unit> = emptyList())

data class BattleState(
    val id: UUID,
    val title: String,
    val territory: String,
    val attacker: String,
    val defender: String,
    val type: IBattle.BattleType,
    val attackingUnits: List<Unit>,
    val defendingUnits: List<Unit>,
    val steps: List<String> = emptyList(),
    val currentStep: String = "",
    val log: List<String> = emptyList(),
    val lastDice: List<Int> = emptyList(),
    val lastDiceHitFlags: List<Boolean> = emptyList(),
    val lastDiceHits: Int = 0,
    /** The step the last dice belong to, e.g. "Germans fire". */
    val diceStep: String = "",
    /** Who rolled the last dice: the attacker's or the defender's name. */
    val lastDiceSide: String = "",
    /** The last dice grouped by the strength they were rolled at: (value, hit) pairs. */
    val lastDiceByStrength: Map<Int, List<Pair<Int, Boolean>>> = emptyMap(),
    /** Battle round, counted from the first step of the step list. */
    val round: Int = 1,
    val lastCasualties: List<Unit> = emptyList(),
    val lastCasualtyPlayer: String = "",
    /** Every unit lost so far, by round, so the window can show a tally under each side. */
    val lossesByRound: List<RoundLosses> = emptyList(),
    /**
     * Units chosen as casualties that are still on the front line: they fire back this round
     * and only leave when the engine removes them, so their dice are seen before they vanish.
     */
    val dying: List<Unit> = emptyList(),
    val ended: Boolean = false,
    val endMessage: String = "",
) {
    /** Records [units] as lost by [player] in the current round; units already recorded are skipped. */
    fun withLosses(player: String, units: Collection<Unit>): BattleState {
        val known = lossesByRound.flatMap { it.attackerLost + it.defenderLost }.toHashSet()
        val fresh = units.filter { it !in known }
        if (fresh.isEmpty()) return this
        val attackerSide = player == attacker
        val current = lossesByRound.lastOrNull()?.takeIf { it.round == round } ?: RoundLosses(round)
        val updated = if (attackerSide) current.copy(attackerLost = current.attackerLost + fresh) else current.copy(defenderLost = current.defenderLost + fresh)
        val rest = if (lossesByRound.lastOrNull()?.round == round) lossesByRound.dropLast(1) else lossesByRound
        return copy(lossesByRound = rest + updated)
    }
}

/** Non-blocking notifications for the user (errors, messages from the engine). */
data class UiMessage(val title: String, val text: String, val isError: Boolean = false)

/**
 * Owns the running game and bridges the engine's blocking questions to the Compose UI. The engine
 * calls the [HumanPlayerUiAdapter] methods on its own thread; each one publishes a [UiRequest] and
 * waits until the UI answers it.
 */
object GameController : HumanPlayerUiAdapter(), GameEventListener {
    private const val TAG = "GameController"

    @Volatile
    var session: LocalGameSession? = null
        private set

    val gameData: GameData? get() = session?.gameData

    private val _pending = MutableStateFlow<UiRequest<*>?>(null)
    val pending = _pending.asStateFlow()

    private val _status = MutableStateFlow(GameStatus())
    val status = _status.asStateFlow()

    private val _battle = MutableStateFlow<BattleState?>(null)
    val battle = _battle.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    private val _dataVersion = MutableStateFlow(0)
    val dataVersion = _dataVersion.asStateFlow()

    private val _gameOver = MutableStateFlow<String?>(null)
    val gameOver = _gameOver.asStateFlow()

    /** A move an AI nation just made, for the map to follow. */
    class AiMove(val player: String, val units: List<Unit>, val route: List<String>)

    private val _aiMoves = MutableSharedFlow<AiMove>(extraBufferCapacity = 64)
    val aiMoves = _aiMoves.asSharedFlow()

    override fun unitsMoved(player: GamePlayer, units: Collection<Unit>, route: List<Territory>) {
        if (isHuman(player) || route.size < 2) return
        _aiMoves.tryEmit(AiMove(player.name, units.toList(), route.map { it.name }))
    }

    private val humanPlayers = mutableSetOf<String>()

    /** Parses and starts a new game. Call from a background thread. */
    /** The game XML the running game was started from; null when it was loaded from a save. */
    @Volatile
    var gameXmlPath: Path? = null
        private set

    fun startNewGame(gameXml: Path, kinds: Map<String, PlayerKind>): LocalGameSession {
        gameXmlPath = gameXml
        val data = MobileEngine.parseGame(gameXml).orElseThrow {
            IllegalStateException("Could not parse game: $gameXml")
        }
        return startSession(data, kinds)
    }

    /** Loads a save game and continues it. Call from a background thread. */
    fun loadGame(saveFile: Path, kinds: Map<String, PlayerKind>): LocalGameSession {
        val data = MobileEngine.loadSaveGame(saveFile).orElseThrow {
            IllegalStateException("Could not load save game: $saveFile")
        }
        return startSession(data, kinds)
    }

    fun startSession(data: GameData, kinds: Map<String, PlayerKind>): LocalGameSession {
        quit()
        humanPlayers.clear()
        humanPlayers += kinds.filterValues { it == PlayerKind.HUMAN }.keys
        _gameOver.value = null
        _battle.value = null
        _status.value = GameStatus()
        val newSession = LocalGameSession.create(data, kinds, this, this)
        newSession.addChangeListener { _dataVersion.value = _dataVersion.value + 1 }
        session = newSession
        lastPhaseSoundPlayer = null
        updateStatus(data)
        newSession.start()
        startWatchdog()
        SoundPlayer.play(SoundPath.CLIP_GAME_START, null, newSession.resourceLoader)
        return newSession
    }

    fun quit() {
        watchdogJob?.cancel()
        _aiThinkingSeconds.value = 0
        gameXmlPath = null
        SoundPlayer.stopAll()
        val current = session ?: return
        session = null
        _pending.value?.cancel()
        _pending.value = null
        // stopping waits for the game thread; that must never happen on the UI thread
        thread(name = "game-stop", isDaemon = true) { runCatching { current.stop() } }
    }

    fun saveGame(file: Path) {
        val current = session ?: return
        current.saveGame(file)
        // a small sidecar so the load screen can show round, nation and game without opening the save
        runCatching {
            val status = _status.value
            val info = listOf(
                "round=${status.round}",
                "player=${status.playerName}",
                "step=${status.stepDisplayName}",
                "game=${current.gameData.gameName}",
                "map=${current.gameData.mapName}",
            ).joinToString(System.lineSeparator())
            java.nio.file.Files.write(infoFileFor(file), info.toByteArray(Charsets.UTF_8))
        }
    }

    /** The sidecar next to a save: "name.tsvg" -> "name.tsvg.info". */
    fun infoFileFor(save: Path): Path = save.resolveSibling(save.fileName.toString() + ".info")

    fun isHuman(player: GamePlayer?): Boolean = player != null && humanPlayers.contains(player.name)

    private fun updateStatus(data: GameData) {
        val step = data.sequence.step
        _status.value = GameStatus(
            round = data.sequence.round,
            stepName = step.name ?: "",
            stepDisplayName = step.displayName ?: step.name ?: "",
            playerName = step.playerId?.name ?: "",
            isHumanTurn = isHuman(step.playerId),
        )
    }

    private fun <T> ask(request: UiRequest<T>): T {
        if (session?.isGameOver != false) {
            throw GameOverException("game is over")
        }
        _pending.value = request
        try {
            return request.result.get()
        } catch (e: ExecutionException) {
            throw GameOverException("game stopped while waiting for user input", e)
        } catch (e: CancellationException) {
            throw GameOverException("game stopped while waiting for user input", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GameOverException("game thread interrupted", e)
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    // ---- HumanPlayerUi -------------------------------------------------------------------------

    override fun playSound(clipName: String, player: GamePlayer?) {
        val loader = session?.resourceLoader ?: return
        SoundPlayer.play(clipName, player?.name, loader)
    }

    private var lastPhaseSoundPlayer: String? = null

    /** The client side sounds the desktop plays at the start of a human phase. */
    private fun playPhaseSound(player: GamePlayer, stepName: String) {
        val loader = session?.resourceLoader ?: return
        if (lastPhaseSoundPlayer != player.name) {
            lastPhaseSoundPlayer = player.name
            SoundPlayer.play(SoundPath.CLIP_REQUIRED_YOUR_TURN_SERIES, player.name, loader)
        }
        val clip = when {
            GameStep.isPurchaseOrBidStepName(stepName) -> SoundPath.CLIP_PHASE_PURCHASE
            GameStep.isMoveStepName(stepName) ->
                if (stepName.contains("NonCombat", ignoreCase = true)) SoundPath.CLIP_PHASE_MOVE_NONCOMBAT else SoundPath.CLIP_PHASE_MOVE_COMBAT
            GameStep.isBattleStepName(stepName) -> SoundPath.CLIP_PHASE_BATTLE
            GameStep.isPlaceStepName(stepName) -> SoundPath.CLIP_PHASE_PLACEMENT
            GameStep.isPoliticsStepName(stepName) -> SoundPath.CLIP_PHASE_POLITICS
            GameStep.isUserActionsStepName(stepName) -> SoundPath.CLIP_PHASE_USER_ACTIONS
            GameStep.isTechStepName(stepName) -> SoundPath.CLIP_PHASE_TECHNOLOGY
            GameStep.isEndTurnStepName(stepName) -> SoundPath.CLIP_PHASE_END_TURN
            else -> null
        }
        if (clip != null) SoundPlayer.play(clip, player.name, loader)
    }

    override fun startPhase(player: GamePlayer, stepName: String) {
        playPhaseSound(player, stepName)
        gameData?.let { updateStatus(it) }
    }

    override fun getPurchase(
        player: GamePlayer,
        bid: Boolean,
        keepCurrentPurchase: Boolean,
    ): Optional<IntegerMap<ProductionRule>> = ask(PurchaseRequest(player, bid))

    override fun getMove(
        player: GamePlayer,
        nonCombat: Boolean,
        stepName: String,
    ): Optional<MoveDescription> = ask(MoveRequest(player, nonCombat, stepName))

    override fun chooseBattle(
        player: GamePlayer,
        battles: BattleListing,
    ): Optional<FightBattleDetails> = ask(BattleRequest(player, battles))

    override fun getPlacement(player: GamePlayer, bid: Boolean): Optional<PlaceData> =
        ask(PlaceRequest(player, bid))

    override fun waitForEndTurn(player: GamePlayer) {
        ask(EndTurnRequest(player))
    }

    override fun notifyError(error: String) {
        Log.w(TAG, "Engine error: $error")
        _messages.tryEmit(UiMessage("Error", error, isError = true))
    }

    override fun notifyMessage(message: String, title: String) {
        _messages.tryEmit(UiMessage(stripHtml(title), stripHtml(message)))
    }

    override fun confirm(title: String, question: String): Boolean =
        ask(ConfirmRequest(title, question))

    override fun confirmInBattle(territory: Territory, title: String, question: String): Boolean =
        ask(ConfirmRequest(title, question, territory = territory.name))

    override fun selectUnitsInBattle(
        territory: Territory,
        candidates: Collection<Unit>,
        title: String,
        message: String,
        max: Int,
    ): Collection<Unit> {
        if (candidates.isEmpty()) return emptyList()
        return ask(SelectUnitsRequest(candidates.toList(), title, message, max, territory = territory.name))
    }

    override fun selectCasualties(
        selectFrom: Collection<Unit>,
        dependents: Map<Unit, Collection<Unit>>,
        count: Int,
        message: String,
        dice: DiceRoll?,
        hit: GamePlayer,
        defaultCasualties: CasualtyList,
        battleId: UUID,
        allowMultipleHitsPerUnit: Boolean,
    ): CasualtyDetails {
        if (count <= 0 || selectFrom.isEmpty() || AppSettings.current.autoDefaultCasualties) {
            return CasualtyDetails(defaultCasualties, true)
        }
        return ask(
            CasualtyRequest(
                battleId,
                selectFrom.toList(),
                count,
                message,
                dice,
                hit,
                defaultCasualties,
                allowMultipleHitsPerUnit,
            )
        )
    }

    override fun selectTerritory(
        candidates: Collection<Territory>,
        title: String,
        message: String,
        noneAllowed: Boolean,
    ): Territory? {
        if (candidates.isEmpty()) return null
        return ask(SelectTerritoryRequest(candidates.toList(), title, message, noneAllowed)).orElse(null)
    }

    override fun selectUnits(
        candidates: Collection<Unit>,
        title: String,
        message: String,
        max: Int,
    ): Collection<Unit> {
        if (candidates.isEmpty()) return emptyList()
        return ask(SelectUnitsRequest(candidates.toList(), title, message, max))
    }

    override fun retreatQuery(
        battleId: UUID,
        submerge: Boolean,
        battleTerritory: Territory,
        possibleTerritories: Collection<Territory>,
        message: String,
    ): Optional<Territory> {
        if (possibleTerritories.isEmpty()) return Optional.empty()
        return ask(RetreatRequest(battleId, battleTerritory, possibleTerritories.toList(), message, submerge))
    }

    override fun getPoliticalActionChoice(
        player: GamePlayer,
        firstRun: Boolean,
        delegate: IPoliticsDelegate,
    ): PoliticalActionAttachment? {
        val actions = runCatching { delegate.validActions.toList() }.getOrDefault(emptyList())
        if (actions.isEmpty()) return null
        return ask(PoliticsRequest(player, actions, firstRun)).orElse(null)
    }

    override fun getUserActionChoice(
        player: GamePlayer,
        firstRun: Boolean,
        delegate: IUserActionDelegate,
    ): UserActionAttachment? {
        val actions = runCatching { delegate.validActions.toList() }.getOrDefault(emptyList())
        if (actions.isEmpty()) return null
        return ask(UserActionRequest(player, actions, firstRun)).orElse(null)
    }

    override fun confirmCasualties(battleId: UUID, message: String) {
        if (!AppSettings.current.pauseAfterCasualties) return
        val text = stripHtml(message).replace("Press space to continue", "").trim()
        ask(CasualtyNoticeRequest(battleId, text))
    }

    /** Slows a visible battle down so each step and dice roll can be seen. Runs on the game thread. */
    private fun battlePause() {
        val millis = AppSettings.current.battleStepPauseMillis
        val battle = _battle.value ?: return
        if (battle.ended) return
        // only battles a human takes part in are slowed down; AI-only battles must not stall AI turns
        if (!humanPlayers.contains(battle.attacker) && !humanPlayers.contains(battle.defender)) return
        if (millis > 0) {
            runCatching { Thread.sleep(millis.toLong()) }
        }
    }

    // ---- watchdog: seconds the current AI turn has been running, and a stack dump when it drags on
    private val _aiThinkingSeconds = MutableStateFlow(0)
    val aiThinkingSeconds = _aiThinkingSeconds.asStateFlow()
    private var watchdogJob: Job? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            var aiSince = 0L
            var lastDump = 0L
            var aiPlayer = ""
            while (true) {
                delay(1000)
                val current = session ?: break
                val aiTurn = !_status.value.isHumanTurn && _status.value.playerName.isNotBlank() && _pending.value == null
                if (!aiTurn) {
                    aiSince = 0L
                    aiPlayer = ""
                    _aiThinkingSeconds.value = 0
                    continue
                }
                val now = System.currentTimeMillis()
                // the clock is per nation: a new AI nation starts at zero
                if (aiSince == 0L || aiPlayer != _status.value.playerName) {
                    aiSince = now
                    aiPlayer = _status.value.playerName
                    lastDump = 0L
                }
                _aiThinkingSeconds.value = ((now - aiSince) / 1000).toInt()
                // every 30 s of one AI turn: where is the engine? (visible with adb logcat -s GameWatchdog)
                if (now - aiSince >= 30_000 && now - lastDump >= 30_000) {
                    lastDump = now
                    val thread = current.gameThread
                    val trace = thread?.stackTrace?.take(25)?.joinToString("\n    at ") ?: "no game thread"
                    Log.w("GameWatchdog", "AI turn of ${_status.value.playerName} running ${_aiThinkingSeconds.value}s, step ${_status.value.stepName}; thread ${thread?.state}:\n    at $trace")
                }
            }
        }
    }

    // ---- GameEventListener ---------------------------------------------------------------------

    override fun message(title: String, message: String) {
        _messages.tryEmit(UiMessage(stripHtml(title), stripHtml(message)))
    }

    override fun gameEnded(message: String) {
        session?.resourceLoader?.let { SoundPlayer.play(SoundPath.CLIP_GAME_WON, null, it) }
        _gameOver.value = message
        _messages.tryEmit(UiMessage("Game over", message))
    }

    override fun gameStepChanged(stepName: String, displayName: String, player: GamePlayer?, round: Int) {
        val previous = _status.value
        // the battle phase is over: a finished battle window must not linger while the game moves on
        if (_battle.value?.ended == true) _battle.value = null
        if (AppSettings.current.vibrateOnTurn && isHuman(player) && !previous.isHumanTurn && previous.playerName.isNotBlank()) {
            Haptics.yourTurn()
        }
        _status.value = GameStatus(
            round = round,
            stepName = stepName,
            stepDisplayName = displayName,
            playerName = player?.name ?: "",
            isHumanTurn = isHuman(player),
        )
    }

    override fun battleStarted(
        battleId: UUID,
        location: Territory,
        battleTitle: String,
        attackingUnits: Collection<Unit>,
        defendingUnits: Collection<Unit>,
        attacker: GamePlayer,
        defender: GamePlayer,
        battleType: IBattle.BattleType,
    ) {
        if (!AppSettings.current.showAiBattles && !isHuman(attacker) && !isHuman(defender)) {
            // keep AI battles off screen; clear an older window so its log does not collect these dice
            _battle.value = null
            return
        }
        if (AppSettings.current.vibrateOnBattle && !_status.value.isHumanTurn && (isHuman(attacker) || isHuman(defender))) {
            Haptics.battle()
        }
        _battle.value = BattleState(
            id = battleId,
            title = battleTitle,
            territory = location.name,
            attacker = attacker.name,
            defender = defender.name,
            type = battleType,
            attackingUnits = attackingUnits.toList(),
            defendingUnits = defendingUnits.toList(),
        )
    }

    private fun updateBattle(battleId: UUID, update: (BattleState) -> BattleState) {
        val current = _battle.value ?: return
        if (current.id != battleId) return
        _battle.value = update(current)
    }

    override fun battleSteps(battleId: UUID, steps: List<String>) {
        updateBattle(battleId) { it.copy(steps = steps) }
    }

    override fun battleStep(battleId: UUID, step: String) {
        updateBattle(battleId) {
            // back at the first step of the list: a new battle round begins
            val newRound = it.steps.isNotEmpty() && step == it.steps.first() && it.currentStep.isNotBlank() && it.currentStep != step
            it.copy(currentStep = step, round = if (newRound) it.round + 1 else it.round)
        }
        battlePause()
    }

    override fun battleEnded(battleId: UUID, message: String) {
        updateBattle(battleId) {
            val gone = it.dying.toSet()
            it.copy(
                ended = true,
                endMessage = message,
                log = it.log + message,
                attackingUnits = it.attackingUnits - gone,
                defendingUnits = it.defendingUnits - gone,
                dying = emptyList(),
            )
        }
    }

    override fun diceRolled(dice: DiceRoll, stepName: String) {
        val current = _battle.value ?: return
        val values = (0 until dice.size()).map { dice.getDie(it).value + 1 }
        val hitFlags = (0 until dice.size()).map { dice.getDie(it).type == Die.DieType.HIT }
        val hits = dice.hits
        // the engine keeps the dice per strength ("rolled at"), which places them at the units on screen
        val byStrength = HashMap<Int, List<Pair<Int, Boolean>>>()
        for (strength in 0..12) {
            val rolls = runCatching { dice.getRolls(strength) }.getOrNull().orEmpty()
            if (rolls.isNotEmpty()) byStrength[strength] = rolls.map { (it.value + 1) to (it.type == Die.DieType.HIT) }
        }
        val side = when {
            stepName.startsWith(current.attacker) -> current.attacker
            stepName.startsWith(current.defender) -> current.defender
            else -> ""
        }
        _battle.value = current.copy(
            lastDice = values,
            lastDiceHitFlags = hitFlags,
            lastDiceHits = hits,
            diceStep = stepName,
            lastDiceSide = side,
            lastDiceByStrength = byStrength,
            log = current.log + "$stepName: rolled ${values.joinToString(" ")} ($hits hits)",
        )
        battlePause()
    }

    override fun casualties(
        battleId: UUID,
        step: String,
        dice: DiceRoll?,
        player: GamePlayer,
        killed: Collection<Unit>,
        damaged: Collection<Unit>,
        dependents: Map<Unit, Collection<Unit>>,
    ) {
        updateBattle(battleId) {
            val text = buildString {
                append(player.name).append(": ")
                if (killed.isEmpty() && damaged.isEmpty()) {
                    append("no casualties")
                } else {
                    if (killed.isNotEmpty()) append("lost ").append(summarize(killed))
                    if (damaged.isNotEmpty()) {
                        if (killed.isNotEmpty()) append(", ")
                        append("damaged ").append(summarize(damaged))
                    }
                }
            }
            // the casualties stay on the line, marked, until the engine takes them away
            it.withLosses(player.name, killed).copy(
                dying = it.dying + killed,
                log = it.log + text,
                lastCasualties = killed.toList() + damaged.toList(),
                lastCasualtyPlayer = player.name,
            )
        }
    }

    override fun unitsDied(
        battleId: UUID,
        player: GamePlayer,
        dead: Collection<Unit>,
        dependents: Map<Unit, Collection<Unit>>,
    ) {
        updateBattle(battleId) {
            it.withLosses(player.name, dead).copy(
                attackingUnits = it.attackingUnits - dead.toSet(),
                defendingUnits = it.defendingUnits - dead.toSet(),
                dying = it.dying - dead.toSet(),
                log = if (dead.isEmpty()) it.log else it.log + "${player.name} lost ${summarize(dead)}",
            )
        }
    }

    override fun unitsRetreated(battleId: UUID, retreating: Collection<Unit>) {
        updateBattle(battleId) {
            it.copy(
                attackingUnits = it.attackingUnits - retreating.toSet(),
                defendingUnits = it.defendingUnits - retreating.toSet(),
                log = it.log + "retreated ${summarize(retreating)}",
            )
        }
    }

    override fun retreat(shortMessage: String, message: String, step: String, player: GamePlayer) {
        // the desktop client shows this in its battle panel; here it goes into the battle log,
        // the front line already shows the units leaving. No pop-up.
        val current = _battle.value
        val text = stripHtml(shortMessage).ifBlank { stripHtml(message) }
        if (current != null && !current.ended) {
            updateBattle(current.id) { it.copy(log = it.log + text) }
        }
    }

    fun dismissBattle() {
        _battle.value = null
    }

    /** Engine messages are often HTML snippets meant for Swing labels. */
    fun stripHtml(text: String): String =
        text.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun summarize(units: Collection<Unit>): String =
        units.groupBy { it.type.name }.entries.joinToString(", ") { (type, list) -> "${list.size} $type" }
}
