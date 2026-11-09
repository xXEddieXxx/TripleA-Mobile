package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.mobile.forum.NodeBbClient.GameThread;

/** Ported from desktop's {@code NodeBbGameThreadsTest} (feat/modern-ui), the writes. */
class NodeBbClientWriteTest {
  private static final String SESSION = "s%3Asession";
  private static final GameThread GLOBAL_GAME =
      GameThread.of(Forum.TRIPLEA,101, "101/global-1940", "Global 1940: Bob vs Ann");

  private final FakeForum forum =
      new FakeForum().answer("api/config", "{\"uid\": 7, \"csrf_token\": \"token-1\"}");
  private final NodeBbClient client = new NodeBbClient(forum, Forum.TRIPLEA, SESSION);

  @Test
  void uploadSendsTheFileWithSessionAndToken(@TempDir final Path folder) throws IOException {
    forum.answer(
        "api/post/upload",
        """
        {"status": {"code": "ok"}, "response": {"images": [
          {"url": "/assets/uploads/files/1-triplea_101_6ger.tsvg"}]}}
        """);
    final Path save = Files.writeString(folder.resolve("triplea_101_6ger.tsvg"), "save");

    final String url = client.upload(save, "application/octet-stream");

    assertThat(url).isEqualTo("/assets/uploads/files/1-triplea_101_6ger.tsvg");
    final Http.Request upload = forum.requests.get(forum.requests.size() - 1);
    assertThat(upload.headers())
        .containsEntry("Cookie", "express.sid=" + SESSION + "; nbb_human=1")
        .containsEntry("x-csrf-token", "token-1");
    assertThat(upload.contentType()).startsWith("multipart/form-data; boundary=");
    assertThat(new String(upload.body(), StandardCharsets.ISO_8859_1))
        .contains(
            "name=\"files[]\"; filename=\"triplea_101_6ger.tsvg\"",
            "Content-Type: application/octet-stream",
            "\r\n\r\nsave\r\n");
  }

  @Test
  void uploadReadsTheOlderListAnswer(@TempDir final Path folder) throws IOException {
    forum.answer("api/post/upload", "[{\"url\": \"/assets/uploads/files/old.tsvg\"}]");
    final Path save = Files.writeString(folder.resolve("old.tsvg"), "save");

    assertThat(client.upload(save, "application/octet-stream"))
        .isEqualTo("/assets/uploads/files/old.tsvg");
  }

  @Test
  void pictureIsUploadedAsPicture(@TempDir final Path folder) throws IOException {
    forum.answer(
        "api/post/upload",
        "{\"response\": {\"images\": [{\"url\": \"/assets/uploads/files/1-map.png\"}]}}");
    final Path picture = Files.write(folder.resolve("map.png"), new byte[] {1, 2, 3});

    assertThat(client.upload(picture, "image/png")).isEqualTo("/assets/uploads/files/1-map.png");
    assertThat(FakeForum.text(forum.requests.get(forum.requests.size() - 1)))
        .contains("Content-Type: image/png");
  }

  @Test
  void replyPostsJsonWithSessionAndToken() throws IOException {
    forum.answer("api/v3/topics/101", "{\"response\": {\"pid\": 9}}");

    assertThat(client.reply(GLOBAL_GAME, "Your turn!")).isFalse();

    final Http.Request reply = forum.requests.get(forum.requests.size() - 1);
    assertThat(reply.method()).isEqualTo("POST");
    assertThat(reply.contentType()).isEqualTo("application/json");
    assertThat(reply.headers())
        .containsEntry("Cookie", "express.sid=" + SESSION + "; nbb_human=1")
        .containsEntry("x-csrf-token", "token-1");
    assertThat(FakeForum.text(reply)).isEqualTo("{\"content\":\"Your turn!\"}");
  }

  @Test
  void replyHeldBackForModerator() throws IOException {
    forum.answer("api/v3/topics/101", 202, "{\"response\": {\"queued\": true, \"id\": \"r1\"}}");

    assertThat(client.reply(GLOBAL_GAME, "Your turn!")).isTrue();
  }

  @Test
  void refusedReplyTellsTheForumsReason() {
    forum.answer(
        "api/v3/topics/101",
        400,
        "{\"status\": {\"code\": \"bad-request\", \"message\": \"Please enter a longer post.\"}}");

    assertThatThrownBy(() -> client.reply(GLOBAL_GAME, "ok"))
        .hasMessage("The forum answered HTTP 400: Please enter a longer post.");
  }

  @Test
  void startThreadInPlayByForumCategory() throws IOException {
    forum.answer(
        "api/v3/topics",
        "{\"response\": {\"tid\": 120, \"slug\": \"120/global-1940\","
            + " \"title\": \"Global 1940: Ann vs Bob\"}}");

    assertThat(client.startThread(Forum.TRIPLEA.getGameCategories().get(0), "Global 1940: Ann vs Bob", "Who joins?"))
        .map(GameThread::topicId)
        .contains(120);
    assertThat(FakeForum.text(forum.requests.get(forum.requests.size() - 1)))
        .isEqualTo("{\"cid\":45,\"title\":\"Global 1940: Ann vs Bob\",\"content\":\"Who joins?\"}");
  }

  @Test
  void ownPostIsReadEditedAndDeleted() throws IOException {
    forum
        .answer("api/v3/posts/9/raw", "{\"response\": {\"content\": \"**old**\"}}")
        .answer("api/v3/posts/9", "{\"response\": {}}")
        .answer("api/v3/posts/9/state", "{\"response\": {}}");

    assertThat(client.rawPost(9)).isEqualTo("**old**");
    client.editPost(9, "new");
    final Http.Request edit = forum.requests.get(forum.requests.size() - 1);
    client.deletePost(9);
    final Http.Request delete = forum.requests.get(forum.requests.size() - 1);

    assertThat(List.of(edit.method(), FakeForum.text(edit))).containsExactly("PUT", "{\"content\":\"new\"}");
    assertThat(edit.headers()).containsEntry("x-csrf-token", "token-1");
    assertThat(List.of(delete.method(), delete.url()))
        .containsExactly("DELETE", FakeForum.URL + "api/v3/posts/9/state");
    assertThat(delete.headers()).containsEntry("x-csrf-token", "token-1");
  }

  @Test
  void foreignPictureIsAskedWithoutSession() throws IOException {
    final List<Http.Request> asked = new ArrayList<>();
    final Http anywhere =
        request -> {
          asked.add(request);
          return new Http.Response(200, "png", Map.of());
        };
    final NodeBbClient reader = new NodeBbClient(anywhere, Forum.TRIPLEA, SESSION);

    reader.picture(URI.create("https://i.example/a.png"));
    reader.picture(URI.create(FakeForum.URL + "assets/uploads/files/b.png"));

    assertThat(asked.get(0).headers()).isEmpty();
    assertThat(asked.get(1).headers()).containsEntry("Cookie", "express.sid=" + SESSION + "; nbb_human=1");
  }

  @Test
  void heldBackThreadHasNoIdYet() throws IOException {
    forum.answer("api/v3/topics", 202, "{\"response\": {\"queued\": true}}");

    assertThat(client.startThread(Forum.TRIPLEA.getGameCategories().get(0), "Held", "x")).isEmpty();
  }
}
