package games.strategy.engine.framework.startup.launcher;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Platform hooks the engine calls while launching and running a game. The desktop client has a
 * Swing implementation, the mobile app provides its own. All networking related hooks of the
 * desktop version have been removed.
 */
public interface LaunchAction {
  /** Called when the game ended (victory, defeat or the user stopped the game). */
  void onEnd(String message);

  /** Player types the user may choose from when setting up a game. */
  Collection<PlayerTypes.Type> getPlayerTypes();

  /**
   * Called once the players have been created and the game is about to start. Implementations
   * typically install the display, sound channel and resource loader on the game.
   */
  void startGame(LocalPlayers localPlayers, IGame game, Set<Player> players, @Nullable Chat chat);

  Path getAutoSaveFile();

  /** Called with the fully constructed server game right before the game loop begins. */
  void onLaunch(ServerGame serverGame);

  AutoSaveFileUtils getAutoSaveFileUtils();

  /**
   * Controls if the AI should be avoided when preparing a game. Headless systems may choose to
   * avoid AI usage where possible to reduce the load on the system.
   */
  boolean shouldMinimizeExpensiveAiUse();

  void handleError(String error);

  /**
   * Called when the engine wants to stop the game sequence. Implementations may ask the user.
   *
   * @return true if the game should stop execution, false otherwise.
   */
  boolean promptGameStop(String status, String title, @Nullable Path mapLocation);

  PlayerTypes.Type getDefaultLocalPlayerType();
}
