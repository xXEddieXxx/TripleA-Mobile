package games.strategy.engine.data;

import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.ServerGame;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Not a test: writes the TripleA Mobile fixture save. Run by generate-desktop-save.sh. */
class WriteDesktopSaveTest {
  @BeforeAll
  static void setUp() throws Exception {
    GameTestUtils.setUp();
  }

  @Test
  void writeSave() throws Exception {
    final ServerGame game = GameTestUtils.setUpGameWithAis("minimap.xml");
    try {
      game.setStopGameOnDelegateExecutionStop(true);
      while (!game.isGameOver() && game.getData().getSequence().getRound() < 2) {
        game.runNextStep();
      }
      // a few steps into round 2, so the save is taken mid-turn
      for (int i = 0; i < 3 && !game.isGameOver(); i++) {
        game.runNextStep();
      }
      final int round = game.getData().getSequence().getRound();
      if (game.isGameOver() || round != 2) {
        throw new IllegalStateException("expected a running game in round 2, got round " + round);
      }
      try (OutputStream out =
          Files.newOutputStream(
              Path.of(
                  Objects.requireNonNull(
                      System.getenv("DESKTOP_SAVE_OUT"), "run via generate-desktop-save.sh")))) {
        GameDataManager.saveGame(out, game.getData());
      }
    } finally {
      game.stopGame();
    }
  }
}
