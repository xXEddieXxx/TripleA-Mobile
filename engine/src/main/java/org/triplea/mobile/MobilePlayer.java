package org.triplea.mobile;

import games.strategy.engine.GameOverException;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.attachments.UserActionAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.GameStepPropertiesHelper;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.BattleListing;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.data.FightBattleDetails;
import games.strategy.triplea.delegate.data.TechResults;
import games.strategy.triplea.delegate.data.TechRoll;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IBattleDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPoliticsDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.delegate.remote.ITechDelegate;
import games.strategy.triplea.delegate.remote.IUserActionDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.player.AbstractBasePlayer;
import games.strategy.triplea.ui.PlaceData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.java.collections.IntegerMap;
import org.triplea.util.Tuple;

/**
 * Human player for the mobile client. This is a port of the desktop {@code TripleAPlayer} with the
 * Swing frame replaced by the {@link HumanPlayerUi} bridge. As a rule, nothing that changes
 * GameData should be in here; all changes are done through the remote delegates.
 */
@Slf4j
public class MobilePlayer extends AbstractBasePlayer {
  private final HumanPlayerUi ui;

  public MobilePlayer(final String name, final String playerLabel, final HumanPlayerUi ui) {
    super(name, playerLabel);
    this.ui = ui;
  }

  @Override
  public boolean isAi() {
    return false;
  }

  @Override
  public void start(final String name) {
    super.start(name);
    try {
      startImpl(name);
    } catch (final GameOverException e) {
      // Return cleanly.
    }
  }

  private void startImpl(final String name) {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    ui.startPhase(getGamePlayer(), name);

    boolean badStep = false;
    if (GameStep.isTechStepName(name)) {
      tech();
    } else if (GameStep.isPurchaseOrBidStepName(name)) {
      purchase(GameStepPropertiesHelper.isBid(getGameData()), false);
    } else if (GameStep.isMoveStepName(name)) {
      final boolean nonCombat = GameStepPropertiesHelper.isNonCombatMove(getGameData(), false);
      move(nonCombat, name);
    } else if (GameStep.isBattleStepName(name)) {
      battle();
    } else if (GameStep.isPlaceStepName(name)) {
      place();
    } else if (GameStep.isPoliticsStepName(name)) {
      politics(true);
    } else if (GameStep.isUserActionsStepName(name)) {
      userActions(true);
    } else if (GameStep.isEndTurnStepName(name)) {
      endTurn();
    } else {
      badStep = !GameStep.isTechActivationStepName(name);
    }
    if (badStep) {
      throw new IllegalArgumentException("Unrecognized step name: " + name);
    }
  }

  private <T extends IRemote> T remoteDelegate(final Class<T> type) {
    final IRemote remote = getPlayerBridge().getRemoteDelegate();
    if (!type.isInstance(remote)) {
      final String errorContext =
          "PlayerBridge step name: "
              + getPlayerBridge().getStepName()
              + ", Remote class name: "
              + remote.getClass()
              + ", expected: "
              + type.getName();
      log.error(errorContext);
      throw new IllegalStateException(errorContext);
    }
    return type.cast(remote);
  }

  private void politics(final boolean firstRun) {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final IPoliticsDelegate politicsDelegate = remoteDelegate(IPoliticsDelegate.class);
    final PoliticalActionAttachment actionChoice =
        ui.getPoliticalActionChoice(getGamePlayer(), firstRun, politicsDelegate);
    if (actionChoice != null) {
      politicsDelegate.attemptAction(actionChoice);
      politics(false);
    }
  }

  private void userActions(final boolean firstRun) {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final IUserActionDelegate userActionDelegate = remoteDelegate(IUserActionDelegate.class);
    final UserActionAttachment actionChoice =
        ui.getUserActionChoice(getGamePlayer(), firstRun, userActionDelegate);
    if (actionChoice != null) {
      userActionDelegate.attemptAction(actionChoice);
      userActions(false);
    }
  }

  private void tech() {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final ITechDelegate techDelegate = remoteDelegate(ITechDelegate.class);
    final TechRoll techRoll = ui.getTechRolls(getGamePlayer()).orElse(null);
    if (techRoll != null) {
      final TechResults techResults =
          techDelegate.rollTech(
              techRoll.getRolls(),
              techRoll.getTech(),
              techRoll.getNewTokens(),
              techRoll.getWhoPaysHowMuch());
      if (techResults.isError()) {
        ui.notifyError(techResults.getErrorString());
        tech();
      } else {
        ui.notifyTechResults(techResults);
      }
    }
  }

