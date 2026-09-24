package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.attachments.UserActionAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.data.BattleListing;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.data.FightBattleDetails;
import games.strategy.triplea.delegate.data.TechResults;
import games.strategy.triplea.delegate.data.TechRoll;
import games.strategy.triplea.delegate.remote.IPoliticsDelegate;
import games.strategy.triplea.delegate.remote.IUserActionDelegate;
import games.strategy.triplea.ui.PlaceData;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Tuple;

/**
 * Everything the engine needs to ask a human player. The mobile app implements this interface.
 *
 * <p>All methods are invoked on the game thread and are expected to block until the user has
 * answered. Implementations typically hand the question to the UI and wait on a future. Returning
 * an empty optional from the phase methods ({@link #getMove}, {@link #getPlacement}, ...) means the
 * player is done with that phase.
 */
public interface HumanPlayerUi {

  /** Called when a phase of the given player begins. Purely informational. */
  void startPhase(GamePlayer player, String stepName);

  /** Ask which technology to roll for. Empty means no tech roll this turn. */
  Optional<TechRoll> getTechRolls(GamePlayer player);

  void notifyTechResults(TechResults results);

  /** Ask what damaged units should be repaired. Empty means no repairs. */
  Optional<Map<Unit, IntegerMap<RepairRule>>> getRepair(
      GamePlayer player, boolean bid, Collection<GamePlayer> allowedPlayersToRepair);

  /** Ask what to buy. Empty means nothing is bought. */
  Optional<IntegerMap<ProductionRule>> getPurchase(
      GamePlayer player, boolean bid, boolean keepCurrentPurchase);

  /**
   * Ask for the next move. Blocks until the user either performed a move (returns the move
   * description) or finished the move phase (returns empty).
   */
  Optional<MoveDescription> getMove(GamePlayer player, boolean nonCombat, String stepName);

  boolean okToLetAirDie(GamePlayer player, Collection<Territory> airCantLand, boolean movePhase);

  boolean okToLetUnitsDie(Collection<Territory> unitsCantFight);

  /** Ask which of the pending battles to fight next. Empty is only allowed if none is left. */
  Optional<FightBattleDetails> chooseBattle(GamePlayer player, BattleListing battles);

  /** Ask where to place purchased units. Empty means placement is finished. */
  Optional<PlaceData> getPlacement(GamePlayer player, boolean bid);

  /** Blocks until the user confirms the end of the turn. */
  void waitForEndTurn(GamePlayer player);

  @Nullable
  PoliticalActionAttachment getPoliticalActionChoice(
      GamePlayer player, boolean firstRun, IPoliticsDelegate delegate);

  @Nullable
  UserActionAttachment getUserActionChoice(
      GamePlayer player, boolean firstRun, IUserActionDelegate delegate);

  void notifyError(String error);

  void notifyMessage(String message, String title);

  /** Generic yes/no question. */
  boolean confirm(String title, String question);

  /**
   * A yes/no question about the battle in {@code territory} (attack submarines, bombard, raid),
   * so the UI can ask it inside the battle window instead of a dialog.
   */
  default boolean confirmInBattle(
      final Territory territory, final String title, final String question) {
    return confirm(title, question);
  }

  /** A unit choice about the battle in {@code territory}, e.g. the target of a bombing raid. */
  default Collection<Unit> selectUnitsInBattle(
      final Territory territory,
      final Collection<Unit> candidates,
      final String title,
      final String message,
      final int max) {
    return selectUnits(candidates, title, message, max);
  }

  CasualtyDetails selectCasualties(
      Collection<Unit> selectFrom,
      Map<Unit, Collection<Unit>> dependents,
      int count,
      String message,
      DiceRoll dice,
      GamePlayer hit,
      CasualtyList defaultCasualties,
      UUID battleId,
      boolean allowMultipleHitsPerUnit);

  int[] selectFixedDice(int numDice, int hitAt, String title, int diceSides);

  /** Generic territory choice. Returns null only if {@code noneAllowed} is true. */
  @Nullable
  Territory selectTerritory(
      Collection<Territory> candidates, String title, String message, boolean noneAllowed);

  /** Generic unit choice, at most {@code max} units. */
  Collection<Unit> selectUnits(Collection<Unit> candidates, String title, String message, int max);

  Optional<Territory> retreatQuery(
      UUID battleId,
      boolean submerge,
      Territory battleTerritory,
      Collection<Territory> possibleTerritories,
      String message);

  Map<Territory, Collection<Unit>> scrambleUnitsQuery(
      Territory scrambleTo,
      Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers);

  void confirmCasualties(UUID battleId, String message);

  boolean acceptAction(GamePlayer playerSendingProposal, String question, boolean politics);

  Map<Territory, IntegerMap<Unit>> selectKamikazeSuicideAttacks(
      Map<Territory, Collection<Unit>> possibleUnitsToAttack,
      Resource attackResourceToken,
      int maxNumberOfAttacksAllowed);

  Tuple<Territory, Set<Unit>> pickTerritoryAndUnits(
      GamePlayer player, List<Territory> territoryChoices, List<Unit> unitChoices, int unitsPerPick);
}
