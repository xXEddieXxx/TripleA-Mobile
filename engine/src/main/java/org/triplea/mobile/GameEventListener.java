package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Die;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Events the engine broadcasts while a game runs (battle progress, dice, messages, game end). The
 * app implements the ones it wants to show; all methods have empty defaults. Events arrive on the
 * game thread.
 */
public interface GameEventListener {

  default void message(final String title, final String message) {}

  default void gameEnded(final String message) {}

  default void gameStepChanged(
      final String stepName, final String displayName, final GamePlayer player, final int round) {}

  default void battleStarted(
      final UUID battleId,
      final Territory location,
      final String battleTitle,
      final Collection<Unit> attackingUnits,
      final Collection<Unit> defendingUnits,
      final GamePlayer attacker,
      final GamePlayer defender,
      final BattleType battleType) {}

  default void battleSteps(final UUID battleId, final List<String> steps) {}

  default void battleStep(final UUID battleId, final String step) {}

  default void battleEnded(final UUID battleId, final String message) {}

  default void diceRolled(final DiceRoll dice, final String stepName) {}

  default void casualties(
      final UUID battleId,
      final String step,
      final DiceRoll dice,
      final GamePlayer player,
      final Collection<Unit> killed,
      final Collection<Unit> damaged,
      final Map<Unit, Collection<Unit>> dependents) {}

  default void unitsDied(
      final UUID battleId,
      final GamePlayer player,
      final Collection<Unit> dead,
      final Map<Unit, Collection<Unit>> dependents) {}

  default void unitsChanged(
      final UUID battleId,
      final GamePlayer player,
      final Collection<Unit> removedUnits,
      final Collection<Unit> addedUnits) {}

  default void bombingResults(final UUID battleId, final List<Die> dice, final int cost) {}

  default void retreat(
      final String shortMessage, final String message, final String step, final GamePlayer player) {}

  default void unitsRetreated(final UUID battleId, final Collection<Unit> retreating) {}

  /** Units moved along a route; the route's territories are given start to end. */
  default void unitsMoved(
      final GamePlayer player, final Collection<Unit> units, final List<Territory> route) {}

  /** A sound clip the engine wants played (see {@code org.triplea.sound.SoundPath}). */
  default void playSound(final String clipName, final GamePlayer player) {}
}
