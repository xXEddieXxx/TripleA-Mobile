package games.strategy.engine.posted.game.pbem;

import games.strategy.engine.data.GameState;
import games.strategy.engine.history.IDelegateHistoryWriter;
import java.io.Serializable;

/**
 * Play-by-email / play-by-forum posting is not supported in the mobile engine. This stub keeps the
 * delegate interfaces compatible while always reporting that no messengers are configured.
 */
public final class PbemMessagePoster implements Serializable {
  private static final long serialVersionUID = 1L;

  private PbemMessagePoster() {}

  public static boolean gameDataHasPlayByEmailOrForumMessengers(final GameState gameData) {
    return false;
  }

  public boolean hasMessengers() {
    return false;
  }

  public boolean post(final IDelegateHistoryWriter historyWriter, final String title) {
    return false;
  }
}