  private void move(final boolean nonCombat, final String stepName) {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final IMoveDelegate moveDel = remoteDelegate(IMoveDelegate.class);
    final GamePlayer gamePlayer = getGamePlayer();
    // getMove blocks until a move is made or the phase is finished. We loop until the UI signals
    // that the phase is done and the engine accepts that (all air can land etc).
    final MoveDescription moveDescription = ui.getMove(gamePlayer, nonCombat, stepName).orElse(null);
    if (moveDescription == null) {
      if (GameStepPropertiesHelper.isRemoveAirThatCanNotLand(getGameData())
          && !canAirLand(true, gamePlayer)) {
        move(nonCombat, stepName);
        return;
      }
      if (!nonCombat && canUnitsFight()) {
        move(false, stepName);
      }
      return;
    }
    moveDel.performMove(moveDescription).ifPresent(ui::notifyError);
    move(nonCombat, stepName);
  }

  private boolean canAirLand(final boolean movePhase, final GamePlayer player) {
    final Collection<Territory> airCantLand;
    if (movePhase) {
      airCantLand = remoteDelegate(IMoveDelegate.class).getTerritoriesWhereAirCantLand(player);
    } else {
      airCantLand = remoteDelegate(IAbstractPlaceDelegate.class).getTerritoriesWhereAirCantLand();
    }
    return airCantLand.isEmpty() || ui.okToLetAirDie(getGamePlayer(), airCantLand, movePhase);
  }

  private boolean canUnitsFight() {
    final Collection<Territory> unitsCantFight =
        remoteDelegate(IMoveDelegate.class).getTerritoriesWhereUnitsCantFight();
    return !(unitsCantFight.isEmpty() || ui.okToLetUnitsDie(unitsCantFight));
  }

