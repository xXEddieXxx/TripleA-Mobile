package org.triplea.mobile.online

import games.strategy.engine.data.DefaultAttachment
import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.MoveDescription
import games.strategy.engine.data.ProductionRule
import games.strategy.engine.data.RepairRule
import games.strategy.engine.data.Resource
import games.strategy.engine.data.Route
import games.strategy.engine.data.TechnologyFrontier
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.Constants
import games.strategy.triplea.Properties
import games.strategy.triplea.attachments.PlayerAttachment
import games.strategy.triplea.attachments.PoliticalActionAttachment
import games.strategy.triplea.attachments.TechAbilityAttachment
import games.strategy.triplea.attachments.UserActionAttachment
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.TechTracker
import games.strategy.triplea.delegate.TechnologyDelegate
import games.strategy.triplea.delegate.battle.IBattle.BattleType
import games.strategy.triplea.delegate.battle.ScrambleLogic
import games.strategy.triplea.delegate.data.BattleListing
import games.strategy.triplea.delegate.data.CasualtyDetails
import games.strategy.triplea.delegate.data.CasualtyList
import games.strategy.triplea.delegate.data.FightBattleDetails
import games.strategy.triplea.delegate.data.TechResults
import games.strategy.triplea.delegate.data.TechRoll
import games.strategy.triplea.delegate.remote.IAbstractMoveDelegate
import games.strategy.triplea.delegate.remote.IPoliticsDelegate
import games.strategy.triplea.delegate.remote.IUserActionDelegate
import games.strategy.triplea.ui.PlaceData
import games.strategy.triplea.ui.newPlaceData
import java.security.SecureRandom
import java.util.Optional
import java.util.UUID
import kotlin.math.ceil
import org.triplea.java.collections.IntegerMap
import org.triplea.mobile.HumanPlayerUi
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Reply
import org.triplea.mobile.protocol.ScrambleOptionDto
import org.triplea.util.Tuple

/** No legitimate purchase, repair or tech amount comes near it; bigger ones only stall or overflow the engine. */
private const val MAX_AMOUNT = 10_000

/**
 * The host side of one human seat played on another device. Every engine question becomes an
 * [Ask] that [link] carries to the player. The answer is checked against this game and turned back
 * into engine objects; an answer that does not fit is rejected with a reason and the question is
 * asked again, so nothing a device sends reaches the engine unchecked. The engine validates the
 * content itself (legal moves, costs) as it does for the desktop's network players.
 */
