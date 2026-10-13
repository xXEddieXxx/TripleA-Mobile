package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.Resource
import games.strategy.engine.data.changefactory.ChangeFactory
import games.strategy.triplea.Constants
import games.strategy.triplea.delegate.TechAdvance
import games.strategy.triplea.delegate.data.TechRoll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.triplea.mobile.protocol.Answer
import org.triplea.mobile.protocol.Ask
import org.triplea.mobile.protocol.Note
import org.triplea.mobile.protocol.Reply

/**
 * The tech answer of a remote seat is checked per tech model before the engine sees it: no target
 * where the model needs one, no zero or huge roll counts, no advance the player cannot research.
 */
class RemoteHumanUiTechTest {
    /** Answers each question with the next queued reply and records the rejections. */
    private class QueuedLink(vararg replies: Reply) : HostLink {
        private val queue = ArrayDeque(replies.toList())
        val rejections = mutableListOf<String>()
        private var id = 0L

        override fun ask(seat: String, ask: Ask) = Answer(++id, queue.removeFirst())

        override fun reject(questionId: Long, reason: String) {
            rejections += reason
        }

        override fun notify(seat: String, note: Note) = Unit
    }

    private fun tech(
        category: String? = null,
        advances: List<String> = emptyList(),
        rolls: Int = 1,
        newTokens: Int = 0,
        whoPays: Map<String, Int> = emptyMap(),
    ) = Reply.Tech(category, advances, rolls, newTokens, whoPays)

    private fun game(ww2v2: Boolean, v3: Boolean = false, selectable: Boolean = false, tokens: Boolean = v3): GameData =
        Minimap.parse().apply {
            properties.set(Constants.WW2V2, ww2v2)
            properties.set(Constants.WW2V3_TECH_MODEL, v3)
            properties.set(Constants.SELECTABLE_TECH_ROLL, selectable)
            // the minimap has no tech tokens; a WW2V3 map defines them
            if (tokens) resourceList.addResource(Resource(Constants.TECH_TOKENS, this))
        }

    private fun roll(data: GameData, link: QueuedLink): TechRoll {
        val ui = RemoteHumanUi("Russians", data, link) { error("no delegate needed") }
        return ui.getTechRolls(data.playerList.getPlayerId("Russians")!!).orElseThrow()
    }

    @Test
    fun revisedModelNeedsOneAvailableAdvanceAndAtLeastOneRoll() {
        // the minimap plays WW2V2 rules: the revised model, the player names the advance
        val data = game(ww2v2 = true)
        val russians = data.playerList.getPlayerId("Russians")!!
        data.performChange(ChangeFactory.attachmentPropertyChange(russians.techAttachment, true, TechAdvance.TECH_PROPERTY_JET_POWER))
        val link = QueuedLink(
            tech(),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER, TechAdvance.TECH_NAME_SUPER_SUBS)),
            tech(advances = listOf("no such tech")),
            tech(advances = listOf(TechAdvance.TECH_NAME_JET_POWER)),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), rolls = 0),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), rolls = 10_001),
            // the payments must pass TechnologyDelegate.checkEnoughMoney (the Russians have 24 PUs, a roll costs 5)
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), whoPays = mapOf("Russians" to 0)),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), whoPays = mapOf("Russians" to 25)),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), whoPays = mapOf("Russians" to 10_001)),
            tech(advances = listOf(TechAdvance.TECH_NAME_HEAVY_BOMBER), rolls = 2),
        )

        val result = roll(data, link)

        assertThat(link.rejections).containsExactly(
            "choose exactly one technology",
            "choose exactly one technology",
            "technology no such tech is not available",
            "technology Jet Power is not available",
            "at least one tech roll",
            "at most 10000 tech rolls or tokens",
            "payments of 0 PUs do not cover the 5 PUs of 1 tech rolls",
            "Russians cannot pay 25 PUs",
            "at most 10000 PUs by Russians",
        )
        assertThat(result.tech!!.techs.map { it.name }).containsExactly(TechAdvance.TECH_NAME_HEAVY_BOMBER)
        assertThat(result.rolls).isEqualTo(2)
    }

    @Test
    fun ww2v3NeedsAKnownCategory() {
        val data = game(ww2v2 = false, v3 = true)
        val category = data.playerList.getPlayerId("Russians")!!.technologyFrontierList.frontiers[0].name
        val link = QueuedLink(
            tech(advances = listOf(TechAdvance.TECH_NAME_JET_POWER), newTokens = 1),
            tech(category = "no such category", newTokens = 1),
            tech(category = category, rolls = 0, newTokens = 0),
            tech(category = category, newTokens = 10_001, rolls = 10_001),
            tech(category = category, newTokens = 1),
        )

        val result = roll(data, link)

        assertThat(link.rejections).containsExactly(
            "choose a technology category",
            "unknown technology category no such category",
            "at least one tech roll",
            "at most 10000 tech rolls or tokens",
        )
        assertThat(result.tech!!.name).isEqualTo(category)
        assertThat(result.rolls).isEqualTo(1)
        assertThat(result.newTokens).isEqualTo(1)
    }

    @Test
    fun ww2v3WithoutTechTokensIsRejected() {
        val data = game(ww2v2 = false, v3 = true, tokens = false)
        val category = data.playerList.getPlayerId("Russians")!!.technologyFrontierList.frontiers[0].name
        val link = QueuedLink(tech(category = category, newTokens = 1), Reply.Done)
        val ui = RemoteHumanUi("Russians", data, link) { error("no delegate needed") }

        assertThat(ui.getTechRolls(data.playerList.getPlayerId("Russians")!!)).isEmpty()
        assertThat(link.rejections).containsExactly("this map has no tech tokens")
    }

    @Test
    fun ww2v3WithWw2v2NeedsTheFirstTechnologyOfTheCategoryAvailable() {
        // the delegate then awards the category's first technology, which must not be researched yet
        val data = game(ww2v2 = true, v3 = true)
        val russians = data.playerList.getPlayerId("Russians")!!
        val category = russians.technologyFrontierList.frontiers[0]
        data.performChange(ChangeFactory.attachmentPropertyChange(russians.techAttachment, true, category.techs[0].property))
        val link = QueuedLink(tech(category = category.name, newTokens = 1), Reply.Done)
        val ui = RemoteHumanUi("Russians", data, link) { error("no delegate needed") }

        assertThat(ui.getTechRolls(russians)).isEmpty()
        assertThat(link.rejections).containsExactly("the first technology of ${category.name} is already researched")
    }

    @Test
    fun classicModelIgnoresTheTargetTheDeviceSent() {
        val data = game(ww2v2 = false)
        val link = QueuedLink(
            tech(rolls = 0),
            tech(category = "anything", advances = listOf("no such tech"), rolls = 3),
        )

        val result = roll(data, link)

        assertThat(link.rejections).containsExactly("at least one tech roll")
        assertThat(result.tech).isNull()
        assertThat(result.rolls).isEqualTo(3)
    }

    @Test
    fun selectableTechRollIsTheRevisedModelAndDoneMeansNoResearch() {
        val data = game(ww2v2 = false, selectable = true)
        val link = QueuedLink(tech(rolls = 1), Reply.Done)
        val ui = RemoteHumanUi("Russians", data, link) { error("no delegate needed") }

        assertThat(ui.getTechRolls(data.playerList.getPlayerId("Russians")!!)).isEmpty()
        assertThat(link.rejections).containsExactly("choose exactly one technology")
    }
}