  private void purchase(final boolean bid, final boolean keepCurrentPurchase) {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final GamePlayer gamePlayer = getGamePlayer();
    final GameData data = getGameData();
    final boolean isOnlyRepairIfDisabled = GameStepPropertiesHelper.isOnlyRepairIfDisabled(data);
    if (gamePlayer.getRepairFrontier() != null
        && gamePlayer.getRepairFrontier().getRules() != null
        && !gamePlayer.getRepairFrontier().getRules().isEmpty()
        && Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data.getProperties())) {
      Predicate<Unit> myDamaged =
          Matches.unitIsOwnedBy(gamePlayer).and(Matches.unitHasTakenSomeBombingUnitDamage());
      if (isOnlyRepairIfDisabled) {
        myDamaged = myDamaged.and(Matches.unitIsDisabled());
      }
      final Collection<Unit> damagedUnits = new ArrayList<>();
      for (final Territory t : data.getMap().getTerritories()) {
        damagedUnits.addAll(CollectionUtils.getMatches(t.getUnits(), myDamaged));
      }
      if (!damagedUnits.isEmpty()) {
        final Map<Unit, IntegerMap<RepairRule>> repair =
            ui.getRepair(
                    gamePlayer, bid, GameStepPropertiesHelper.getRepairPlayers(data, gamePlayer))
                .orElse(null);
        if (repair != null) {
          final IPurchaseDelegate purchaseDel = remoteDelegate(IPurchaseDelegate.class);
          final String error = purchaseDel.purchaseRepair(repair);
          if (error != null) {
            ui.notifyError(error);
            purchase(bid, true);
          }
        }
      }
    }
    if (isOnlyRepairIfDisabled) {
      return;
    }
    final IntegerMap<ProductionRule> prod =
        ui.getPurchase(gamePlayer, bid, keepCurrentPurchase).orElse(null);
    if (prod == null) {
      return;
    }
    final IPurchaseDelegate purchaseDel = remoteDelegate(IPurchaseDelegate.class);
    final String purchaseError = purchaseDel.purchase(prod);
    if (purchaseError != null) {
      ui.notifyError(purchaseError);
      purchase(bid, true);
    }
  }

  private void battle() {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final IBattleDelegate battleDel = remoteDelegate(IBattleDelegate.class);
    final GamePlayer gamePlayer = getGamePlayer();
    while (true) {
      if (getPlayerBridge().isGameOver()) {
        return;
      }
      final BattleListing battleListing = battleDel.getBattleListing();
      if (battleListing.isEmpty()) {
        return;
      }
      final FightBattleDetails details = ui.chooseBattle(gamePlayer, battleListing).orElse(null);
      if (getPlayerBridge().isGameOver()) {
        return;
      }
      if (details != null) {
        final String error =
            battleDel.fightBattle(
                details.getWhere(), details.isBombingRaid(), details.getBattleType());
        if (error != null) {
          ui.notifyError(error);
        }
      }
    }
  }

  private void place() {
    final boolean bid = GameStepPropertiesHelper.isBid(getGameData());
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    final GamePlayer gamePlayer = getGamePlayer();
    final IAbstractPlaceDelegate placeDel = remoteDelegate(IAbstractPlaceDelegate.class);
    while (true) {
      final PlaceData placeData = ui.getPlacement(gamePlayer, bid).orElse(null);
      if (placeData == null) {
        if (!GameStepPropertiesHelper.isRemoveAirThatCanNotLand(getGameData())
            || canAirLand(false, gamePlayer)
            || getPlayerBridge().isGameOver()) {
          return;
        }
        continue;
      }
      placeDel
          .placeUnits(
              placeData.getUnits(),
              placeData.getAt(),
              bid ? IAbstractPlaceDelegate.BidMode.BID : IAbstractPlaceDelegate.BidMode.NOT_BID)
          .ifPresent(ui::notifyError);
    }
  }

  private void endTurn() {
    if (getPlayerBridge().isGameOver()) {
      return;
    }
    ui.waitForEndTurn(getGamePlayer());
  }

  @Override
  public CasualtyDetails selectCasualties(
      final Collection<Unit> selectFrom,
      final Map<Unit, Collection<Unit>> dependents,
      final int count,
      final String message,
      final DiceRoll dice,
      final GamePlayer hit,
      final Collection<Unit> friendlyUnits,
      final Collection<Unit> enemyUnits,
      final boolean amphibious,
      final Collection<Unit> amphibiousLandAttackers,
      final CasualtyList defaultCasualties,
      final UUID battleId,
      final Territory battleTerritory,
      final boolean allowMultipleHitsPerUnit) {
    return ui.selectCasualties(
        selectFrom,
        dependents,
        count,
        message,
        dice,
        hit,
        defaultCasualties,
        battleId,
        allowMultipleHitsPerUnit);
  }

  @Override
  public int[] selectFixedDice(
      final int numDice, final int hitAt, final String title, final int diceSides) {
    return ui.selectFixedDice(numDice, hitAt, title, diceSides);
  }

  @Override
  public Territory selectBombardingTerritory(
      final Unit unit,
      final Territory unitTerritory,
      final Collection<Territory> territories,
      final boolean noneAvailable) {
    return ui.selectTerritory(
        territories,
        "Bombardment",
        "Which territory should " + unit.getType().getName() + " in " + unitTerritory + " bombard?",
        true);
  }

  @Override
  public boolean selectAttackSubs(final Territory unitTerritory) {
    return ui.confirm("Attack submarines", "Attack submarines in " + unitTerritory + "?");
  }

  @Override
  public boolean selectAttackTransports(final Territory unitTerritory) {
    return ui.confirm("Attack transports", "Attack transports in " + unitTerritory + "?");
  }

  @Override
  public boolean selectAttackUnits(final Territory unitTerritory) {
    return ui.confirm("Attack units", "Attack units in " + unitTerritory + "?");
  }

  @Override
  public boolean selectShoreBombard(final Territory unitTerritory) {
    return ui.confirm("Shore bombardment", "Conduct shore bombardment in " + unitTerritory + "?");
  }

  @Override
  public void reportError(final String error) {
    ui.notifyError(error);
  }

  @Override
  public void reportMessage(final String message, final String title) {
    ui.notifyMessage(message, title);
  }

  @Override
  public boolean shouldBomberBomb(final Territory territory) {
    return ui.confirm(
        "Strategic bombing raid", "Conduct a strategic bombing raid in " + territory + "?");
  }

  @Override
  public Unit whatShouldBomberBomb(
      final Territory territory,
      final Collection<Unit> potentialTargets,
      final Collection<Unit> bombers) {
    final Collection<Unit> chosen =
        ui.selectUnits(potentialTargets, "Bombing target", "Select the unit to bomb", 1);
    return chosen.stream().findFirst().orElse(potentialTargets.iterator().next());
  }

  @Override
  public Territory whereShouldRocketsAttack(
      final Collection<Territory> candidates, final Territory from) {
    return ui.selectTerritory(
        candidates, "Rocket attack", "Where should rockets from " + from + " attack?", true);
  }

  @Override
  public Collection<Unit> getNumberOfFightersToMoveToNewCarrier(
      final Collection<Unit> fightersThatCanBeMoved, final Territory from) {
    return ui.selectUnits(
        fightersThatCanBeMoved,
        "Move fighters to new carrier",
        "Select fighters in " + from + " to move to the new carrier",
        fightersThatCanBeMoved.size());
  }

  @Override
  public Territory selectTerritoryForAirToLand(
      final Collection<Territory> candidates,
      final Territory currentTerritory,
      final String unitMessage) {
    final Territory selected =
        ui.selectTerritory(candidates, "Select territory for air units to land", unitMessage, false);
    return selected != null ? selected : candidates.iterator().next();
  }

  @Override
  public boolean confirmMoveInFaceOfAa(final Collection<Territory> aaFiringTerritories) {
    final String question =
        "Your units will be fired on in: "
            + MyFormatter.defaultNamedToTextList(aaFiringTerritories)
            + ".  Do you still want to move?";
    return ui.confirm("Anti-aircraft fire", question);
  }

  @Override
  public boolean confirmMoveKamikaze() {
    final String question =
        "Not all air units in destination territory can land, do you still want to move?";
    return ui.confirm("Kamikaze move", question);
  }

  @Override
  public boolean acceptAction(
      final GamePlayer playerSendingProposal,
      final String acceptanceQuestion,
      final boolean politics) {
    return !getGamePlayer().amNotDeadYet()
        || getPlayerBridge().isGameOver()
        || ui.acceptAction(
            playerSendingProposal,
            "To " + getGamePlayer().getName() + ": " + acceptanceQuestion,
            politics);
  }

  @Override
  public Optional<Territory> retreatQuery(
      final UUID battleId,
      final boolean submerge,
      final Territory battleTerritory,
      final Collection<Territory> possibleTerritories,
      final String message) {
    return ui.retreatQuery(battleId, submerge, battleTerritory, possibleTerritories, message);
  }

  @Override
  public Map<Territory, Collection<Unit>> scrambleUnitsQuery(
      final Territory scrambleTo,
      final Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers) {
    return ui.scrambleUnitsQuery(scrambleTo, possibleScramblers);
  }

  @Override
  public Collection<Unit> selectUnitsQuery(
      final Territory current, final Collection<Unit> possible, final String message) {
    return ui.selectUnits(possible, "Select units in " + current, message, possible.size());
  }

  @Override
  public void confirmEnemyCasualties(
      final UUID battleId, final String message, final GamePlayer hitPlayer) {
    // mobile players do not confirm the enemy's casualties, the battle screen shows them
  }

  @Override
  public void confirmOwnCasualties(final UUID battleId, final String message) {
    ui.confirmCasualties(battleId, message);
  }

  @Override
  public @Nullable Map<Territory, Map<Unit, IntegerMap<Resource>>> selectKamikazeSuicideAttacks(
      final Map<Territory, Collection<Unit>> possibleUnitsToAttack) {
    final GamePlayer gamePlayer = getGamePlayer();
    final PlayerAttachment pa = PlayerAttachment.get(gamePlayer);
    if (pa == null) {
      return null;
    }
    final IntegerMap<Resource> resourcesAndAttackValues = pa.getSuicideAttackResources();
    if (resourcesAndAttackValues.isEmpty()) {
      return null;
    }
    final IntegerMap<Resource> playerResourceCollection =
        gamePlayer.getResources().getResourcesCopy();
    final IntegerMap<Resource> attackTokens = new IntegerMap<>();
    for (final Resource possible : resourcesAndAttackValues.keySet()) {
      final int amount = playerResourceCollection.getInt(possible);
      if (amount > 0) {
        attackTokens.put(possible, amount);
      }
    }
    if (attackTokens.isEmpty()) {
      return null;
    }
    final Map<Territory, Map<Unit, IntegerMap<Resource>>> kamikazeSuicideAttacks = new HashMap<>();
    for (final Entry<Resource, Integer> entry : attackTokens.entrySet()) {
      final Resource resource = entry.getKey();
      final int max = entry.getValue();
      final Map<Territory, IntegerMap<Unit>> selection =
          ui.selectKamikazeSuicideAttacks(possibleUnitsToAttack, resource, max);
      for (final Entry<Territory, IntegerMap<Unit>> selectionEntry : selection.entrySet()) {
        final Territory territory = selectionEntry.getKey();
        final Map<Unit, IntegerMap<Resource>> currentTerr =
            kamikazeSuicideAttacks.computeIfAbsent(territory, key -> new HashMap<>());
        for (final Entry<Unit, Integer> unitEntry : selectionEntry.getValue().entrySet()) {
          final Unit unit = unitEntry.getKey();
          final Integer amount = unitEntry.getValue();
          currentTerr.computeIfAbsent(unit, key -> new IntegerMap<>()).add(resource, amount);
        }
      }
    }
    return kamikazeSuicideAttacks;
  }

  @Override
  public Tuple<Territory, Set<Unit>> pickTerritoryAndUnits(
      final List<Territory> territoryChoices,
      final List<Unit> unitChoices,
      final int unitsPerPick) {
    if (territoryChoices == null || territoryChoices.isEmpty() || unitsPerPick < 1) {
      return Tuple.of(null, new HashSet<>());
    }
    return ui.pickTerritoryAndUnits(getGamePlayer(), territoryChoices, unitChoices, unitsPerPick);
  }
}
