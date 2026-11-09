package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.triplea.mobile.forum.NodeBbClient.GameThread;

class GameSearchTest {
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

  private static GameThread game(
      final int tid, final String title, final String poster, final String turn, final int daysAgo) {
    return new GameThread(
        tid, "", title, NOW.minus(Duration.ofDays(daysAgo)), 0, turn, poster, false, false, Forum.TRIPLEA);
  }

  private final List<GameThread> games =
      List.of(
          game(1, "Global 1940: Ann vs Bob", "bob", "Germans, round 5", 1),
          game(2, "Pacific league", "carl", "", 10),
          game(3, "Old Global game", "dan", "", 61));

  @Test
  void inactiveGamesAreHiddenUnlessAsked() {
    assertThat(GameSearch.matching(games, "", false, NOW))
        .extracting(GameThread::topicId)
        .containsExactly(1, 2);
    assertThat(GameSearch.matching(games, "", true, NOW))
        .extracting(GameThread::topicId)
        .containsExactly(1, 2, 3);
  }

  @Test
  void searchMatchesTitlePosterAndTurnIgnoringCase() {
    assertThat(GameSearch.matching(games, "global", true, NOW))
        .extracting(GameThread::topicId)
        .containsExactly(1, 3);
    assertThat(GameSearch.matching(games, "CARL", false, NOW))
        .extracting(GameThread::topicId)
        .containsExactly(2);
    assertThat(GameSearch.matching(games, "germans", false, NOW))
        .extracting(GameThread::topicId)
        .containsExactly(1);
  }

  @Test
  void browseReadsFurtherPagesUntilTheLast() throws IOException {
    final FakeForum forum =
        new FakeForum()
            .answer(
                "api/category/45?sort=recently_replied&page=1",
                "{\"topics\": [{\"tid\": 1, \"title\": \"A\", \"lastposttime\": 20}],"
                    + " \"pagination\": {\"pageCount\": 2}}")
            .answer(
                "api/category/45?sort=recently_replied&page=2",
                "{\"topics\": [{\"tid\": 2, \"title\": \"B\", \"lastposttime\": 10},"
                    + " {\"tid\": 1, \"title\": \"A\", \"lastposttime\": 20}],"
                    + " \"pagination\": {\"pageCount\": 2}}");

    final List<GameThread> found = new NodeBbClient(forum, Forum.TRIPLEA, "s").browse(Forum.TRIPLEA.getGameCategories().get(0));

    assertThat(found).extracting(GameThread::topicId).containsExactly(1, 2);
    assertThat(forum.requests).hasSize(2);
  }
}
