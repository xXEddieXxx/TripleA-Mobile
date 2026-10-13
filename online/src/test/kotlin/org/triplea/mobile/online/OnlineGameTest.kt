package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.changefactory.ChangeFactory
import games.strategy.engine.random.IRandomSource
import games.strategy.engine.message.IRemote
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.triplea.mobile.LocalGameSession
import org.triplea.mobile.PlayerKind
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Event
import org.triplea.mobile.protocol.Reply

/**
 * Plays the minimap with both nations as remote seats: the host runs the engine with a
 * [RemoteHumanUi] per seat, the devices answer through [OnlineClient] with a [ScriptedPlayer], and
 * every message goes through JSON ([Wire]). A hang (e.g. copying the game while the game thread
 * holds its read lock) fails through the timeout.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class OnlineGameTest {
    @Test
    fun remoteSeatsPlayTwoRoundsAndBadAnswersAreRejected() {
        val host = Minimap.parse()
        val wire = Wire(host)
        wire.seat("Russians", ScriptedPlayer())
        wire.seat("Italians", ScriptedPlayer())
        wire.tamper(
            "Russians",
            Ask.Purchase::class,
            Reply.Purchase(mapOf("no such rule" to 1)),
            Reply.Purchase(mapOf("buyInfantry" to -1)),
            Reply.YesNo(true),
            Reply.Purchase(mapOf("buyInfantry" to 715827883)),
        )
        val session = startGame(wire, host)
        session.setUpForSteps()
        playUntilRound(session, host, 3)

        assertThat(host.sequence.round).isGreaterThanOrEqualTo(3)
        for ((nation, player) in wire.players) {
            assertThat(player.purchases).describedAs("$nation purchases").isGreaterThanOrEqualTo(2)
            assertThat(player.endTurns).describedAs("$nation end turns").isGreaterThanOrEqualTo(2)
            assertThat(player.errors).describedAs("$nation errors").isEmpty()
        }
        assertThat(wire.rejections).containsExactly(
            "unknown production rule no such rule",
            "negative purchase of buyInfantry",
            "unexpected answer YesNo",
            "at most 10000 of buyInfantry",
        )
        assertThat(wire.asked).contains("Purchase", "Move", "Place", "EndTurn")
    }

    @Test
    fun attackWithUndoCasualtiesPlacementQueryAndEvents() {
        val host = Minimap.parse()
        val russians = host.playerList.getPlayerId("Russians")!!
        // a mixed stack next to the enemy, so the attack forces a real casualty choice
        val reinforcements = host.unitTypeList.getUnitTypeOrThrow("artillery").create(1, russians) +
            host.unitTypeList.getUnitTypeOrThrow("armour").create(1, russians)
        host.performChange(ChangeFactory.addUnits(host.map.getTerritoryOrNull("red north 2")!!, reinforcements))
        // a second battle: the engine fights the last pending battle by itself, so the Battle question needs two
        val stray = host.unitTypeList.getUnitTypeOrThrow("infantry").create(1, russians)
        host.performChange(ChangeFactory.addUnits(host.map.getTerritoryOrNull("blue middle 2")!!, stray))
        val attacker = ScriptedPlayer(attack = "red north 2" to "blue north 2")
        val wire = Wire(host)
        wire.seat("Russians", attacker)
        wire.seat("Italians", ScriptedPlayer())
        attacker.askPlaceable = wire::placeable
        wire.tamper("Russians", Ask.Move::class, Reply.Undo(-1), Reply.Undo(99))
        wire.tamper("Russians", Ask.Battle::class, Reply.Done)
        wire.tamper(
            "Russians",
            Ask.Casualties::class,
            { ask -> (ask as Ask.Casualties).selectFrom.first().let { Reply.Casualties(listOf(it, it), emptyList(), false) } },
            { _ -> Reply.Casualties(emptyList(), emptyList(), false) },
        )
        val session = startGame(wire, host)
        // every die hits: the 2 defenders hit 2 of the 4 mixed attackers, who must choose; the defenders all die
        session.serverGame.setRandomSource(AlwaysHits)
        session.setUpForSteps()
        playUntilRound(session, host, 3)
        wire.flushEvents()

        assertThat(wire.rejections).containsExactly(
            "undo index must not be negative: -1",
            // the engine's answer to undoing a move that does not exist
            "No moves to undo",
            "unexpected answer Done",
            "a unit was named twice in killed casualties",
            "choose exactly 2 casualties",
        )
        assertThat(attacker.errors).isEmpty()
        assertThat(wire.players.getValue("Italians").errors).isEmpty()
        assertThat(attacker.undos).isEqualTo(1)
        assertThat(attacker.infantryBackAfterUndo).describedAs("infantry in red north 1 after the undo").isEqualTo(2)
        assertThat(attacker.casualtyChoices).isGreaterThanOrEqualTo(1)
        assertThat(host.map.getTerritoryOrNull("blue north 2")!!.owner).isEqualTo(russians)
        assertThat(attacker.placeable).anySatisfy { assertThat(it.isError).isFalse(); assertThat(it.units).isNotEmpty() }
        assertThat(attacker.events).contains("battleStarted", "diceRolled", "stepChanged")

        // an event naming a unit the device does not know is shown without that unit
        assertThatCode { wire.deliver(Event.UnitsRetreated(UUID.randomUUID().toString(), listOf(UUID.randomUUID().toString()))) }
            .doesNotThrowAnyException()
        assertThat(attacker.events).contains("unitsRetreated:0")
    }

    private object AlwaysHits : IRandomSource {
        override fun getRandom(max: Int, annotation: String): Int = 0

        override fun getRandom(max: Int, count: Int, annotation: String): IntArray = IntArray(count)
    }

    private fun startGame(wire: Wire, host: GameData): LocalGameSession {
        lateinit var session: LocalGameSession
        wire.delegates = DelegateAccess { session.getCurrentRemoteDelegate(IRemote::class.java) }
        session = LocalGameSession.create(
            host,
            wire.players.keys.associateWith { PlayerKind.HUMAN },
            { seat -> RemoteHumanUi(seat, host, wire, wire.delegates) },
            EventEncoder(wire::event),
        )
        return session
    }

    private fun playUntilRound(session: LocalGameSession, host: GameData, round: Int) {
        var steps = 0
        while (host.sequence.round < round && steps < 400 && !session.isGameOver) {
            session.runNextStep()
            steps++
        }
        session.stop()
    }
}
