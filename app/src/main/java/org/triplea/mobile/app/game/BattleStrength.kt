package org.triplea.mobile.app.game

import games.strategy.engine.data.Unit
import games.strategy.triplea.Properties
import games.strategy.triplea.delegate.TerritoryEffectHelper
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls
import org.triplea.mobile.LocalGameSession

/** Strength (the number a die must roll at or below) and dice per unit of one side of a battle. */
class SideStrength(
    val strength: Map<Unit, Int>,
    val rolls: Map<Unit, Int>,
    /** Sum of strength over all dice; divided by the dice sides this is the expected number of hits. */
    val totalPower: Int,
    val diceSides: Int,
) {
    val expectedHits: Double get() = if (diceSides > 0) totalPower.toDouble() / diceSides else 0.0
}

class BattleStrengths(val attackers: SideStrength, val defenders: SideStrength)

/**
 * The current combat values of the units in a battle, computed with the engine's own power
 * calculator, so supports (artillery), territory effects and technology are included exactly as
 * the dice will use them.
 */
object BattleStrength {
    fun compute(session: LocalGameSession, attacking: List<Unit>, defending: List<Unit>, territoryName: String): BattleStrengths? =
        runCatching {
            val data = session.gameData
            data.acquireReadLock().use {
                val territory = data.map.getTerritoryOrNull(territoryName) ?: return@use null
                val effects = TerritoryEffectHelper.getEffects(territory)
                val supports = data.unitTypeList.supportRules
                val lhtr = Properties.getLhtrHeavyBombers(data.properties)
                fun side(units: List<Unit>, enemies: List<Unit>, side: games.strategy.triplea.delegate.battle.BattleState.Side): SideStrength {
                    val combatValue = CombatValueBuilder.mainCombatValue()
                        .enemyUnits(enemies)
                        .friendlyUnits(units)
                        .side(side)
                        .gameSequence(data.sequence)
                        .supportAttachments(supports)
                        .lhtrHeavyBombers(lhtr)
                        .gameDiceSides(data.diceSides)
                        .territoryEffects(effects)
                        .build()
                    val result = PowerStrengthAndRolls.build(units, combatValue)
                    return SideStrength(
                        strength = units.associateWith { result.getStrength(it) },
                        rolls = units.associateWith { result.getRolls(it) },
                        totalPower = result.calculateTotalPower(),
                        diceSides = data.diceSides,
                    )
                }
                BattleStrengths(
                    attackers = side(attacking, defending, games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE),
                    defenders = side(defending, attacking, games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE),
                )
            }
        }.getOrNull()
}
