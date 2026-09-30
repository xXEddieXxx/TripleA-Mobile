package org.triplea.mobile;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.engine.history.History;
import games.strategy.engine.history.HistoryNode;
import lombok.Getter;

/**
 * The desktop client's "Show History" mode: a clone of the game data that is wound back to any
 * point of the history while the game itself goes on untouched. Create one with {@link #of}, move
 * it with {@link #gotoNode} and render from {@link #getGameData()}.
 */
public final class HistoryView {
  @Getter private final GameData gameData;

  private HistoryView(final GameData clone) {
    this.gameData = clone;
  }

  /**
   * Clones the live game data under its write lock, like the desktop frame does; the clone starts
   * at the end of the history. Costs a serialization round trip of the whole game.
   */
  public static HistoryView of(final GameData live) {
    try (GameData.Unlocker ignored = live.acquireWriteLock()) {
      return new HistoryView(GameDataUtils.cloneGameDataKeepSameHistory(live, true));
    }
  }

  /**
   * Winds the clone to the state at the history node addressed by child indices from the root
   * (round, step, event, detail), exactly like selecting that node in the desktop tree: a node
   * with children shows the state before it, a leaf the state after it, no indices the start of
   * the game.
   *
   * @return false when there is no such node, which means it happened after this clone was made.
   */
  public boolean gotoNode(final int... path) {
    final History history = gameData.getHistory();
    HistoryNode node = (HistoryNode) history.getRoot();
    for (final int index : path) {
      if (index < 0 || index >= node.getChildCount()) {
        return false;
      }
      node = (HistoryNode) node.getChildAt(index);
    }
    history.gotoNode(
        history.getNearestLeafAtOrBefore(node).orElse((HistoryNode) history.getRoot()));
    return true;
  }
}
