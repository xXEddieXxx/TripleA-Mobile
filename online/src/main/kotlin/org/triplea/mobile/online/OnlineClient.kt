package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.battle.IBattle
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.TechResults
import games.strategy.triplea.delegate.data.TechRoll
import games.strategy.triplea.delegate.remote.IPoliticsDelegate
import games.strategy.triplea.delegate.remote.IUserActionDelegate
import java.lang.reflect.Proxy
import java.util.UUID
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.GameEventListener
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.GameEvent
import org.triplea.mobile.protocol.MoveDto
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Notice
import org.triplea.mobile.protocol.Question
import org.triplea.mobile.protocol.Rejected
import org.triplea.mobile.protocol.Reply
import org.triplea.util.Tuple

/**
 * The device side of an online game. It turns a [Question] into a call on [ui] (the UI that plays
 * local games) and the result into an [Answer]. Notices and events go to [ui] and [events].
 * [data] is the device's copy of the game at the message's version; the caller syncs it first.
 * Call [answer] on a background thread: [ui] blocks until the player has answered.
 */
class OnlineClient(private val ui: HumanPlayerUi, private val events: GameEventListener) {

    fun answer(question: Question, data: GameData): Answer = Answer(question.id, reply(question.ask, Names(data), data))

    private fun reply(ask: Ask, names: Names, data: GameData): Reply =
        when (ask) {
            is Ask.Purchase ->
                ui.getPurchase(names.player(ask.player), ask.bid, ask.keepCurrentPurchase).orElse(null)
                    ?.let { Reply.Purchase(it.byName { rule -> rule.name }) } ?: Reply.Done
            is Ask.Repair ->
                ui.getRepair(names.player(ask.player), ask.bid, ask.allowedPlayers.map(names::player)).orElse(null)
                    ?.let { repair -> Reply.Repair(repair.entries.associate { (unit, rules) -> unit.wireId to rules.byName { it.name } }) }
                    ?: Reply.Done
            is Ask.Tech -> {
                val player = names.player(ask.player)
                ui.getTechRolls(player).orElse(null)?.let { techReply(player, it) } ?: Reply.Done
            }
            is Ask.Move -> undoable {
                ui.getMove(names.player(ask.player), ask.nonCombat, ask.step).orElse(null)?.let { Reply.Move(it.toDto()) } ?: Reply.Done
            }
            is Ask.AirCantLand -> Reply.YesNo(ui.okToLetAirDie(names.player(ask.player), names.territories(ask.territories), ask.movePhase))
            is Ask.UnitsCantFight -> Reply.YesNo(ui.okToLetUnitsDie(names.territories(ask.territories)))
            is Ask.Battle ->
                ui.chooseBattle(names.player(ask.player), battleListing(ask.battles, names)).orElse(null)
                    ?.let { Reply.Battle(it.where.name, it.isBombingRaid, it.battleType.name) } ?: Reply.Done
            is Ask.Place -> undoable {
                ui.getPlacement(names.player(ask.player), ask.bid).orElse(null)?.let { Reply.Place(it.units.wireIds(), it.at.name) } ?: Reply.Done
            }
            is Ask.EndTurn -> {
                ui.waitForEndTurn(names.player(ask.player))
                Reply.Done
            }
            is Ask.Politics -> {
                val player = names.player(ask.player)
                val offered = PoliticalActionAttachment.getPoliticalActionAttachments(player).filter { it.nameOrThrow in ask.actions }
                ui.getPoliticalActionChoice(player, ask.firstRun, offering(IPoliticsDelegate::class.java, offered))
                    ?.let { Reply.Choice(it.nameOrThrow) } ?: Reply.Done
            }
            is Ask.UserAction -> {
                val player = names.player(ask.player)
                val offered = UserActionAttachment.getUserActionAttachments(player).filter { it.nameOrThrow in ask.actions }
                ui.getUserActionChoice(player, ask.firstRun, offering(IUserActionDelegate::class.java, offered))
                    ?.let { Reply.Choice(it.nameOrThrow) } ?: Reply.Done
            }
            is Ask.Confirm ->
                Reply.YesNo(ask.territory?.let { ui.confirmInBattle(names.territory(it), ask.title, ask.question) } ?: ui.confirm(ask.title, ask.question))
            is Ask.SelectUnits -> {
                val candidates = names.units(ask.candidates)
                val chosen = ask.territory?.let { ui.selectUnitsInBattle(names.territory(it), candidates, ask.title, ask.message, ask.max) }
                    ?: ui.selectUnits(candidates, ask.title, ask.message, ask.max)
                Reply.Units(chosen.wireIds())
            }
            is Ask.Casualties -> {
                val details = ui.selectCasualties(
                    names.units(ask.selectFrom), emptyMap(), ask.count, ask.message, ask.dice?.toDiceRoll(), names.player(ask.hit),
                    CasualtyList(names.units(ask.defaultKilled), names.units(ask.defaultDamaged)), UUID.fromString(ask.battleId),
                    ask.allowMultipleHitsPerUnit,
                )
                Reply.Casualties(details.killed.wireIds(), details.damaged.wireIds(), details.autoCalculated)
            }
            is Ask.SelectTerritory ->
                ui.selectTerritory(names.territories(ask.candidates), ask.title, ask.message, ask.noneAllowed)
                    ?.let { Reply.TerritoryChoice(it.name) } ?: Reply.Done
            is Ask.Retreat ->
                ui.retreatQuery(UUID.fromString(ask.battleId), ask.submerge, names.territory(ask.battleTerritory), names.territories(ask.possible), ask.message)
                    .orElse(null)?.let { Reply.TerritoryChoice(it.name) } ?: Reply.Done
            is Ask.Scramble -> {
                val possible = ask.options.entries.associate { (from, option) ->
                    names.territory(from) to Tuple.of<Collection<GameUnit>, Collection<GameUnit>>(names.units(option.airbases), names.units(option.units))
                }
                val chosen = ui.scrambleUnitsQuery(names.player(ask.player), names.territory(ask.scrambleTo), possible)
                Reply.Scramble(chosen.entries.associate { (from, units) -> from.name to units.wireIds() })
            }
            // dead path: the host answers it (RemoteHumanUi.confirmCasualties); kept for the protocol's completeness
            is Ask.ConfirmCasualties -> {
                ui.confirmCasualties(UUID.fromString(ask.battleId), ask.message)
                Reply.Done
            }
            is Ask.AcceptAction -> Reply.YesNo(ui.acceptAction(names.player(ask.from), ask.question, ask.politics))
            is Ask.Kamikaze -> {
                val targets = ask.targets.entries.associate { (place, ids) -> names.territory(place) to names.units(ids) }
                val attacks = ui.selectKamikazeSuicideAttacks(names.player(ask.player), targets, data.resourceList.getResourceOrThrow(ask.resource), ask.max)
                Reply.Kamikaze(attacks.entries.associate { (territory, amounts) -> territory.name to amounts.byName { it.wireId } })
            }
            is Ask.PickTerritoryAndUnits -> {
                val pick = ui.pickTerritoryAndUnits(names.player(ask.player), names.territories(ask.territories), names.units(ask.units), ask.unitsPerPick)
                Reply.Pick(pick.first?.name, pick.second.wireIds())
            }
        }

