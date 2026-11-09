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
  void browseAndNewGamesUseTheChosenGameCategoryBelowForums() throws IOException {
    final List<Forum.Category> categories = Forum.AXIS_AND_ALLIES.getGameCategories();
    final List<GameThread> games = client.browse(categories.get(0));
    client.startThread(categories.get(1), "G40", "Who joins?");

    assertThat(asked.get(0).url())
        .isEqualTo("https://www.axisandallies.org/forums/api/category/16?sort=newest_to_oldest&page=1");
    // a new game in the League (29)
    assertThat(new String(asked.get(asked.size() - 1).body(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("\"cid\":29");
    assertThat(games).singleElement().extracting(GameThread::forum).isEqualTo(Forum.AXIS_AND_ALLIES);
    // its Cloudflare lets only requests with the proof-of-life cookie through
    assertThat(asked).allSatisfy(r -> assertThat(r.headers().get("Cookie")).isEqualTo("express.sid=s; nbb_human=1"));
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
  void cloudflareChallengeIsNoLoginProblem() {
    // what www.axisandallies.org answers the app since its bot protection checks the API too
    final Http challenged =
        request -> new Http.Response(403, "<html>Just a moment...</html>", Map.of("Cf-Mitigated", List.of("challenge")));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new NodeBbClient(challenged, Forum.AXIS_AND_ALLIES, "s").signedInPlayer())
        .isInstanceOf(NodeBbClient.BlockedException.class)
        .hasMessageContaining("bot protection");
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
