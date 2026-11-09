package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.triplea.mobile.forum.NodeBbClient.GameThread;
import org.triplea.mobile.forum.NodeBbClient.Player;
import org.triplea.mobile.forum.NodeBbClient.SaveGame;
import org.triplea.mobile.forum.NodeBbClient.ThreadPage;

/** Ported from desktop's {@code NodeBbGameThreadsTest} (feat/modern-ui). */
class NodeBbClientReadTest {
  private static final URI FORUM = URI.create(FakeForum.URL);
  private static final String SESSION = "s%3Asession";
  private static final Player ANN = new Player("Ann Lee", "ann-lee");
  private static final GameThread PACIFIC = GameThread.of(Forum.TRIPLEA,103, "103/pacific", "Pacific");

  /** Topics the player started: a game of the Play by Forum category and a question elsewhere. */
  private static final String STARTED =
      """
      {"topics": [
        {"tid": 101, "slug": "101/global-1940", "title": "Global 1940: Bob vs Ann", "cid": 45,
         "lastposttime": 1700000000000, "postcount": 40,
         "teaser": {"content": "<h3>TripleA Turn Summary: Germans round 5</h3>",
                    "user": {"userslug": "bob"}}},
        {"tid": 102, "title": "Lobby question", "cid": 10, "teaser": null,
         "lastposttime": 1700000100000, "postcount": 3},
        {"tid": 105, "title": "Abandoned game", "cid": 45, "deleted": 1,
         "lastposttime": 1700000500000, "postcount": 2}
      ]}
      """;

  private static final String POSTED =
      """
      {"posts": [
        {"tid": 103, "timestamp": 1700000200000,
         "content": "<p><a href=\\"/assets/uploads/files/1-triplea_103_3rus.tsvg\\">Savegame</a></p>",
         "topic": {"tid": 103, "title": "Pacific &amp; friends", "postcount": 12},
         "category": {"cid": 20, "name": "Off Topic"}},
        {"tid": 101, "timestamp": 1700000300000, "content": "<p>gg</p>",
         "user": {"userslug": "ann-lee"},
         "topic": {"tid": 101, "title": "Global 1940: Bob vs Ann", "postcount": 41},
         "category": {"cid": 46, "name": "Play  By Forum"}},
        {"tid": 104, "timestamp": 1700000400000, "content": "<p>hello</p>",
         "topic": {"tid": 104, "title": "Chat"}, "category": null},
        {"tid": 105, "timestamp": 1700000500000, "content": "<p>Who joins?</p>",
         "topic": {"tid": 105, "title": "Abandoned game", "deleted": 1},
         "category": {"cid": 45, "name": "Play  By Forum"}}
      ]}
      """;

  private static final String WATCHED =
      """
      {"topics": [
        {"tid": 103, "slug": "103/pacific", "title": "Pacific &amp; friends", "cid": 20,
         "lastposttime": 1700000600000, "postcount": 14, "unread": true,
         "teaser": {"content": "<h3>TripleA Turn Summary: Japanese round 4</h3>",
                    "user": {"userslug": "bob"}}},
        {"tid": 106, "title": "Site news", "cid": 1, "lastposttime": 1700000700000},
        {"tid": 107, "slug": "107/others", "title": "Carl vs Dan", "cid": 45,
         "lastposttime": 1700000800000, "teaser": {"user": {"userslug": "carl"}}}
      ]}
      """;

  private static final String PAGE =
      """
      {"posts": [
        {"timestamp": 1700000000000,
         "user": {"username": "ann", "userslug": "ann", "displayname": "Ann Lee",
                  "icon:text": "A", "icon:bgColor": "#ffc107", "picture": "/uploads/ann.png"},
         "content": "<h3>TripleA Turn Summary: Russians round 3</h3><p>Moves&nbsp;done<br>Attack</p>\
      <p><a href=\\"/assets/uploads/files/1700-triplea_103_3rus.tsvg\\">Savegame</a></p>"},
        {"timestamp": 1, "deleted": 1, "user": {"username": "bob"}, "content": "<p>gone</p>"}
      ],
      "pagination": {"currentPage": 2, "pageCount": 3}, "isFollowing": true}
      """;

  private final FakeForum forum = new FakeForum();
  private final NodeBbClient client = new NodeBbClient(forum, Forum.TRIPLEA, SESSION);