    private inline fun undoable(block: () -> Reply): Reply =
        try {
            block()
        } catch (e: UndoRequest) {
            Reply.Undo(e.index)
        }

    private fun <K> IntegerMap<K>.byName(name: (K) -> String): Map<String, Int> = keySet().associate { name(it) to getInt(it) }

    private fun MoveDescription.toDto() =
        MoveDto(
            units.wireIds(),
            route.allTerritories.names(),
            unitsToSeaTransports.entries.associate { (unit, transport) -> unit.wireId to transport.wireId },
            airTransportsDependents.entries.associate { (transport, units) -> transport.wireId to units.wireIds() },
        )

    private fun techReply(player: GamePlayer, roll: TechRoll): Reply.Tech {
        val tech = roll.tech
        val category = tech?.name?.takeIf { name -> name.isNotEmpty() && player.technologyFrontierList.frontiers.any { it.name == name } }
        val advances = if (category == null) tech?.techs.orEmpty().map { it.name } else emptyList()
        val whoPays = roll.whoPaysHowMuch?.byName { it.name }.orEmpty()
        return Reply.Tech(category, advances, roll.rolls, roll.newTokens, whoPays)
    }

    private fun battleListing(battles: Map<String, List<String>>, names: Names): BattleListing =
        BattleListing(emptySet<IBattle>()).apply {
            battles.forEach { (type, places) -> battlesMap[BattleType.valueOf(type)] = names.territories(places).toMutableSet() }
        }

