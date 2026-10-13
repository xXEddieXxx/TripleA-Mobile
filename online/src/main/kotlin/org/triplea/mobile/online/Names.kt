package org.triplea.mobile.online

import games.strategy.engine.data.GameData
import games.strategy.engine.data.GamePlayer
import games.strategy.engine.data.Territory
import games.strategy.engine.data.Unit as GameUnit
import games.strategy.triplea.delegate.DiceRoll
import games.strategy.triplea.delegate.Die
import java.util.UUID
import org.triplea.mobile.protocol.DiceDto
import org.triplea.mobile.protocol.DieDto

/** An answer that names something the game does not have, or that the question did not offer. */
class InvalidAnswerException(message: String) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidAnswerException(message)

internal val GameUnit.wireId: String get() = id.toString()

internal fun Collection<GameUnit>.wireIds(): List<String> = map { it.wireId }

internal fun Collection<Territory>.names(): List<String> = map { it.name }

/** Looks up wire ids in one game: units by UUID, territories and players by name. */
internal class Names(private val data: GameData) {
    private fun unitOrNull(id: String): GameUnit? = runCatching { UUID.fromString(id) }.getOrNull()?.let { data.units.get(it) }

    fun unit(id: String): GameUnit = unitOrNull(id) ?: invalid("unknown unit $id")

    fun units(ids: Collection<String>): List<GameUnit> = ids.map(::unit)

    /** Units that must each appear once: a unit named twice would be moved, placed or killed twice. */
    fun distinctUnits(ids: Collection<String>, what: String): List<GameUnit> =
        units(ids).also { if (it.toSet().size != it.size) invalid("a unit was named twice in $what") }

    /** For events, which only display: units this copy of the game does not know are left out. */
    fun knownUnits(ids: Collection<String>): List<GameUnit> = ids.mapNotNull(::unitOrNull)

    fun territory(name: String): Territory = data.map.getTerritoryOrNull(name) ?: invalid("unknown territory $name")

    fun territories(names: Collection<String>): List<Territory> = names.map(::territory)

    fun player(name: String): GamePlayer = data.playerList.getPlayerId(name) ?: invalid("unknown player $name")
}

/** Every chosen item must be one of the offered ones. */
internal fun <T> requireOffered(chosen: Collection<T>, offered: Collection<T>, what: String) {
    val allowed = offered.toSet()
    val extra = chosen.filterNot { it in allowed }
    if (extra.isNotEmpty()) invalid("$what not offered: $extra")
}

internal fun Die.toDto() = DieDto(value, rolledAt, type.name)

internal fun DieDto.toDie() = Die(value, rolledAt, Die.DieType.valueOf(kind))

internal fun DiceRoll.toDto() = DiceDto(rolls.map { it.toDto() }, hits, expectedHits, playerName)

internal fun DiceDto.toDiceRoll() = DiceRoll(dice.map { it.toDie() }, hits, expectedHits, player)
