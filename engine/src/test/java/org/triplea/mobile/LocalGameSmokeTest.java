package org.triplea.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs an AI-only game on the bundled minimap for a couple of rounds. This proves the engine works
 * without any AWT/Swing classes on the class path and that map loading, parsing, delegates and the
 * AI all function in the trimmed engine.
 */
class LocalGameSmokeTest {
  @TempDir static Path userRoot;

  @BeforeAll
  static void installMinimap() throws IOException, URISyntaxException {
    MobileEngine.configure(userRoot);
    final Path source =
        Path.of(LocalGameSmokeTest.class.getResource("/maps/minimap").toURI());
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
  void installedGamesAreListed() {
    assertThat(MobileEngine.listInstalledGames())
        .extracting(MobileEngine.InstalledGame::getGameName)
        .contains("Minimap");
  }

  @Test
  void aiOnlyGameRunsSeveralRounds() throws IOException {
    final Path xml = MobileEngine.listInstalledGames().get(0).getXmlPath();
    final GameData gameData = MobileEngine.parseGame(xml).orElseThrow();
    assertThat(gameData.getMapName()).isEqualTo("minimap");

    final NoOpEvents events = new NoOpEvents();
    final LocalGameSession session =
        LocalGameSession.create(gameData, Map.of(), new NoInputUi(), events);
    assertThat(session.getMapData().getTerritories()).isNotEmpty();
    assertThat(session.getMapData().getMapDimensions().width).isEqualTo(900);

    session.setUpForSteps();
    int steps = 0;
    while (gameData.getSequence().getRound() < 3 && steps < 400 && !session.isGameOver()) {
      session.runNextStep();
      steps++;
    }
    assertThat(gameData.getSequence().getRound()).isGreaterThanOrEqualTo(3);
    assertThat(events.steps).isGreaterThan(0);

    final Path save = MobileEngine.getSaveGamesFolder().resolve("smoke.tsvg");
    MobileEngine.saveGame(gameData, save);
    final Optional<GameData> reloaded = MobileEngine.loadSaveGame(save);
    assertThat(reloaded).isPresent();
    assertThat(reloaded.get().getSequence().getRound())
        .isEqualTo(gameData.getSequence().getRound());
    assertThat(MobileEngine.listSaveGames()).contains(save);

    session.stop();
  }

  /** Human UI that is never used because every player is an AI. */
  private static final class NoInputUi extends HumanPlayerUiAdapter {
    @Override
    public Optional<org.triplea.java.collections.IntegerMap<games.strategy.engine.data.ProductionRule>>
        getPurchase(final GamePlayer player, final boolean bid, final boolean keepCurrentPurchase) {
      throw new IllegalStateException("no human players expected");
    }

    @Override
    public Optional<games.strategy.engine.data.MoveDescription> getMove(
        final GamePlayer player, final boolean nonCombat, final String stepName) {
      throw new IllegalStateException("no human players expected");
    }

    @Override
    public Optional<games.strategy.triplea.delegate.data.FightBattleDetails> chooseBattle(
        final GamePlayer player, final games.strategy.triplea.delegate.data.BattleListing battles) {
      throw new IllegalStateException("no human players expected");
    }

    @Override
    public Optional<games.strategy.triplea.ui.PlaceData> getPlacement(
        final GamePlayer player, final boolean bid) {
      throw new IllegalStateException("no human players expected");
    }

    @Override
    public void waitForEndTurn(final GamePlayer player) {
      throw new IllegalStateException("no human players expected");
    }

    @Override
    public void notifyError(final String error) {
      throw new IllegalStateException(error);
    }

    @Override
    public void notifyMessage(final String message, final String title) {}

    @Override
    public boolean confirm(final String title, final String question) {
      return true;
    }
  }

  private static final class NoOpEvents implements GameEventListener {
    int steps;

    @Override
    public void gameStepChanged(
        final String stepName, final String displayName, final GamePlayer player, final int round) {
      steps++;
    }
  }
}
