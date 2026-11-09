package org.triplea.mobile.forum;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The NodeBB forums desktop TripleA posts to ({@code NodeBbForumPoster}), each with its address,
 * the categories games are played in and the one that holds all its game categories.
 */
@Getter
@AllArgsConstructor
public enum Forum {
  TRIPLEA(
      "forums.triplea-game.org",
      "TripleA forum",
      "https://forums.triplea-game.org/",
      "recently_replied",
      List.of(new Category(45, "Play by Forum")),
      45),
  // the game categories of "Play Online Axis & Allies" (65); its others hold no games
  AXIS_AND_ALLIES(
      "www.axisandallies.org/forums/",
      "AxisAndAllies.org",
      "https://www.axisandallies.org/forums/",
      // an older NodeBB: it reads "recently_replied" as unknown and lists the oldest topics first
      "newest_to_oldest",
      List.of(
          new Category(16, "Play Boardgames"),
          new Category(29, "League"),
          new Category(25, "Tournaments"),
          new Category(33, "Team Games")),
      65);

  /** A forum category games are played in. */
  public record Category(int id, String label) {}

  /** Desktop's name, stored in a save's {@code FORUM_POSTER_NAME}. */
  private final String posterName;

  private final String label;
  private final String url;

  /** The forum's name for "last answered first" in a category, as its api/config's categoryTopicSort. */
  private final String recentlyRepliedSort;

  /** Where Browse looks and new games start, the usual one first. */
  private final List<Category> gameCategories;

  /** A game thread lies in this category or one below it. */
  private final int playByForumCategory;

  public URI uri() {
    return URI.create(url);
  }

  /** The forum a save names, eg one from desktop; empty for a name of no known forum. */
  public static Optional<Forum> ofPosterName(final String posterName) {
    return Arrays.stream(values()).filter(forum -> forum.posterName.equals(posterName)).findFirst();
  }

  /** The forum that keeps the file at this address, eg an uploaded picture. */
  public static Optional<Forum> keeping(final URI url) {
    return Arrays.stream(values()).filter(forum -> NodeBbClient.isOnForum(url, forum.uri())).findFirst();
  }
}
