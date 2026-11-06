package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplea.mobile.forum.NodeBbClient.GameThread;
import org.triplea.mobile.forum.NodeBbClient.SaveGame;

/** axisandallies.org: a NodeBB below "/forums/", its games in "Play Boardgames" (16). */
class AxisAndAlliesForumTest {
  private final List<Http.Request> asked = new ArrayList<>();
  private final Http forum =
      request -> {
        asked.add(request);
        final String body =
            request.url().endsWith("api/config")
                ? "{\"uid\": 3, \"csrf_token\": \"t\"}"
                : "{\"topics\": [{\"tid\": 9, \"slug\": \"9/g40\", \"title\": \"G40\", \"cid\": 16}],"
                    + " \"response\": {\"tid\": 9, \"slug\": \"9/g40\", \"title\": \"G40\"}}";
        return new Http.Response(200, body, Map.of());
      };
  private final NodeBbClient client = new NodeBbClient(forum, Forum.AXIS_AND_ALLIES, "s");

  @Test
  void browseAndNewGamesUseItsGameCategoryBelowForums() throws IOException {
    final List<GameThread> games = client.browse();
    client.startThread("G40", "Who joins?");

    assertThat(asked.get(0).url())
        .isEqualTo("https://www.axisandallies.org/forums/api/category/16?sort=recently_replied&page=1");
    assertThat(new String(asked.get(asked.size() - 1).body(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"cid\":16");
    assertThat(games).singleElement().extracting(GameThread::forum).isEqualTo(Forum.AXIS_AND_ALLIES);
  }

  @Test
  void uploadsBelowForumsAreSaveGames() {
    // the upload answer of axisandallies.org, as desktop's NodeBbForumPosterTest has it
    final String post =
        "<a href=\"/forums/assets/uploads/files/1665368873282-triplea_20781_1car.tsvg\">Savegame</a>";

    assertThat(NodeBbClient.saveGamesIn(post, Forum.AXIS_AND_ALLIES.uri()))
        .extracting(SaveGame::url)
        .containsExactly(
            URI.create(
                "https://www.axisandallies.org/forums/assets/uploads/files/1665368873282-triplea_20781_1car.tsvg"));
  }

  @Test
  void desktopForumNamesAreKnown() {
    assertThat(Forum.ofPosterName("www.axisandallies.org/forums/")).contains(Forum.AXIS_AND_ALLIES);
    assertThat(Forum.ofPosterName("forums.triplea-game.org")).contains(Forum.TRIPLEA);
    assertThat(Forum.keeping(URI.create("https://www.axisandallies.org/forums/assets/x.png")))
        .contains(Forum.AXIS_AND_ALLIES);
    assertThat(Forum.keeping(URI.create("https://www.axisandallies.org/other/x.png"))).isEmpty();
  }
}