  @Test
  void gameThreadsOfPlayByForumAndSaveGames() {
    final List<GameThread> threads =
        NodeBbClient.gameThreads(json(STARTED), json(WATCHED), List.of(json(POSTED)), Forum.TRIPLEA);

    assertThat(threads)
        .containsExactly(
            new GameThread(
                107,
                "107/others",
                "Carl vs Dan",
                Instant.ofEpochMilli(1700000800000L),
                0,
                "",
                "carl",
                false,
                true,
                Forum.TRIPLEA),
            new GameThread(
                103,
                "103/pacific",
                "Pacific & friends",
                Instant.ofEpochMilli(1700000600000L),
                14,
                "Japanese, round 4",
                "bob",
                true,
                false,
                Forum.TRIPLEA),
            new GameThread(
                101,
                "101/global-1940",
                "Global 1940: Bob vs Ann",
                Instant.ofEpochMilli(1700000300000L),
                41,
                "Germans, round 5",
                "ann-lee",
                false,
                false,
                Forum.TRIPLEA));
    assertThat(threads).extracting(game -> game.waitsFor(ANN)).containsExactly(false, true, false);
  }

  @Test
  void threadPageReadsPostsAndSaveGames() {
    final ThreadPage page = NodeBbClient.threadPage(json(PAGE), FORUM);

    assertThat(page.posts())
        .singleElement()
        .satisfies(
            post -> {
              assertThat(
                      List.of(
                          post.author(), post.authorSlug(), post.avatarText(), post.avatarColor()))
                  .containsExactly("Ann Lee", "ann", "A", "#ffc107");
              assertThat(post.avatarUrl())
                  .isEqualTo("https://forums.triplea-game.org/uploads/ann.png");
              assertThat(post.text())
                  .isEqualTo("TripleA Turn Summary: Russians round 3\nMoves done\nAttack\nSavegame");
              assertThat(post.saveGames())
                  .containsExactly(
                      new SaveGame(
                          URI.create(
                              "https://forums.triplea-game.org/assets/uploads/files/"
                                  + "1700-triplea_103_3rus.tsvg"),
                          "1700-triplea_103_3rus.tsvg",
                          "Russians, round 3"));
            });
    assertThat(List.of(page.page(), page.pageCount())).containsExactly(2, 3);
    assertThat(page.watched()).isTrue();
  }

  @Test
  void picturesAreHttpsWithoutEmoji() {
    final String post =
        "<p><img src=\"/assets/uploads/files/map.png\" alt=\"\">"
            + "<img class=\"not-responsive emoji\" src=\"/plugins/emoji/smile.png\">"
            + "<img src=\"http://plain.example/a.png\"><IMG alt=\"x\" src=\"https://i.example/b.jpg\"></p>";

    assertThat(NodeBbClient.picturesIn(post, FORUM))
        .containsExactly(
            URI.create("https://forums.triplea-game.org/assets/uploads/files/map.png"),
            URI.create("https://i.example/b.jpg"));
  }

  @Test
  void expiredSessionOnReadNeedsLogin() {
    forum.answer("api/topic/101/global?page=1&sort=oldest_to_newest", 403, "");

    assertThatThrownBy(() -> client.page(GameThread.of(Forum.TRIPLEA,101, "101/global", ""), 1))
        .isInstanceOf(NodeBbClient.LoginNeededException.class);
  }

  @Test
  void foreignLinksAreNoSaveGames() {
    final String post =
        "<a href=\"https://evil.example/x.tsvg\">a</a>"
            + "<a href=\"http://forums.triplea-game.org/assets/plain.tsvg\">b</a>"
            + "<a href=\"/outgoing?url=https://evil.example/y.tsvg\">c</a>"
            + "<a href=\"/assets/uploads/files/100%25_win.tsvg\">d</a>";

    assertThat(NodeBbClient.saveGamesIn(post, FORUM))
        .extracting(SaveGame::fileName)
        .containsExactly("100%_win.tsvg");
  }

  @Test
  void readingOnlyAsksWithTheSession() throws IOException {
    forum.answer("api/user/ann-lee/topics", STARTED);
    for (int page = 1; page <= 3; page++) {
      forum.answer("api/user/ann-lee/posts?page=" + page, POSTED);
    }

    final List<GameThread> threads = client.threadsOf(ANN);

    assertThat(threads).extracting(GameThread::topicId).containsExactly(101, 103);
    assertThat(forum.requests)
        .allSatisfy(
            request -> {
              assertThat(request.method()).isEqualTo("GET");
              assertThat(request.headers()).containsEntry("Cookie", "express.sid=" + SESSION + "; nbb_human=1");
            })
        .extracting(Http.Request::url)
        .contains(FakeForum.URL + "api/user/ann-lee/posts?page=3");
  }

  @Test
  void pageIsAskedOldestFirst() throws IOException {
    forum.answer("api/topic/103/pacific?page=2&sort=oldest_to_newest", PAGE);

    assertThat(client.page(PACIFIC, 2).pageCount()).isEqualTo(3);
  }

