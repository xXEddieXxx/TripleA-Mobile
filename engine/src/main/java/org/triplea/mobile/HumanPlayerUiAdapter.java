package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.attachments.UserActionAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.data.TechResults;
import games.strategy.triplea.delegate.data.TechRoll;
import games.strategy.triplea.delegate.remote.IPoliticsDelegate;
import games.strategy.triplea.delegate.remote.IUserActionDelegate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Tuple;

/**
 * Convenience base class with sensible defaults for the rarely used questions of {@link
 * HumanPlayerUi}. A UI only needs to implement the core phase interactions: purchase, move,
 * battle choice, placement, end turn and the notification methods.
 */
public abstract class HumanPlayerUiAdapter implements HumanPlayerUi {
  private final Random random = new Random();

  @Override
  public void startPhase(final GamePlayer player, final String stepName) {}

  @Override
  public Optional<TechRoll> getTechRolls(final GamePlayer player) {
    return Optional.empty();
  }

  @Override
  public void notifyTechResults(final TechResults results) {
    notifyMessage(String.valueOf(results), "Technology");
  }

  @Override
  public Optional<Map<Unit, IntegerMap<RepairRule>>> getRepair(
      final GamePlayer player,
      final boolean bid,
      final Collection<GamePlayer> allowedPlayersToRepair) {
    return Optional.empty();
  }

  @Override
  public boolean okToLetAirDie(
      final GamePlayer player, final Collection<Territory> airCantLand, final boolean movePhase) {
    return confirm(
        "Air units cannot land",
        "Air units in " + airCantLand + " cannot land and will be lost. Continue anyway?");
  }

  @Override
  public boolean okToLetUnitsDie(final Collection<Territory> unitsCantFight) {
    return confirm(
        "Units cannot fight",
        "Units in " + unitsCantFight + " cannot fight and will be lost. Continue anyway?");
  }

  @Override
  @Nullable
  public PoliticalActionAttachment getPoliticalActionChoice(
      final GamePlayer player, final boolean firstRun, final IPoliticsDelegate delegate) {
    return null;
  }

  @Override
  @Nullable
  public UserActionAttachment getUserActionChoice(
      final GamePlayer player, final boolean firstRun, final IUserActionDelegate delegate) {
    return null;
  }

  @Override
  public CasualtyDetails selectCasualties(
      final Collection<Unit> selectFrom,
      final Map<Unit, Collection<Unit>> dependents,
      final int count,
      final String message,
      final DiceRoll dice,
      final GamePlayer hit,
      final CasualtyList defaultCasualties,
      final UUID battleId,
      final boolean allowMultipleHitsPerUnit) {
    return new CasualtyDetails(defaultCasualties, true);
  }

  @Override
  public int[] selectFixedDice(
      final int numDice, final int hitAt, final String title, final int diceSides) {
    final int[] dice = new int[numDice];
    for (int i = 0; i < numDice; i++) {
      dice[i] = random.nextInt(diceSides);
    }
    return dice;
  }

  @Override
  @Nullable
  public Territory selectTerritory(
      final Collection<Territory> candidates,
      final String title,
      final String message,
      final boolean noneAllowed) {
    return candidates.stream().findFirst().orElse(null);
  }

  @Override
  public Collection<Unit> selectUnits(
      final Collection<Unit> candidates, final String title, final String message, final int max) {
    final List<Unit> selected = new ArrayList<>();
    for (final Unit unit : candidates) {
      if (selected.size() >= max) {
        break;
      }
      selected.add(unit);
    }
    return selected;
  }

  @Override
  public Optional<Territory> retreatQuery(
      final UUID battleId,
      final boolean submerge,
      final Territory battleTerritory,
      final Collection<Territory> possibleTerritories,
      final String message) {
    return Optional.empty();
  }

  @Override
  public Map<Territory, Collection<Unit>> scrambleUnitsQuery(
      final Territory scrambleTo,
      final Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers) {
    return Map.of();
  }

  @Override
  public void confirmCasualties(final UUID battleId, final String message) {}

  @Override
  public boolean acceptAction(
      final GamePlayer playerSendingProposal, final String question, final boolean politics) {
    return confirm("Proposal from " + playerSendingProposal.getName(), question);
  }

  @Override
  public Map<Territory, IntegerMap<Unit>> selectKamikazeSuicideAttacks(
      final Map<Territory, Collection<Unit>> possibleUnitsToAttack,
      final Resource attackResourceToken,
      final int maxNumberOfAttacksAllowed) {
    return Map.of();
  }

  @Override
  public Tuple<Territory, Set<Unit>> pickTerritoryAndUnits(
      final GamePlayer player,
      final List<Territory> territoryChoices,
      final List<Unit> unitChoices,
      final int unitsPerPick) {
    final Territory territory = territoryChoices.isEmpty() ? null : territoryChoices.get(0);
    final Set<Unit> units = new HashSet<>();
    for (final Unit unit : unitChoices) {
      if (units.size() >= unitsPerPick) {
        break;
      }
      units.add(unit);
    }
    return Tuple.of(territory, units);
  }
}
