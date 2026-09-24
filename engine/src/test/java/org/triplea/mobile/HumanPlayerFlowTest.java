package org.triplea.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.data.BattleListing;
import games.strategy.triplea.delegate.data.FightBattleDetails;
import games.strategy.triplea.ui.PlaceData;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.java.collections.IntegerMap;

/**
 * Drives a game where one nation is controlled by a scripted "human": it buys one unit per turn,
 * never moves, fights any pending battle, places what it can in the first territory the engine
 * accepts and ends its turn. This exercises the {@link MobilePlayer} phase handling end to end.
 */
class HumanPlayerFlowTest {
  @TempDir static Path userRoot;

  @BeforeAll
  static void installMinimap() throws IOException, URISyntaxException {
    MobileEngine.configure(userRoot);
    final Path source = Path.of(HumanPlayerFlowTest.class.getResource("/maps/minimap").toURI());
    final Path target = MobileEngine.getMapsFolder().resolve("minimap");
    try (Stream<Path> paths = Files.walk(source)) {
      for (final Path path : paths.toList()) {
        final Path dest = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(dest);
        } else {
          Files.copy(path, dest);
        }
      }
    }
  }

  @Test
  void scriptedHumanPlaysTwoRounds() {
    final Path xml = MobileEngine.listInstalledGames().get(0).getXmlPath();
    final GameData gameData = MobileEngine.parseGame(xml).orElseThrow();
    final String humanNation = gameData.getPlayerList().getPlayers().get(0).getName();

    final ScriptedHuman human = new ScriptedHuman();
    final LocalGameSession session =
        LocalGameSession.create(
            gameData, Map.of(humanNation, PlayerKind.HUMAN), human, new GameEventListener() {});

    session.setUpForSteps();
    int steps = 0;
    while (gameData.getSequence().getRound() < 3 && steps < 400 && !session.isGameOver()) {
      session.runNextStep();
      steps++;
    }
    session.stop();

    assertThat(gameData.getSequence().getRound()).isGreaterThanOrEqualTo(3);
    assertThat(human.purchases).as("human purchase phases").isGreaterThanOrEqualTo(2);
    assertThat(human.movePhases).as("human move phases").isGreaterThanOrEqualTo(2);
    assertThat(human.endTurns).as("human end turns").isGreaterThanOrEqualTo(2);
    assertThat(human.errors).as("engine rejected a human action").isEmpty();
  }

  private static final class ScriptedHuman extends HumanPlayerUiAdapter {
    int purchases;
    int movePhases;
    int endTurns;
    final List<String> errors = new ArrayList<>();

    @Override
    public Optional<IntegerMap<ProductionRule>> getPurchase(
        final GamePlayer player, final boolean bid, final boolean keepCurrentPurchase) {
      purchases++;
      final IntegerMap<ProductionRule> purchase = new IntegerMap<>();
      for (final ProductionRule rule : player.getProductionFrontier()) {
        final boolean affordable =
            rule.getCosts().entrySet().stream()
                .allMatch(e -> player.getResources().getQuantity(e.getKey()) >= e.getValue());
        if (affordable && rule.getResults().totalValues() > 0) {
          purchase.put(rule, 1);
          break;
        }
      }
      return purchase.isEmpty() ? Optional.empty() : Optional.of(purchase);
    }

    @Override
    public Optional<MoveDescription> getMove(
        final GamePlayer player, final boolean nonCombat, final String stepName) {
      movePhases++;
      return Optional.empty();
    }

    @Override
    public Optional<FightBattleDetails> chooseBattle(
        final GamePlayer player, final BattleListing battles) {
      return battles.getBattlesMap().entrySet().stream()
          .flatMap(e -> e.getValue().stream().map(t -> Map.entry(e.getKey(), t)))
          .findFirst()
          .map(
              e ->
                  FightBattleDetails.builder()
                      .where(e.getValue())
                      .bombingRaid(e.getKey().isBombingRun())
                      .battleType(e.getKey())
                      .build());
    }

    @Override
    public Optional<PlaceData> getPlacement(final GamePlayer player, final boolean bid) {
      final List<Unit> toPlace = new ArrayList<>(player.getUnitCollection().getUnits());
      if (toPlace.isEmpty()) {
        return Optional.empty();
      }
      // place everything in the first owned territory that has a factory
      for (final Territory territory : player.getData().getMap().getTerritories()) {
        if (territory.getOwner().equals(player)
            && territory.getUnits().stream()
                .anyMatch(u -> u.getUnitAttachment().canProduceUnits())) {
          return Optional.of(new PlaceData(toPlace, territory));
        }
      }
      return Optional.empty();
    }

    @Override
    public void waitForEndTurn(final GamePlayer player) {
      endTurns++;
    }

    @Override
    public void notifyError(final String error) {
      errors.add(error);
    }

    @Override
    public void notifyMessage(final String message, final String title) {}

    @Override
    public boolean confirm(final String title, final String question) {
      return true;
    }

    @SuppressWarnings("unused")
    private static int total(final IntegerMap<Resource> map) {
      return map.totalValues();
    }
  }
}
