package org.triplea.mobile.forum;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/** What a forum turn's review tells the player about the turn order. */
@UtilityClass
public class PbfTurn {

  /**
   * The nation whose turn follows {@code player}'s, looking from step {@code fromStep} on and
   * across the end of the round, when it is allied with {@code player}: often the same person
   * plays it too and keeps playing instead of posting. Empty for an enemy or when no other nation
   * plays. Call under the game data's read lock.
   */
  public static Optional<GamePlayer> nextAlly(
      final GameData data, final GamePlayer player, final int fromStep) {
    final GameSequence sequence = data.getSequence();
    final int size = sequence.size();
    for (int i = 1; i <= size; i++) {
      final GamePlayer next = sequence.getStep((fromStep + i) % size).getPlayerId();
      if (next != null && !next.equals(player) && !next.isNull()) {
        return data.getRelationshipTracker().isAllied(player, next)
            ? Optional.of(next)
            : Optional.empty();
      }
    }
    return Optional.empty();
  }
}
