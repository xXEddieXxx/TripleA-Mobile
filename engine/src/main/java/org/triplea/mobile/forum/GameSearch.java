package org.triplea.mobile.forum;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.triplea.mobile.forum.NodeBbClient.GameThread;

/** The games list's search box and its "inactive" filter. */
@UtilityClass
public class GameSearch {
  /** A game without a post for this long counts as inactive and is hidden by default. */
  public static final Duration INACTIVE_AFTER = Duration.ofDays(60);

  /** The games whose title, last poster or last turn contain the query, in their order. */
  public static List<GameThread> matching(
      final List<GameThread> games,
      final String query,
      final boolean includeInactive,
      final Instant now) {
    final String needle = query.strip().toLowerCase(Locale.ROOT);
    final Instant activeSince = now.minus(INACTIVE_AFTER);
    return games.stream()
        .filter(game -> includeInactive || game.lastPost().isAfter(activeSince))
        .filter(
            game ->
                needle.isEmpty()
                    || (game.title() + "\n" + game.lastPosterSlug() + "\n" + game.lastTurn())
                        .toLowerCase(Locale.ROOT)
                        .contains(needle))
        .toList();
  }
}