class RemoteHumanUi(
    private val seat: String,
    private val data: GameData,
    private val link: HostLink,
    private val delegates: DelegateAccess,
) : HumanPlayerUi {
    private val names = Names(data)
    private val random = SecureRandom()

    private fun <T> askUntilValid(question: Ask, decode: (Reply) -> T): T = askUntilValidAnswer(question, decode).second

    /** Like [askUntilValid], with the id of the question that got the valid answer. */
    private fun <T> askUntilValidAnswer(question: Ask, decode: (Reply) -> T): Pair<Long, T> {
        while (true) {
            val answer = link.ask(seat, question)
            try {
                return answer.questionId to data.acquireReadLock().use { decode(answer.reply) }
            } catch (e: InvalidAnswerException) {
                link.reject(answer.questionId, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                // engine constructors (Route, MoveDescription, ...) check their arguments
                link.reject(answer.questionId, e.message ?: "invalid answer")
            }
        }
    }

    private inline fun <reified R : Reply> Reply.expect(): R = this as? R ?: invalid("unexpected answer ${this::class.simpleName}")

    /** What the player did in a move or placement phase. */
    private sealed interface PhaseAction<out T> {
        data object Finish : PhaseAction<Nothing>

        data class Undo(val index: Int) : PhaseAction<Nothing>

        data class Act<T>(val value: T) : PhaseAction<T>
    }

    /** A move or placement phase question: an action, an undo (the question follows again) or the end. */
    private fun <T : Any> phase(question: Ask, decode: (Reply) -> T): Optional<T> {
        while (true) {
            val (questionId, action) = askUntilValidAnswer(question) { reply ->
                when (reply) {
                    Reply.Done -> PhaseAction.Finish
                    is Reply.Undo ->
                        if (reply.index < 0) invalid("undo index must not be negative: ${reply.index}") else PhaseAction.Undo(reply.index)
                    else -> PhaseAction.Act(decode(reply))
                }
            }
            when (action) {
                PhaseAction.Finish -> return Optional.empty()
                // a failed undo changed nothing: rejected, so the re-ask takes no new save
                is PhaseAction.Undo ->
                    (delegates.current() as IAbstractMoveDelegate<*>).undoMove(action.index)?.let { link.reject(questionId, it) }
                is PhaseAction.Act -> return Optional.of(action.value)
            }
        }
    }

    override fun startPhase(player: GamePlayer, stepName: String) = link.notify(seat, Note.PhaseStarted(player.name, stepName))

    override fun getTechRolls(player: GamePlayer): Optional<TechRoll> =
        Optional.ofNullable(askUntilValid(Ask.Tech(player.name)) { reply -> if (reply == Reply.Done) null else techRoll(player, reply.expect()) })

    private fun techRoll(player: GamePlayer, reply: Reply.Tech): TechRoll {
        // the engine rolls `rolls` dice; zero dice throw on the game thread
        if (reply.rolls < 1) invalid("at least one tech roll")
        if (reply.newTokens < 0) invalid("negative tech tokens")
        if (reply.rolls > MAX_AMOUNT || reply.newTokens > MAX_AMOUNT) invalid("at most $MAX_AMOUNT tech rolls or tokens")
        val tech = techTarget(player, reply)
        val helpers = PlayerAttachment.get(player)?.helpPayTechCost.orEmpty()
        val whoPays = IntegerMap<GamePlayer>()
        reply.whoPays.forEach { (name, amount) ->
            if (amount < 0) invalid("negative payment by $name")
            if (amount > MAX_AMOUNT) invalid("at most $MAX_AMOUNT ${Constants.PUS} by $name")
            val payer = names.player(name)
            if (payer != player && payer !in helpers) invalid("$name may not pay for the technology of ${player.name}")
            whoPays.put(payer, amount)
        }
        requireTechAffordable(player, reply, whoPays)
        return TechRoll(tech, reply.rolls, reply.newTokens, if (whoPays.isEmpty()) null else whoPays)
    }

    /**
     * What the rolls are for, per tech model as TechnologyDelegate.rollTech reads it: a category in
     * WW2V3 (its advances are discovered), one available advance in the revised model, nothing in the
     * classic model (a hit discovers a random advance). Mirrors the app's TechDialog.
     */
    private fun techTarget(player: GamePlayer, reply: Reply.Tech): TechnologyFrontier? {
        val props = data.properties
        val available = TechnologyDelegate.getAvailableTechs(player, data.technologyFrontier)
        return when {
            Properties.getWW2V3TechModel(props) -> {
                val name = reply.category ?: invalid("choose a technology category")
                val category = player.technologyFrontierList.frontiers.firstOrNull { it.name == name } ?: invalid("unknown technology category $name")
                // as the app offers; an empty category would also break the delegate if WW2V2 is set too
                if (category.techs.none { it in available }) invalid("no technology left in category $name")
                // with WW2V2 also set, the delegate awards the category's first technology
                if (Properties.getWW2V2(props) && category.techs[0] !in available) invalid("the first technology of $name is already researched")
                category
            }
            Properties.getWW2V2(props) || Properties.getSelectableTechRoll(props) -> {
                val name = reply.advances.singleOrNull() ?: invalid("choose exactly one technology")
                val advance = available.firstOrNull { it.name == name } ?: invalid("technology $name is not available")
                TechnologyFrontier("", data).apply { addAdvance(advance) }
            }
            else -> null
        }
    }

    /**
     * The engine multiplies rolls by the tech cost in int; a huge count would wrap around or make the
     * game thread roll forever. The paid count is rolls, or new tokens in the WW2V3 tech model, where
     * the rolls are the tokens held.
     */
    private fun requireTechAffordable(player: GamePlayer, reply: Reply.Tech, whoPays: IntegerMap<GamePlayer>) {
        val v3 = Properties.getWW2V3TechModel(data.properties)
        val paid = (if (v3) reply.newTokens else reply.rolls).toLong()
        val cost = paid * TechTracker.getTechCost(player)
        // the rule of TechnologyDelegate.checkEnoughMoney; an answer it refuses is asked again by recursion
        // in MobilePlayer.tech, so a device repeating it would overflow the game thread's stack
        if (whoPays.isEmpty()) {
            val has = player.resources.getQuantity(Constants.PUS)
            if (cost > has) invalid("$paid tech rolls cost $cost ${Constants.PUS}, more than ${player.name} has")
        } else {
            whoPays.entrySet().forEach { (payer, amount) ->
                if (amount > payer.resources.getQuantity(Constants.PUS)) invalid("${payer.name} cannot pay $amount ${Constants.PUS}")
            }
            val total = whoPays.keySet().sumOf { whoPays.getInt(it).toLong() }
            if (total < cost) invalid("payments of $total ${Constants.PUS} do not cover the $cost ${Constants.PUS} of $paid tech rolls")
        }
        if (v3) {
            // the delegate charges tokens with getResourceOrThrow, which would throw on the game thread
            val tokenResource = data.resourceList.getResourceOptional(Constants.TECH_TOKENS).orElse(null) ?: invalid("this map has no tech tokens")
            val tokens = player.resources.getQuantity(tokenResource)
            if (reply.rolls > tokens + paid) invalid("${reply.rolls} tech rolls but only ${tokens + paid} tech tokens")
        }
    }

    /**
     * The engine adds the costs of a purchase or repair up in int, so a huge amount wraps around to
     * a negative cost; totals are counted in Long here and must fit and be affordable.
     */
    private fun requireAffordable(
        player: GamePlayer,
        bid: Boolean,
        what: String,
        costs: List<Pair<IntegerMap<Resource>, Int>>,
        discount: Double = 1.0,
    ) {
        val totals = HashMap<Resource, Long>()
        for ((cost, amount) in costs) {
            for (resource in cost.keySet()) {
                // each total stays <= Int.MAX_VALUE before the next product (< 2^62) is added, so no Long overflow
                val total = totals.getOrDefault(resource, 0L) + cost.getInt(resource).toLong() * amount
                if (total > Int.MAX_VALUE) invalid("$what costs $total ${resource.name}, more than the game can count")
                totals[resource] = total
            }
        }
        // shortcut: a bid's budget is a game property the bid delegate checks itself; only the overflow is checked here
        if (bid) return
        totals.forEach { (resource, total) ->
            val due = ceil(total * discount).toLong()
            if (due > player.resources.getQuantity(resource)) invalid("$what costs $due ${resource.name}, more than ${player.name} has")
        }
    }

    override fun notifyTechResults(results: TechResults) =
        link.notify(
            seat,
            Note.TechResults(results.rolls?.toList().orEmpty(), results.hits, results.remainder, results.advances.orEmpty(), results.errorString),
        )

    override fun getRepair(
        player: GamePlayer,
        bid: Boolean,
        allowedPlayersToRepair: Collection<GamePlayer>,
    ): Optional<Map<GameUnit, IntegerMap<RepairRule>>> =
        Optional.ofNullable(
            askUntilValid(Ask.Repair(player.name, bid, allowedPlayersToRepair.map { it.name })) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    val rules = player.repairFrontier?.rules.orEmpty().associateBy { it.name }
                    val repairs = reply.expect<Reply.Repair>().units.entries.associate { (unitId, amounts) ->
                        val unit = names.unit(unitId)
                        if (unit.owner != player && unit.owner !in allowedPlayersToRepair) invalid("${player.name} may not repair $unitId")
                        unit to IntegerMap<RepairRule>().apply {
                            amounts.forEach { (rule, amount) ->
                                if (amount < 0) invalid("negative repair of $rule")
                                if (amount > MAX_AMOUNT) invalid("at most $MAX_AMOUNT repairs of $rule")
                                put(rules[rule] ?: invalid("unknown repair rule $rule"), amount)
                            }
                        }
                    }
                    // the repair delegate grants the same discount when it charges
                    val discount = TechAbilityAttachment.getRepairDiscount(TechTracker.getCurrentTechAdvances(player, data.technologyFrontier))
                    requireAffordable(player, bid, "repair", repairs.values.flatMap { map -> map.keySet().map { it.costs to map.getInt(it) } }, discount)
                    repairs
                }
            },
        )

    override fun getPurchase(player: GamePlayer, bid: Boolean, keepCurrentPurchase: Boolean): Optional<IntegerMap<ProductionRule>> =
        Optional.ofNullable(
            askUntilValid(Ask.Purchase(player.name, bid, keepCurrentPurchase)) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    val rules = player.productionFrontier?.rules.orEmpty().associateBy { it.name }
                    IntegerMap<ProductionRule>().apply {
                        reply.expect<Reply.Purchase>().rules.forEach { (name, amount) ->
                            if (amount < 0) invalid("negative purchase of $name")
                            if (amount > MAX_AMOUNT) invalid("at most $MAX_AMOUNT of $name")
                            put(rules[name] ?: invalid("unknown production rule $name"), amount)
                        }
                        requireAffordable(player, bid, "purchase", keySet().map { it.costs to getInt(it) })
                    }
                }
            },
        )

    override fun getMove(player: GamePlayer, nonCombat: Boolean, stepName: String): Optional<MoveDescription> =
        phase(Ask.Move(player.name, nonCombat, stepName)) { reply -> moveDescription(reply.expect()) }

    private fun moveDescription(reply: Reply.Move): MoveDescription {
        val move = reply.move
        val route = names.territories(move.route)
        if (route.size < 2) invalid("a route needs a start and an end")
        return MoveDescription(
            names.distinctUnits(move.units, "move"),
            Route(route),
            move.seaTransports.entries.associate { (unit, transport) -> names.unit(unit) to names.unit(transport) },
            move.airTransportDependents.entries.associate { (transport, units) -> names.unit(transport) to names.units(units) },
        )
    }

    override fun okToLetAirDie(player: GamePlayer, airCantLand: Collection<Territory>, movePhase: Boolean): Boolean =
        askUntilValid(Ask.AirCantLand(player.name, airCantLand.names(), movePhase)) { it.expect<Reply.YesNo>().yes }

    override fun okToLetUnitsDie(unitsCantFight: Collection<Territory>): Boolean =
        askUntilValid(Ask.UnitsCantFight(unitsCantFight.names())) { it.expect<Reply.YesNo>().yes }

    override fun chooseBattle(player: GamePlayer, battles: BattleListing): Optional<FightBattleDetails> {
        val offered = battles.battlesMap
        val question = Ask.Battle(player.name, offered.entries.associate { (type, places) -> type.name to places.names() })
        // the engine only asks while battles are left, so "done" is not an answer here
        return Optional.of(
            askUntilValid(question) { reply ->
                val choice = reply.expect<Reply.Battle>()
                val type = BattleType.entries.firstOrNull { it.name == choice.battleType } ?: invalid("unknown battle type ${choice.battleType}")
                val where = names.territory(choice.territory)
                if (where !in offered[type].orEmpty()) invalid("no ${choice.battleType} battle in ${choice.territory}")
                if (choice.bombingRaid != type.isBombingRun) invalid("bombing raid does not match ${choice.battleType}")
                FightBattleDetails.builder().where(where).bombingRaid(choice.bombingRaid).battleType(type).build()
            },
        )
    }

    override fun getPlacement(player: GamePlayer, bid: Boolean): Optional<PlaceData> =
        phase(Ask.Place(player.name, bid)) { reply ->
            val place = reply.expect<Reply.Place>()
            newPlaceData(names.distinctUnits(place.units, "placement"), names.territory(place.territory))
        }

    override fun waitForEndTurn(player: GamePlayer) {
        askUntilValid(Ask.EndTurn(player.name)) { it.expect<Reply.Done>() }
    }

    override fun getPoliticalActionChoice(player: GamePlayer, firstRun: Boolean, delegate: IPoliticsDelegate): PoliticalActionAttachment? {
        val actions = delegate.validActions
        return if (actions.isEmpty()) null else choose(actions, Ask.Politics(player.name, firstRun, actions.map { it.nameOrThrow }))
    }

    override fun getUserActionChoice(player: GamePlayer, firstRun: Boolean, delegate: IUserActionDelegate): UserActionAttachment? {
        val actions = delegate.validActions
        return if (actions.isEmpty()) null else choose(actions, Ask.UserAction(player.name, firstRun, actions.map { it.nameOrThrow }))
    }

    private fun <A : DefaultAttachment> choose(actions: Collection<A>, question: Ask): A? =
        askUntilValid(question) { reply ->
            if (reply == Reply.Done) {
                null
            } else {
                val name = reply.expect<Reply.Choice>().name
                actions.firstOrNull { it.nameOrThrow == name } ?: invalid("action $name not offered")
            }
        }

    override fun notifyError(error: String) = link.notify(seat, Note.Error(error))

    override fun notifyMessage(message: String, title: String) = link.notify(seat, Note.Message(title, message))

    override fun confirm(title: String, question: String): Boolean =
        askUntilValid(Ask.Confirm(title, question)) { it.expect<Reply.YesNo>().yes }

    override fun confirmInBattle(territory: Territory, title: String, question: String): Boolean =
        askUntilValid(Ask.Confirm(title, question, territory.name)) { it.expect<Reply.YesNo>().yes }

    override fun selectUnits(candidates: Collection<GameUnit>, title: String, message: String, max: Int): Collection<GameUnit> =
        selectUnits(candidates, title, message, max, null)

    override fun selectUnitsInBattle(
        territory: Territory,
        candidates: Collection<GameUnit>,
        title: String,
        message: String,
        max: Int,
    ): Collection<GameUnit> = selectUnits(candidates, title, message, max, territory.name)

    private fun selectUnits(candidates: Collection<GameUnit>, title: String, message: String, max: Int, territory: String?): Collection<GameUnit> =
        askUntilValid(Ask.SelectUnits(candidates.wireIds(), title, message, max, territory)) { reply ->
            val chosen = names.distinctUnits(reply.expect<Reply.Units>().units, "units")
            requireOffered(chosen, candidates, "units")
            if (chosen.size > max) invalid("at most $max units")
            chosen
        }

    override fun selectCasualties(
        selectFrom: Collection<GameUnit>,
        dependents: Map<GameUnit, Collection<GameUnit>>,
        count: Int,
        message: String,
        dice: DiceRoll?,
        hit: GamePlayer,
        defaultCasualties: CasualtyList,
        battleId: UUID,
        allowMultipleHitsPerUnit: Boolean,
    ): CasualtyDetails {
        val question = Ask.Casualties(
            selectFrom.wireIds(), count, message, dice?.toDto(), hit.name,
            defaultCasualties.killed.wireIds(), defaultCasualties.damaged.wireIds(), battleId.toString(), allowMultipleHitsPerUnit,
            data.acquireReadLock().use { data.battleDelegate.battleTracker.getPendingBattle(battleId)?.territory?.name },
        )
        return askUntilValid(question) { reply ->
            val answer = reply.expect<Reply.Casualties>()
            val killed = names.distinctUnits(answer.killed, "killed casualties")
            val damaged = names.units(answer.damaged)
            if (damaged.isNotEmpty() && !allowMultipleHitsPerUnit) invalid("no unit can be damaged in this battle")
            requireOffered(killed + damaged, selectFrom, "casualties")
            // a unit may be both damaged and killed, as in the engine's own default for multi-hit units
            damaged.groupingBy { it }.eachCount().forEach { (unit, hits) ->
                if (hits > unit.hitsUnitCanTakeHitWithoutBeingKilled()) invalid("${unit.wireId} cannot take $hits more hits")
            }
            // the engine only asks when its default selection has exactly `count` entries
            if (killed.size + damaged.size != count) invalid("choose exactly $count casualties")
            CasualtyDetails(killed, damaged, answer.autoCalculated)
        }
    }

    // asked in edit mode and, under Selectable Tech Roll, for which advances tech hits discover;
    // the device never chooses dice, so the host rolls them
    override fun selectFixedDice(numDice: Int, hitAt: Int, title: String, diceSides: Int): IntArray =
        IntArray(numDice) { random.nextInt(diceSides) }

    override fun selectTerritory(candidates: Collection<Territory>, title: String, message: String, noneAllowed: Boolean): Territory? =
        askUntilValid(Ask.SelectTerritory(candidates.names(), title, message, noneAllowed)) { reply ->
            if (reply == Reply.Done) {
                if (!noneAllowed) invalid("a territory must be chosen")
                null
            } else {
                names.territory(reply.expect<Reply.TerritoryChoice>().territory).also { requireOffered(listOf(it), candidates, "territory") }
            }
        }

    override fun retreatQuery(
        battleId: UUID,
        submerge: Boolean,
        battleTerritory: Territory,
        possibleTerritories: Collection<Territory>,
        message: String,
    ): Optional<Territory> =
        Optional.ofNullable(
            askUntilValid(Ask.Retreat(battleId.toString(), submerge, battleTerritory.name, possibleTerritories.names(), message)) { reply ->
                if (reply == Reply.Done) {
                    null
                } else {
                    names.territory(reply.expect<Reply.TerritoryChoice>().territory)
                        .also { requireOffered(listOf(it), possibleTerritories, "retreat territory") }
                }
            },
        )

    override fun scrambleUnitsQuery(
        player: GamePlayer,
        scrambleTo: Territory,
        possibleScramblers: Map<Territory, Tuple<Collection<GameUnit>, Collection<GameUnit>>>,
    ): Map<Territory, Collection<GameUnit>> {
        val options = possibleScramblers.entries.associate { (from, bases) -> from.name to ScrambleOptionDto(bases.first.wireIds(), bases.second.wireIds()) }
        return askUntilValid(Ask.Scramble(player.name, scrambleTo.name, options)) { reply ->
            reply.expect<Reply.Scramble>().units.entries.associate { (from, ids) ->
                val territory = names.territory(from)
                val option = possibleScramblers[territory] ?: invalid("no scramble from $from")
                val units = names.distinctUnits(ids, "scrambling aircraft")
                requireOffered(units, option.second, "scrambling aircraft")
                val max = ScrambleLogic.getMaxScrambleCount(option.first)
                if (units.size > max) invalid("at most $max aircraft may scramble from $from")
                territory to units
            }
        }
    }

    // the host confirms: an online battle never waits on a phone for this (the local "pause after casualties" stays offline-only)
    override fun confirmCasualties(battleId: UUID, message: String) = Unit

    override fun acceptAction(playerSendingProposal: GamePlayer, question: String, politics: Boolean): Boolean =
        askUntilValid(Ask.AcceptAction(playerSendingProposal.name, question, politics)) { it.expect<Reply.YesNo>().yes }

    override fun selectKamikazeSuicideAttacks(
        player: GamePlayer,
        possibleUnitsToAttack: Map<Territory, Collection<GameUnit>>,
        attackResourceToken: Resource,
        maxNumberOfAttacksAllowed: Int,
    ): Map<Territory, IntegerMap<GameUnit>> {
        val targets = possibleUnitsToAttack.entries.associate { (territory, units) -> territory.name to units.wireIds() }
        return askUntilValid(Ask.Kamikaze(player.name, targets, attackResourceToken.name, maxNumberOfAttacksAllowed)) { reply ->
            var total = 0L
            val attacks = reply.expect<Reply.Kamikaze>().attacks.entries.associate { (place, amounts) ->
                val territory = names.territory(place)
                val offered = possibleUnitsToAttack[territory] ?: invalid("no kamikaze targets in $place")
                territory to IntegerMap<GameUnit>().apply {
                    amounts.forEach { (id, amount) ->
                        val unit = names.unit(id)
                        if (unit !in offered) invalid("not a kamikaze target: $id")
                        if (amount < 0) invalid("negative kamikaze attacks on $id")
                        total += amount
                        put(unit, amount)
                    }
                }
            }
            if (total > maxNumberOfAttacksAllowed) invalid("at most $maxNumberOfAttacksAllowed attacks")
            attacks
        }
    }

    override fun pickTerritoryAndUnits(
        player: GamePlayer,
        territoryChoices: List<Territory>,
        unitChoices: List<GameUnit>,
        unitsPerPick: Int,
    ): Tuple<Territory?, Set<GameUnit>> =
        askUntilValid(Ask.PickTerritoryAndUnits(player.name, territoryChoices.names(), unitChoices.wireIds(), unitsPerPick)) { reply ->
            val pick = reply.expect<Reply.Pick>()
            val territory = pick.territory?.let(names::territory)?.also { requireOffered(listOf(it), territoryChoices, "territory") }
            val units = names.units(pick.units).toSet()
            requireOffered(units, unitChoices, "units")
            if (units.size > unitsPerPick) invalid("at most $unitsPerPick units")
            Tuple.of(territory, units)
        }
}
