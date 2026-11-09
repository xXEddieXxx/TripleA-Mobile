package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.mobile.MobileEngine;

/** The minimap: Russians, then Italians, then the round's end step; enemies unless a test allies them. */
class PbfTurnTest {
  @TempDir static Path userRoot;

  @BeforeAll
  static void installMinimap() throws IOException, URISyntaxException {
    MobileEngine.configure(userRoot);
    final Path source = Path.of(PbfTurnTest.class.getResource("/maps/minimap").toURI());
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

  private static GameData minimap() {
    return MobileEngine.parseGame(MobileEngine.listInstalledGames().get(0).getXmlPath())
        .orElseThrow();
  }

  private static int stepIndex(final GameData data, final String name) {
    for (int i = 0; i < data.getSequence().size(); i++) {
      if (name.equals(data.getSequence().getStep(i).getName())) {
        return i;
      }
    }
    throw new IllegalArgumentException(name);
  }

  @Test
  void anEnemyNextIsNoAlly() {
    final GameData data = minimap();
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");

    assertThat(PbfTurn.nextAlly(data, russians, stepIndex(data, "russianEndTurn"))).isEmpty();
  }

  @Test
  void anAllyNextIsNamedAlsoAcrossTheRoundsEnd() {
    final GameData data = minimap();
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
    data.getRelationshipTracker()
        .setRelationship(
            russians, italians, data.getRelationshipTypeList().getDefaultAlliedRelationship());

    assertThat(PbfTurn.nextAlly(data, russians, stepIndex(data, "russianEndTurn")))
        .contains(italians);
    // the Italians end the round: the Russians start the next one
    assertThat(PbfTurn.nextAlly(data, italians, stepIndex(data, "italianEndTurn")))
        .contains(russians);
  }
}
