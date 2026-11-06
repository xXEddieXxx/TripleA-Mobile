package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.mobile.forum.NodeBbClient.GameThread;

class TurnPosterTest {
  private static final GameThread GAME = GameThread.of(Forum.TRIPLEA,101, "101/global", "Global");

  @Test
  void retryAfterFailedReplyDoesNotUploadAgain(@TempDir final Path folder) throws IOException {
    final FakeForum forum =
        new FakeForum()
            .answer("api/config", "{\"uid\": 7, \"csrf_token\": \"t\"}")
            .answer(
                "api/post/upload",
                "{\"response\": {\"images\": [{\"url\": \"/assets/uploads/files/s.tsvg\"}]}}")
            .answer("api/v3/topics/101", 500, "");
    final Path save = Files.writeString(folder.resolve("s.tsvg"), "save");
    final TurnPoster poster =
        new TurnPoster(new NodeBbClient(forum, Forum.TRIPLEA, "s"), GAME, save);

    assertThatThrownBy(() -> poster.send(url -> "turn " + url)).isInstanceOf(IOException.class);
    forum.answer("api/v3/topics/101", "{\"response\": {\"pid\": 1}}");
    final boolean heldBack = poster.send(url -> "turn " + url);

    assertThat(heldBack).isFalse();
    assertThat(forum.requests)
        .filteredOn(request -> request.url().endsWith("api/post/upload"))
        .hasSize(1);
    assertThat(FakeForum.text(forum.requests.get(forum.requests.size() - 1)))
        .contains("turn /assets/uploads/files/s.tsvg");
  }
}
