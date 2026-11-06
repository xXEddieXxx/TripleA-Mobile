package org.triplea.mobile.forum;

import lombok.experimental.UtilityClass;

/**
 * A turn post exactly as desktop TripleA writes it ({@code PbemMessagePoster}, {@code
 * NodeBbForumPoster}), so desktop players read the turn back from a mobile post.
 */
@UtilityClass
public class PbfPost {
  /** Eg "TripleA Turn Summary: Germans round 3"; desktop finds turns by this title. */
  public static String title(final String stepTitle, final String player, final int round) {
    return "TripleA " + stepTitle + ": " + player + " round " + round;
  }

  /** Eg "triplea_1234_3Ger.tsvg"; desktop keeps at most 3, and fewer than all, of the letters. */
  public static String saveName(final int topicId, final int round, final String player) {
    return "triplea_"
        + topicId
        + "_"
        + round
        + player.substring(0, Math.min(3, player.length() - 1))
        + ".tsvg";
  }

  /** The post: title heading, game line, the player's message, the summary and the save link. */
  public static String content(
      final String stepTitle,
      final String player,
      final int round,
      final String gameName,
      final String message,
      final String summary,
      final String saveUrl) {
    return "### "
        + title(stepTitle, player, round)
        + "\n"
        + "TripleA "
        + stepTitle
        + " for game: "
        + gameName
        + "\n\n"
        + (message.isBlank() ? "" : message.strip() + "\n\n")
        + summary
        + "\n\n[Savegame]("
        + saveUrl
        + ")";
  }
}