    /** The delegate argument of the politics and user action questions: the UI only reads the offered actions. */
    private fun <D : Any> offering(type: Class<D>, actions: Collection<*>): D =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name) {
                    "getValidActions" -> actions
                    "toString" -> "online ${type.simpleName}"
                    else -> throw UnsupportedOperationException("${method.name} is not available in online games")
                }
            },
        )

    fun notice(notice: Notice, data: GameData) {
        when (val note = notice.note) {
            is Note.PhaseStarted -> ui.startPhase(Names(data).player(note.player), note.step)
            is Note.Error -> ui.notifyError(note.text)
            is Note.Message -> ui.notifyMessage(note.text, note.title)
            is Note.TechResults ->
                ui.notifyTechResults(note.error?.let { TechResults(it) } ?: TechResults(note.rolls.toIntArray(), note.remainder, note.hits, note.advances))
        }
    }

    fun rejected(rejected: Rejected) = ui.notifyError(rejected.reason)

    fun event(gameEvent: GameEvent, data: GameData) {
        val names = Names(data)
        when (val e = gameEvent.event) {
            is Event.Message -> events.message(e.title, e.text)
            is Event.GameEnded -> if (e.aborted) events.gameAborted(e.message) else events.gameEnded(e.message)
            is Event.StepChanged -> events.gameStepChanged(e.step, e.displayName, e.player?.let(names::player), e.round)
            is Event.BattleStarted ->
                events.battleStarted(
                    UUID.fromString(e.battleId), names.territory(e.territory), e.title, names.knownUnits(e.attackingUnits),
                    names.knownUnits(e.defendingUnits), names.player(e.attacker), names.player(e.defender), BattleType.valueOf(e.battleType),
                )
            is Event.BattleSteps -> events.battleSteps(UUID.fromString(e.battleId), e.steps)
            is Event.BattleStep -> events.battleStep(UUID.fromString(e.battleId), e.step)
            is Event.BattleEnded -> events.battleEnded(UUID.fromString(e.battleId), e.message)
            is Event.DiceRolled -> events.diceRolled(e.dice.toDiceRoll(), e.step)
            is Event.Casualties ->
                events.casualties(
                    UUID.fromString(e.battleId), e.step, e.dice?.toDiceRoll(), names.player(e.player),
                    names.knownUnits(e.killed), names.knownUnits(e.damaged), emptyMap(),
                )
            is Event.UnitsDied -> events.unitsDied(UUID.fromString(e.battleId), names.player(e.player), names.knownUnits(e.dead), emptyMap())
            is Event.UnitsChanged ->
                events.unitsChanged(UUID.fromString(e.battleId), names.player(e.player), names.knownUnits(e.removed), names.knownUnits(e.added))
            is Event.BombingResults -> events.bombingResults(UUID.fromString(e.battleId), e.dice.map { it.toDie() }, e.cost)
            is Event.Retreat -> events.retreat(e.shortMessage, e.message, e.step, e.player?.let(names::player))
            is Event.UnitsRetreated -> events.unitsRetreated(UUID.fromString(e.battleId), names.knownUnits(e.units))
            is Event.UnitsMoved -> events.unitsMoved(names.player(e.player), names.knownUnits(e.units), names.territories(e.route))
            is Event.PlaySound -> events.playSound(e.clip, e.player?.let(names::player))
        }
    }
}
