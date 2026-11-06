package org.triplea.mobile.forum;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The NodeBB forums desktop TripleA posts to ({@code NodeBbForumPoster}), each with its address,
 * the category new games start in and the one that holds all its game categories.
 */
@Getter
@AllArgsConstructor
public enum Forum {
  TRIPLEA("forums.triplea-game.org", "TripleA forum", "https://forums.triplea-game.org/", 45, 45),
  // "Play Online Axis & Allies" (65) holds "Play Boardgames" (16), League, Tournaments, Team Games
  AXIS_AND_ALLIES(
      "www.axisandallies.org/forums/", "AxisAndAllies.org", "https://www.axisandallies.org/forums/", 16, 65);

  /** Desktop's name, stored in a save's {@code FORUM_POSTER_NAME}. */
  private final String posterName;

  private final String label;
  private final String url;

  /** Where Browse looks and new games start. */
  private final int gameCategory;

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
