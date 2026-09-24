package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.display.IDisplay;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Die;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adapts the engine display channel to a {@link GameEventListener}. */
public class MobileDisplay implements IDisplay {
  private final GameEventListener listener;

  public MobileDisplay(final GameEventListener listener) {
    this.listener = listener;
  }

  @Override
  public void shutDown() {}

  @Override
  public void reportMessageToAll(
      final String message,
      final String title,
      final boolean doNotIncludeHost,
      final boolean doNotIncludeClients,
      final boolean doNotIncludeObservers) {
    if (!doNotIncludeHost) {
      listener.message(title, message);
    }
  }

  @Override
  public void reportMessageToPlayers(
      final Collection<GamePlayer> playersToSendTo,
      final Collection<GamePlayer> butNotThesePlayers,
      final String message,
      final String title) {
    listener.message(title, message);
  }

  @Override
  public void showBattle(
      final UUID battleId,
      final Territory location,
      final String battleTitle,
      final Collection<Unit> attackingUnits,
      final Collection<Unit> defendingUnits,
      final Collection<Unit> killedUnits,
      final Collection<Unit> attackingWaitingToDie,
      final Collection<Unit> defendingWaitingToDie,
      final Map<Unit, Collection<Unit>> dependentUnits,
      final GamePlayer attacker,
      final GamePlayer defender,
      final boolean isAmphibious,
      final BattleType battleType,
      final Collection<Unit> amphibiousLandAttackers) {
    listener.battleStarted(
        battleId,
        location,
        battleTitle,
        attackingUnits,
        defendingUnits,
        attacker,
        defender,
        battleType);
  }

  @Override
  public void listBattleSteps(final UUID battleId, final List<String> steps) {
    listener.battleSteps(battleId, steps);
  }

  @Override
  public void battleEnd(final UUID battleId, final String message) {
    listener.battleEnded(battleId, message);
  }

  @Override
  public void casualtyNotification(
      final UUID battleId,
      final String step,
      final DiceRoll dice,
      final GamePlayer player,
      final Collection<Unit> killed,
      final Collection<Unit> damaged,
      final Map<Unit, Collection<Unit>> dependents) {
    listener.casualties(battleId, step, dice, player, killed, damaged, dependents);
  }

  @Override
  public void deadUnitNotification(
      final UUID battleId,
      final GamePlayer player,
      final Collection<Unit> dead,
      final Map<Unit, Collection<Unit>> dependents) {
    listener.unitsDied(battleId, player, dead, dependents);
  }

  @Override
  public void changedUnitsNotification(
      final UUID battleId,
      final GamePlayer player,
      final Collection<Unit> removedUnits,
      final Collection<Unit> addedUnits,
      final Map<Unit, Collection<Unit>> dependents) {
    listener.unitsChanged(battleId, player, removedUnits, addedUnits);
  }

  @Override
  public void bombingResults(final UUID battleId, final List<Die> dice, final int cost) {
    listener.bombingResults(battleId, dice, cost);
  }

  @Override
  public void notifyRetreat(
      final String shortMessage,
      final String message,
      final String step,
      final GamePlayer retreatingPlayer) {
    listener.retreat(shortMessage, message, step, retreatingPlayer);
  }

  @Override
  public void notifyRetreat(final UUID battleId, final Collection<Unit> retreating) {
    listener.unitsRetreated(battleId, retreating);
  }

  @Override
  public void notifyDice(final DiceRoll dice, final String stepName) {
    listener.diceRolled(dice, stepName);
  }

  @Override
  public void gotoBattleStep(final UUID battleId, final String step) {
    listener.battleStep(battleId, step);
  }

  @Override
  public void unitsMoved(final GamePlayer player, final Collection<Unit> units, final Route route) {
    listener.unitsMoved(player, units, route.getAllTerritories());
  }
}