  @Test
  void newestPageIsTheLast() throws IOException {
    forum.answer("api/topic/103/pacific?page=1&sort=oldest_to_newest", PAGE);
    forum.answer("api/topic/103/pacific?page=3&sort=oldest_to_newest", PAGE);

    client.newestPage(PACIFIC);

    assertThat(forum.requests)
        .extracting(Http.Request::url)
        .last()
        .isEqualTo(FakeForum.URL + "api/topic/103/pacific?page=3&sort=oldest_to_newest");
  }

  @Test
  void browseShowsCategoryGames() {
    final String category =
        """
        {"topics": [
          {"tid": 1, "title": "Rules of this category", "pinned": 1, "lastposttime": 9},
          {"tid": 4133, "slug": "4133/testing", "title": "Testing Global '40", "postcount": 30,
           "lastposttime": 1743505988047,
           "teaser": {"content": "<h3>TripleA Turn Summary: Russians round 1</h3>"}},
          {"tid": 4441, "slug": "4441/qualifier", "title": "Qualifier Game 2",
           "lastposttime": 1793000000000, "teaser": null}
        ]}
        """;

    assertThat(NodeBbClient.categoryGames(json(category), Forum.TRIPLEA))
        .extracting(GameThread::topicId, GameThread::lastTurn)
        .containsExactly(tuple(4441, ""), tuple(4133, "Russians, round 1"));
  }

  @Test
  void sessionSignsInItsPlayer() throws IOException {
    forum.answer("api/config", "{\"uid\": 7176, \"loggedIn\": true}");
    forum.answer("api/user/uid/7176", "{\"username\": \"O&#x27;Neil\", \"userslug\": \"oneil\"}");

    assertThat(client.signedInPlayer()).contains(new Player("O'Neil", "oneil"));
  }

  @Test
  void expiredSessionSignsInNoOne() throws IOException {
    forum.answer("api/config", "{\"uid\": 0, \"loggedIn\": false}");

    assertThat(client.signedInPlayer()).isEmpty();
  }

  @Test
  void redirectIsAnError() {
    forum.answer("api/config", 302, "");

    assertThatThrownBy(client::signedInPlayer).isInstanceOf(IOException.class);
  }

  @Test
  void downloadsSaveGame(@TempDir final Path folder) throws IOException {
    final Http bytes =
        request -> new Http.Response(200, new byte[] {1, 2, 3}, Map.of());
    final SaveGame saveGame =
        new SaveGame(
            URI.create(FakeForum.URL + "assets/uploads/files/1-triplea_103_3rus.tsvg"),
            "1-triplea_103_3rus.tsvg",
            "");

    final Path file = new NodeBbClient(bytes, Forum.TRIPLEA, SESSION).download(saveGame, folder);

    assertThat(file).hasFileName("1-triplea_103_3rus.tsvg");
    assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
    assertThat(folder.toFile().list()).containsExactly("1-triplea_103_3rus.tsvg");
  }

  @Test
  void downloadNeverReplacesAFileOfTheSameName(@TempDir final Path folder) throws IOException {
    // eg the player's own turn save, put off with "send later", named as desktop names saves
    Files.write(folder.resolve("triplea_103_3Rus.tsvg"), new byte[] {9});
    final Http bytes = request -> new Http.Response(200, new byte[] {1, 2, 3}, Map.of());
    final SaveGame saveGame =
        new SaveGame(
            URI.create(FakeForum.URL + "assets/uploads/files/triplea_103_3Rus.tsvg"),
            "triplea_103_3Rus.tsvg",
            "");

    final Path file = new NodeBbClient(bytes, Forum.TRIPLEA, SESSION).download(saveGame, folder);

    assertThat(file.getFileName().toString()).endsWith("-triplea_103_3Rus.tsvg");
    assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
    assertThat(Files.readAllBytes(folder.resolve("triplea_103_3Rus.tsvg"))).containsExactly(9);
  }

  @Test
  void foreignSaveGameIsNotDownloaded(@TempDir final Path folder) {
    final SaveGame foreign = new SaveGame(URI.create("https://evil.example/x.tsvg"), "x.tsvg", "");

    assertThatThrownBy(() -> client.download(foreign, folder)).isInstanceOf(IOException.class);
    assertThat(forum.requests).isEmpty();
    assertThat(folder.toFile().list()).isEmpty();
  }

  @Test
  void fileNamesAreSafe() {
    assertThat(
            List.of(
                NodeBbClient.safeFileName("../../evil name"), NodeBbClient.safeFileName("TURN.TSVG")))
        .containsExactly(".._.._evil_name.tsvg", "TURN.TSVG");
  }

  @Test
  void plainTextKeepsLines() {
    assertThat(NodeBbClient.plainText("<p>Hi&amp;bye<br/>next</p><ul><li>one</li></ul>"))
        .isEqualTo("Hi&bye\nnext\none");
    assertThat(NodeBbClient.turnOf("<p>no turn here</p>")).isEmpty();
  }

  private static JsonObject json(final String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }
}
