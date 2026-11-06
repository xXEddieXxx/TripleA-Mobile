package org.triplea.mobile.forum;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.AccessLevel;
import lombok.With;
import org.apache.commons.text.StringEscapeUtils;

/**
 * A NodeBB {@link Forum} as the signed-in player sees it: their game threads, a thread's posts and
 * save games. Port of desktop's {@code NodeBbGameThreads} (feat/modern-ui) on the {@link Http}
 * seam; every request carries the browser session's {@code express.sid} cookie and no redirect is
 * followed, so the session never leaves the forum.
 */
public final class NodeBbClient {
  public static final String SESSION_COOKIE = "express.sid";

  /** Pages of the player's own posts read for games they did not start. */
  private static final int POST_PAGES = 3;

  private static final char NO_BREAK_SPACE = (char) 0xA0;
  private static final Pattern SAVE_GAME_LINK =
      Pattern.compile("href=\"([^\"]+?\\.tsvg)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern PICTURE = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern PICTURE_SOURCE =
      Pattern.compile("\\ssrc=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern TURN =
      Pattern.compile(
          "TripleA (?:Turn Summary|Move Summary|Manual Gamesave Post): (.+?) round (\\d+)");
  private static final Pattern PLAY_BY_FORUM_NAME =
      Pattern.compile("play\\W*by\\W*forum|\\bpbf\\b", Pattern.CASE_INSENSITIVE);

  private final Http http;
  private final Forum site;
  private final URI forum;
  private final String session;

  /** The player as shown, eg "O'Neil", and as in the forum's addresses, eg "oneil". */
  public record Player(String username, String userslug) {}

  /**
   * A game thread: its topic, address (eg "101/global-1940", empty when unknown), last post, the
   * last turn posted (eg "Germans, round 5", or empty), who wrote the last post, whether it has
   * unread posts, whether the player only watches it and the forum it is on.
   */
  public record GameThread(
      int topicId,
      String slug,
      String title,
      Instant lastPost,
      int posts,
      String lastTurn,
      String lastPosterSlug,
      boolean unread,
      @With(AccessLevel.PRIVATE) boolean onlyWatched,
      Forum forum) {

    /** A thread known by its address only, eg to post to. */
    public static GameThread of(
        final Forum forum, final int topicId, final String slug, final String title) {
      return new GameThread(topicId, slug, title, Instant.EPOCH, 0, "", "", false, false, forum);
    }

    /**
     * Whether it is likely the player's move: the last post is someone else's. A guess: the forum
     * does not know the order of play. Never in a game the player only watches.
     */
    public boolean waitsFor(final Player player) {
      return !onlyWatched && !lastPosterSlug.isEmpty() && !lastPosterSlug.equals(player.userslug());
    }
  }

  /** A save game linked in a post, with the turn the post names, or an empty text. */
  public record SaveGame(URI url, String fileName, String turn) {}

  /**
   * A post: its author as shown and as slug, the forum's letter avatar (text and colour) and
   * picture (empty when none), time, plain text, HTML, save games, its id and its pictures.
   */
  public record ThreadPost(
      String author,
      String authorSlug,
      String avatarText,
      String avatarColor,
      String avatarUrl,
      Instant time,
      String text,
      String html,
      List<SaveGame> saveGames,
      int pid,
      List<URI> pictures) {}

  /**
   * The forum's bot protection (Cloudflare) answered instead of the forum: it lets web browsers in
   * only, so signing in again does not help.
   */
  public static final class BlockedException extends IOException {
    private static final long serialVersionUID = 1L;

    BlockedException(final String message) {
      super(message);
    }
  }

  /** The forum refused the session: it ran out or was signed out elsewhere. */
  public static final class LoginNeededException extends IOException {
    private static final long serialVersionUID = 1L;

    public LoginNeededException(final String message) {
      super(message);
    }
  }

  /** A page of a thread, oldest post first; pages count from 1. */
  public record ThreadPage(List<ThreadPost> posts, int page, int pageCount, boolean watched) {}

  public NodeBbClient(final Http http, final Forum site, final String session) {
    this.http = http;
    this.site = site;
    this.forum = site.uri();
    this.session = session;
  }

  public Forum site() {
    return site;
  }

  /** The player the session signs in; empty when it ran out (the forum then says uid 0). */
  public Optional<Player> signedInPlayer() throws IOException {
    final int userId = intOf(read("api/config"), "uid");
    if (userId <= 0) {
      return Optional.empty();
    }
    final JsonObject user = read("api/user/uid/" + userId);
    // the forum sends names HTML-escaped, eg "O&#x27;Neil"
    final String username = StringEscapeUtils.unescapeHtml4(stringOf(user, "username"));
    final String userslug = stringOf(user, "userslug");
    return username.isEmpty() || userslug.isEmpty()
        ? Optional.empty()
        : Optional.of(new Player(username, userslug));
  }

  /**
   * The Play by Forum category's games of its first pages, the last answered first, without pinned
   * notes; enough for the search to find games that are a few weeks old.
   */
  public List<GameThread> browse() throws IOException {
    final Map<Integer, GameThread> games = new LinkedHashMap<>();
    for (int page = 1; page <= BROWSE_PAGES; page++) {
      // the order named: the forum orders by the signed-in player's own setting otherwise
      final JsonObject category =
          read(
              "api/category/"
                  + site.getGameCategory()
                  + "?sort=recently_replied&page="
                  + page);
      // a topic that moved up between two pages is listed twice: keep the first
      categoryGames(category, site).forEach(game -> games.putIfAbsent(game.topicId(), game));
      final int pageCount =
          objectOf(category, "pagination").map(it -> intOf(it, "pageCount")).orElse(1);
      if (page >= pageCount) {
        break;
      }
    }
    return games.values().stream()
        .sorted(Comparator.comparing(GameThread::lastPost).reversed())
        .toList();
  }

  private static final int BROWSE_PAGES = 3;

  static List<GameThread> categoryGames(final JsonObject category, final Forum site) {
    return objectsIn(category, "topics").stream()
        .filter(topic -> !isSet(topic, "deleted") && !isSet(topic, "pinned"))
        .map(topic -> topicThread(topic, site))
        .sorted(Comparator.comparing(GameThread::lastPost).reversed())
        .toList();
  }

  private static GameThread topicThread(final JsonObject topic, final Forum site) {
    final Optional<JsonObject> teaser = objectOf(topic, "teaser");
    return toThread(
        topic,
        0,
        turnOf(teaser.map(it -> stringOf(it, "content")).orElse("")),
        teaser
            .flatMap(it -> objectOf(it, "user"))
            .map(user -> stringOf(user, "userslug"))
            .orElse(""),
        site);
  }

  /** The game threads the player started, posted in or watches, the last answered first. */
  public List<GameThread> threadsOf(final Player player) throws IOException {
    final String userPath = "api/user/" + pathSegment(player.userslug());
    final JsonObject started = read(userPath + "/topics");
    final List<JsonObject> posted = new ArrayList<>();
    for (int page = 1; page <= POST_PAGES; page++) {
      final JsonObject posts = read(userPath + "/posts?page=" + page);
      posted.add(posts);
      if (objectsIn(posts, "posts").isEmpty()) {
        break;
      }
    }
    return gameThreads(started, watched(userPath), posted, site);
  }

  private JsonObject watched(final String userPath) {
    try {
      return read(userPath + "/watched");
    } catch (final IOException e) {
      // the forum may not tell, eg to a guest
      return new JsonObject();
    }
  }

  /** The newest page of the thread, where the last turn is. */
  public ThreadPage newestPage(final GameThread thread) throws IOException {
    final ThreadPage first = page(thread, 1);
    return first.pageCount() > 1 ? page(thread, first.pageCount()) : first;
  }

  /** A page of the thread, from 1 to its page count. */
  public ThreadPage page(final GameThread thread, final int page) throws IOException {
    final String path = thread.slug().isEmpty() ? String.valueOf(thread.topicId()) : thread.slug();
    return threadPage(read("api/topic/" + path + "?page=" + page + "&sort=oldest_to_newest"), forum);
  }

  /**
   * Downloads the save game into the folder, named as in the forum; only a file the forum keeps,
   * so the session never goes elsewhere. A failed download leaves no broken file.
   */
  public Path download(final SaveGame saveGame, final Path folder) throws IOException {
    if (!isOnForum(saveGame.url().normalize(), forum)) {
      throw new IOException("Not a file of the forum: " + saveGame.url());
    }
    final Http.Response response = send(Http.Request.get(saveGame.url().toString(), cookie()));
    Files.createDirectories(folder);
    final Path file = folder.resolve(safeFileName(saveGame.fileName()));
    final Path part = Files.createTempFile(folder, "download-", ".part");
    try {
      Files.write(part, response.bytes());
      Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(part);
    }
    return file;
  }

  /**
   * Uploads a file as the forum's editor does; the forum takes it as a picture by its type only.
   *
   * @return the forum's address of the file, eg "/assets/uploads/files/1-map.png", to link
   */
  public String upload(final Path file, final String mime) throws IOException {
    final Map<String, String> headers = writeHeaders();
    final Http.Response response =
        send(
            Http.Request.multipart(
                forum.resolve("api/post/upload").toString(),
                "files[]",
                String.valueOf(file.getFileName()),
                Files.readAllBytes(file),
                mime,
                headers));
    return uploadedUrl(response.body());
  }

  /** The address in the upload's answer, of newer forums ({@code response.images}) and older. */
  private static String uploadedUrl(final String body) throws IOException {
    try {
      final JsonElement answer = JsonParser.parseString(body);
      final JsonObject first =
          answer.isJsonArray()
              ? answer.getAsJsonArray().get(0).getAsJsonObject()
              : answer
                  .getAsJsonObject()
                  .getAsJsonObject("response")
                  .getAsJsonArray("images")
                  .get(0)
                  .getAsJsonObject();
      final String url = stringOf(first, "url");
      if (url.isEmpty()) {
        throw new IOException("The forum's upload answer has no address: " + body);
      }
      return url;
    } catch (final RuntimeException e) {
      throw new IOException("The forum's upload answer is unreadable: " + body, e);
    }
  }

  /**
   * Answers in the thread as the signed-in player.
   *
   * @return whether the forum holds the post back for a moderator, as it does for new members
   */
  public boolean reply(final GameThread thread, final String content) throws IOException {
    final JsonObject body = new JsonObject();
    body.addProperty("content", content);
    final Http.Response response =
        send(
            Http.Request.json(
                forum.resolve("api/v3/topics/" + thread.topicId()).toString(),
                body.toString(),
                writeHeaders()));
    return objectOf(json(response, "the post"), "response")
        .filter(answer -> isSet(answer, "queued"))
        .isPresent();
  }

  /** The Markdown of a post as its author wrote it, to edit it. */
  public String rawPost(final int pid) throws IOException {
    return objectOf(read("api/v3/posts/" + pid + "/raw"), "response")
        .map(answer -> stringOf(answer, "content"))
        .orElseThrow(() -> new IOException("The forum sent no text of post " + pid));
  }

  /** Replaces the text of the player's own post. */
  public void editPost(final int pid, final String content) throws IOException {
    final JsonObject body = new JsonObject();
    body.addProperty("content", content);
    send(
        new Http.Request(
            "PUT",
            forum.resolve("api/v3/posts/" + pid).toString(),
            writeHeaders(),
            body.toString().getBytes(StandardCharsets.UTF_8),
            "application/json"));
  }

  /** Deletes the player's own post as the forum's own button does: a moderator can restore it. */
  public void deletePost(final int pid) throws IOException {
    send(
        new Http.Request(
            "DELETE", forum.resolve("api/v3/posts/" + pid + "/state").toString(), writeHeaders(), null, null));
  }

  /** A picture of a post: the forum's own with the session, any other https one without. */
  public byte[] picture(final URI url) throws IOException {
    final Map<String, String> headers = isOnForum(url.normalize(), forum) ? cookie() : Map.of();
    return send(Http.Request.get(url.toString(), headers)).bytes();
  }

  /**
   * Starts a game thread in the Play by Forum category.
   *
   * @return the new thread; empty when a moderator must approve it first
   */
  public Optional<GameThread> startThread(final String title, final String content)
      throws IOException {
    final JsonObject body = new JsonObject();
    body.addProperty("cid", site.getGameCategory());
    body.addProperty("title", title);
    body.addProperty("content", content);
    final Http.Response response =
        send(
            Http.Request.json(
                forum.resolve("api/v3/topics").toString(), body.toString(), writeHeaders()));
    return objectOf(json(response, "the new thread"), "response")
        .filter(topic -> !isSet(topic, "queued") && intOf(topic, "tid") > 0)
        .map(topic -> topicThread(topic, site));
  }

  /** The session and the CSRF token the forum asks for with every write. */
  private Map<String, String> writeHeaders() throws IOException {
    final String token = stringOf(read("api/config"), "csrf_token");
    return Map.of(
        "Cookie", SESSION_COOKIE + "=" + session,
        "x-csrf-token", token);
  }

  static String safeFileName(final String fileName) {
    final String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    return safe.toLowerCase(Locale.ROOT).endsWith(".tsvg") ? safe : safe + ".tsvg";
  }

  static List<GameThread> gameThreads(
      final JsonObject started,
      final JsonObject watched,
      final List<JsonObject> posted,
      final Forum site) {
    final List<GameThread> startedThreads =
        objectsIn(started, "topics").stream()
            // the forum still lists a deleted topic to the player who started it
            .filter(topic -> !isSet(topic, "deleted") && isPlayByForum(topic, site))
            .map(topic -> topicThread(topic, site))
            .toList();
    final List<GameThread> postedThreads =
        posted.stream()
            .flatMap(posts -> objectsIn(posts, "posts").stream())
            .flatMap(post -> postedThread(post, site).stream())
            .toList();
    final Set<Integer> playersTopics =
        Stream.concat(startedThreads.stream(), postedThreads.stream())
            .map(GameThread::topicId)
            .collect(Collectors.toSet());
    // a followed game of others counts only in the Play by Forum category
    final Stream<GameThread> watchedThreads =
        objectsIn(watched, "topics").stream()
            .filter(topic -> !isSet(topic, "deleted"))
            .flatMap(
                topic -> {
                  final boolean players = playersTopics.contains(intOf(topic, "tid"));
                  return players || isPlayByForum(topic, site)
                      ? Stream.of(topicThread(topic, site).withOnlyWatched(!players))
                      : Stream.empty();
                });
    final Map<Integer, GameThread> threads =
        Stream.of(startedThreads.stream(), watchedThreads, postedThreads.stream())
            .flatMap(Function.identity())
            .collect(
                Collectors.toMap(
                    GameThread::topicId,
                    Function.identity(),
                    NodeBbClient::newerOf,
                    LinkedHashMap::new));
    return threads.values().stream()
        .sorted(Comparator.comparing(GameThread::lastPost).reversed())
        .toList();
  }

  /** Whether the topic or post lies in the Play by Forum category, one below it, or one named so. */
  private static boolean isPlayByForum(final JsonObject topicOrPost, final Forum site) {
    final int category = site.getPlayByForumCategory();
    return intOf(topicOrPost, "cid") == category
        || intOf(topicOrPost, "cid") == site.getGameCategory()
        || objectOf(topicOrPost, "category")
            .filter(
                found ->
                    intOf(found, "cid") == category
                        || intOf(found, "parentCid") == category
                        || PLAY_BY_FORUM_NAME.matcher(stringOf(found, "name")).find())
            .isPresent();
  }

  private static GameThread toThread(
      final JsonObject topic,
      final long lastPost,
      final String lastTurn,
      final String lastPoster,
      final Forum site) {
    return new GameThread(
        intOf(topic, "tid"),
        stringOf(topic, "slug"),
        StringEscapeUtils.unescapeHtml4(stringOf(topic, "title")),
        Instant.ofEpochMilli(Math.max(lastPost, longOf(topic, "lastposttime"))),
        intOf(topic, "postcount"),
        lastTurn,
        lastPoster,
        isSet(topic, "unread"),
        false,
        site);
  }

  /** The thread of a post that is a game's: in the Play by Forum category, or a turn or save. */
  private static Optional<GameThread> postedThread(final JsonObject post, final Forum site) {
    final String content = stringOf(post, "content");
    final String turn = turnOf(content);
    final boolean gamePost = !turn.isEmpty() || !saveGamesIn(content, site.uri()).isEmpty();
    return objectOf(post, "topic")
        .filter(topic -> !isSet(topic, "deleted") && (gamePost || isPlayByForum(post, site)))
        .map(
            topic ->
                toThread(
                    topic,
                    longOf(post, "timestamp"),
                    turn,
                    objectOf(post, "user").map(user -> stringOf(user, "userslug")).orElse(""),
                    site));
  }

  /** A thread found twice, eg started by the player and posted in, with the newer news of both. */
  private static GameThread newerOf(final GameThread known, final GameThread other) {
    final GameThread newer = other.lastPost().isAfter(known.lastPost()) ? other : known;
    final GameThread older = newer == other ? known : other;
    return new GameThread(
        known.topicId(),
        known.slug().isEmpty() ? other.slug() : known.slug(),
        known.title().isEmpty() ? other.title() : known.title(),
        newer.lastPost(),
        Math.max(known.posts(), other.posts()),
        newer.lastTurn().isEmpty() ? older.lastTurn() : newer.lastTurn(),
        newer.lastPosterSlug().isEmpty() ? older.lastPosterSlug() : newer.lastPosterSlug(),
        known.unread() || other.unread(),
        // found also as the player's own: the player's
        known.onlyWatched() && other.onlyWatched(),
        known.forum());
  }

  static ThreadPage threadPage(final JsonObject topic, final URI forum) {
    final List<ThreadPost> posts =
        objectsIn(topic, "posts").stream()
            .filter(post -> !isSet(post, "deleted"))
            .map(post -> toPost(post, forum))
            .toList();
    final boolean watched = isSet(topic, "isFollowing");
    return objectOf(topic, "pagination")
        .map(
            pagination ->
                new ThreadPage(
                    posts,
                    Math.max(1, intOf(pagination, "currentPage")),
                    Math.max(1, intOf(pagination, "pageCount")),
                    watched))
        .orElseGet(() -> new ThreadPage(posts, 1, 1, watched));
  }

  private static ThreadPost toPost(final JsonObject post, final URI forum) {
    final String html = stringOf(post, "content");
    final Optional<JsonObject> user = objectOf(post, "user");
    final String author =
        user.map(
                it ->
                    stringOf(it, "displayname").isEmpty()
                        ? stringOf(it, "username")
                        : stringOf(it, "displayname"))
            .map(StringEscapeUtils::unescapeHtml4)
            .orElse("");
    return new ThreadPost(
        author,
        user.map(it -> stringOf(it, "userslug")).orElse(""),
        user.map(it -> stringOf(it, "icon:text")).map(StringEscapeUtils::unescapeHtml4).orElse(""),
        user.map(it -> stringOf(it, "icon:bgColor")).orElse(""),
        user.map(it -> stringOf(it, "picture"))
            .filter(picture -> !picture.isEmpty())
            .flatMap(picture -> resolved(picture, forum))
            .map(URI::toString)
            .orElse(""),
        Instant.ofEpochMilli(longOf(post, "timestamp")),
        plainText(html),
        html,
        saveGamesIn(html, forum),
        intOf(post, "pid"),
        picturesIn(html, forum));
  }

  /** The pictures of a post's HTML, by https only; the forum's emoji stay text-sized, so out. */
  public static List<URI> picturesIn(final String html, final URI forum) {
    final Matcher tags = PICTURE.matcher(html);
    final List<URI> pictures = new ArrayList<>();
    while (tags.find()) {
      final String tag = tags.group();
      final Matcher source = PICTURE_SOURCE.matcher(tag);
      if (!tag.contains("emoji") && source.find()) {
        resolved(StringEscapeUtils.unescapeHtml4(source.group(1)), forum)
            .filter(url -> "https".equalsIgnoreCase(url.getScheme()))
            .ifPresent(pictures::add);
      }
    }
    return pictures;
  }

  /** The save games linked in a post's HTML; only files the forum itself keeps. */
  static List<SaveGame> saveGamesIn(final String html, final URI forum) {
    final Matcher links = SAVE_GAME_LINK.matcher(html);
    final List<URI> urls = new ArrayList<>();
    while (links.find()) {
      resolved(StringEscapeUtils.unescapeHtml4(links.group(1)), forum)
          .filter(url -> isOnForum(url, forum))
          .filter(url -> url.getPath().toLowerCase(Locale.ROOT).endsWith(".tsvg"))
          .ifPresent(urls::add);
    }
    if (urls.isEmpty()) {
      return List.of();
    }
    final String turn = turnOf(html);
    // the path is decoded already, eg "100%.tsvg" for "100%25.tsvg"
    return urls.stream()
        .map(
            url ->
                new SaveGame(
                    url, url.getPath().substring(url.getPath().lastIndexOf('/') + 1), turn))
        .toList();
  }

  /** The link as an address on the forum, its ".." already gone. */
  static Optional<URI> resolved(final String link, final URI forum) {
    try {
      return Optional.of(forum.resolve(link).normalize()).filter(url -> url.getPath() != null);
    } catch (final IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Same scheme, host and port as the forum, and below its path. */
  static boolean isOnForum(final URI url, final URI forum) {
    // decoded: "%2e%2e" is ".." to the server, though normalizing the raw path keeps it
    final String path = Objects.toString(url.getPath(), "");
    return forum.getScheme().equalsIgnoreCase(Objects.toString(url.getScheme(), ""))
        && forum.getHost().equalsIgnoreCase(Objects.toString(url.getHost(), ""))
        && forum.getPort() == url.getPort()
        && path.startsWith(forum.getPath())
        && !path.contains("/../")
        && !path.endsWith("/..");
  }

  /** The turn a TripleA post names, eg "Germans, round 5"; empty for other posts. */
  static String turnOf(final String html) {
    final Matcher turn = TURN.matcher(plainText(html));
    return turn.find() ? turn.group(1).strip() + ", round " + turn.group(2) : "";
  }

  /** The text of a post's HTML: its paragraphs and lines kept, its tags and entities gone. */
  static String plainText(final String html) {
    final String lines =
        html.replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|h[1-6]|li|div|blockquote|pre|tr)>", "\n")
            .replaceAll("<[^>]*>", "");
    return StringEscapeUtils.unescapeHtml4(lines)
        .replace(NO_BREAK_SPACE, ' ')
        .replaceAll("[ \\t]+\\n", "\n")
        .replaceAll("\\n{3,}", "\n\n")
        .strip();
  }

  Map<String, String> cookie() {
    return Map.of("Cookie", SESSION_COOKIE + "=" + session);
  }

  JsonObject read(final String path) throws IOException {
    return json(send(Http.Request.get(forum.resolve(path).toString(), cookie())), path);
  }

  static JsonObject json(final Http.Response response, final String what) throws IOException {
    try {
      return JsonParser.parseString(response.body()).getAsJsonObject();
    } catch (final JsonParseException | IllegalStateException e) {
      throw new IOException("The forum's answer to " + what + " is no JSON object", e);
    }
  }

  /** Sends and checks the status; 202 is a write held for a moderator. */
  Http.Response send(final Http.Request request) throws IOException {
    final Http.Response response = http.send(request);
    final int status = response.status();
    if (status == 403 && isBotCheck(response)) {
      throw new BlockedException(
          site.getLabel()
              + " lets only web browsers in: its bot protection (Cloudflare) refuses the app's requests.");
    }
    if (status == 401 || status == 403) {
      throw new LoginNeededException("The forum asks for a valid login (HTTP " + status + ")" + reasonOf(response));
    }
    if (status != 200 && status != 202) {
      throw new IOException("The forum answered HTTP " + status + reasonOf(response));
    }
    return response;
  }

  /** Cloudflare marks the challenge page it sends instead of the forum's answer. */
  private static boolean isBotCheck(final Http.Response response) {
    return response.headers() != null
        && response.headers().keySet().stream().anyMatch("cf-mitigated"::equalsIgnoreCase);
  }

  /** The reason NodeBB gives for a refusal, eg a post too short, after a colon; else nothing. */
  private static String reasonOf(final Http.Response response) {
    try {
      return objectOf(JsonParser.parseString(response.body()).getAsJsonObject(), "status")
          .map(status -> stringOf(status, "message"))
          .filter(message -> !message.isEmpty())
          .map(message -> ": " + message)
          .orElse("");
    } catch (final JsonParseException | IllegalStateException e) {
      return "";
    }
  }

  /** A name as one part of an address: a space as "%20", not as the "+" of forms. */
  private static String pathSegment(final String name) {
    return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static Optional<JsonObject> objectOf(final JsonObject parent, final String key) {
    final JsonElement value = parent.get(key);
    return value != null && value.isJsonObject()
        ? Optional.of(value.getAsJsonObject())
        : Optional.empty();
  }

  private static List<JsonObject> objectsIn(final JsonObject parent, final String key) {
    final JsonElement array = parent.get(key);
    if (array == null || !array.isJsonArray()) {
      return List.of();
    }
    return StreamSupport.stream(array.getAsJsonArray().spliterator(), false)
        .filter(JsonElement::isJsonObject)
        .map(JsonElement::getAsJsonObject)
        .toList();
  }

  static String stringOf(final JsonObject object, final String key) {
    final JsonElement value = object.get(key);
    return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
  }

  /** A number the forum sends as a number or a string, eg "6"; 0 when missing or broken. */
  private static long longOf(final JsonObject object, final String key) {
    try {
      return stringOf(object, key).isEmpty() ? 0 : Long.parseLong(stringOf(object, key));
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  static int intOf(final JsonObject object, final String key) {
    return (int) longOf(object, key);
  }

  static boolean isSet(final JsonObject object, final String key) {
    final String value = stringOf(object, key);
    return "1".equals(value) || "true".equals(value);
  }
}
