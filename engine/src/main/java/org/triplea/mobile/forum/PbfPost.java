package org.triplea.mobile.forum;

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * A turn post as desktop TripleA writes it ({@code PbemMessagePoster}, {@code NodeBbForumPoster}),
 * so desktop players read the turn back from a mobile post; only the player's message moved to the
 * top.
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

  /** A game post's text split for reading: its title, the player's message, the game lines. */
  public record Parts(String title, String message, List<String> game) {}

  /**
   * Splits a game post's plain text: the title line, the player's message and the game lines (the
   * "for game" line and the summary from "Game History" on). Reads both layouts, the message above
   * the game line (mobile) and below it (desktop). Without a "Game History" line all is game lines.
   */
  public static Parts parts(final String text) {
    final List<String> lines =
        text.lines().filter(l -> !l.isBlank() && !"Savegame".equals(l.strip())).toList();
    if (lines.isEmpty()) {
      return new Parts("", "", List.of());
    }
    final List<String> rest = lines.subList(1, lines.size());
    int history = -1;
    for (int i = 0; i < rest.size() && history < 0; i++) {
      if ("Game History".equals(rest.get(i).strip())) {
        history = i;
      }
    }
    if (history < 0) {
      return new Parts(lines.get(0), "", rest);
    }
    final List<String> game = new ArrayList<>();
    final List<String> message = new ArrayList<>();
    for (final String line : rest.subList(0, history)) {
      if (line.startsWith("TripleA ") && line.contains(" for game: ")) {
        game.add(line);
      } else if (!"---".equals(line.strip())) {
        message.add(line.strip());
      }
    }
    game.addAll(rest.subList(history, rest.size()));
    return new Parts(lines.get(0), String.join("\n", message), game);
  }

  /**
   * The post: title heading, the player's message on top (set apart by a rule from the game
   * information below it), the game line, the summary and the save link. The title stays the first
   * line: desktop reads the turn from the post's teaser, its beginning. Desktop puts the message
   * after the game line; mobile: on top, so it is read first.
   */
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
        + "\n\n"
        + (message.isBlank() ? "" : message.strip() + "\n\n---\n\n")
        + "TripleA "
        + stepTitle
        + " for game: "
        + gameName
        + "\n\n"
        + summary
        + "\n\n[Savegame]("
        + saveUrl
        + ")";
  }
}
