package org.triplea.mobile.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun everyMessageSurvivesTheRoundTrip() {
        Samples.serverMessages.forEach {
            assertThat(ProtocolJson.decodeServerMessage(ProtocolJson.encode(it))).isEqualTo(it)
        }
        Samples.clientMessages.forEach {
            assertThat(ProtocolJson.decodeClientMessage(ProtocolJson.encode(it))).isEqualTo(it)
        }
        Samples.rest.forEach { assertThat(it.roundTrip()).isEqualTo(it.value) }
    }

    @Test
    fun samplesCoverEveryMessageType() {
        assertThat(typesOf(Samples.asks, Ask.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Ask.serializer().descriptor))
        assertThat(typesOf(Samples.replies, Reply.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Reply.serializer().descriptor))
        assertThat(typesOf(Samples.notes, Note.serializer())).containsExactlyInAnyOrderElementsOf(subtypes(Note.serializer().descriptor))
        assertThat(typesOf(Samples.events, Event.serializer()).distinct()).containsExactlyInAnyOrderElementsOf(subtypes(Event.serializer().descriptor))
        assertThat(typesOf(Samples.serverMessages, ServerMessage.serializer()).distinct())
            .containsExactlyInAnyOrderElementsOf(subtypes(ServerMessage.serializer().descriptor))
        assertThat(typesOf(Samples.clientMessages, ClientMessage.serializer()).distinct())
            .containsExactlyInAnyOrderElementsOf(subtypes(ClientMessage.serializer().descriptor))
    }

    @Test
    fun messagesMatchTheGoldenFileOfThisProtocolVersion() {
        val actual = (Samples.serverMessages.map { ProtocolJson.encode(it) } + Samples.clientMessages.map { ProtocolJson.encode(it) } +
            Samples.rest.map { it.encode() })
            .joinToString("\n")
        val name = "protocol-v$PROTOCOL_VERSION.json"
        val output = Path.of("build", "golden")
        Files.createDirectories(output)
        Files.writeString(output.resolve(name), actual)
        val expected = javaClass.getResource("/golden/$name")?.readText()?.replace("\r\n", "\n")?.trim()
        assertThat(expected)
            .describedAs("golden file src/test/resources/golden/$name is missing; this run wrote build/golden/$name")
            .isNotNull()
        assertThat(actual)
            .describedAs("the messages changed: bump PROTOCOL_VERSION and add build/golden/$name as the new golden file")
            .isEqualTo(expected)
    }

    @Test
    fun oversizedMessagesAreRefused() {
        val tooLong = ProtocolJson.encode(Answer(1, Reply.Units(List(2000) { "x".repeat(36) })))
        assertThat(tooLong.length).isGreaterThan(MAX_MESSAGE_CHARS)
        assertThatThrownBy { ProtocolJson.decodeClientMessage(tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too long")
    }

    @Test
    fun helloChecksProtocolVersionAndEngineTag() {
        assertThat(Hello(PROTOCOL_VERSION, ENGINE_BASE_TAG).isCompatible()).isTrue()
        assertThat(Hello(PROTOCOL_VERSION + 1, ENGINE_BASE_TAG).isCompatible()).isFalse()
        assertThat(Hello(PROTOCOL_VERSION, "2026-01.1.1").isCompatible()).isFalse()
    }

    private fun <T> typesOf(items: List<T>, serializer: KSerializer<T>): List<String> =
        items.map { ProtocolJson.json.encodeToJsonElement(serializer, it).jsonObject.getValue("type").jsonPrimitive.content }

    @OptIn(ExperimentalSerializationApi::class)
    private fun subtypes(sealed: SerialDescriptor): List<String> = sealed.getElementDescriptor(1).elementNames.toList()

    @Test
    fun defenderKindsComeFromTheAsk() {
        val kinds = Samples.asks.associate { it::class.simpleName!! to DefenderNotice.kindOf(it) }
        assertThat(kinds).containsEntry("Casualties", "casualties").containsEntry("Scramble", "scramble").containsEntry("Retreat", "retreat")
            .containsEntry("Kamikaze", "kamikaze").containsEntry("AcceptAction", "offer").containsEntry("Confirm", "confirm")
            .containsEntry("ConfirmCasualties", "confirm").containsEntry("Purchase", "confirm")
        assertThat(DefenderNotice.label("casualties")).isEqualTo("Choose casualties")
        assertThat(Samples.asks.filterIsInstance<Ask.Retreat>().map { DefenderNotice.territoryOf(it) }).containsExactly("blue north 2")
        assertThat(Samples.asks.filterIsInstance<Ask.Kamikaze>().map { DefenderNotice.territoryOf(it) }).containsExactly("sea zone 2")
        val casualties = Samples.asks.filterIsInstance<Ask.Casualties>().single()
        assertThat(DefenderNotice.territoryOf(casualties.copy(territory = "red north 1"))).isEqualTo("red north 1")
    }
}
