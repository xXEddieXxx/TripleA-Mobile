package org.triplea.mobile;

import games.strategy.engine.chat.Chat;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.framework.AutoSaveFileUtils;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing;
import games.strategy.engine.framework.startup.launcher.LaunchAction;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.delegate.EndRoundDelegate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/** {@link LaunchAction} for the mobile client: no Swing, no networking. */
@Slf4j
public class MobileLaunchAction implements LaunchAction {
  private final GameEventListener listener;
  private final PlayerTypes.Type humanPlayerType;
  private final AutoSaveFileUtils autoSaveFileUtils = new AutoSaveFileUtils();

  /** The running game, for its winners at the end; set in startGame. */
  private @Nullable IGame game;

  public MobileLaunchAction(
      final GameEventListener listener, final PlayerTypes.Type humanPlayerType) {
    this.listener = listener;
    this.humanPlayerType = humanPlayerType;
  }

  @Override
  public void onEnd(final String message) {
    log.info("Game ended: {}", message);
    listener.gameEnded(message);
  }

  @Override
  public Collection<PlayerTypes.Type> getPlayerTypes() {
    final List<PlayerTypes.Type> types = new ArrayList<>();
    types.add(humanPlayerType);
    types.addAll(PlayerTypes.getBuiltInPlayerTypes());
    types.add(PlayerKind.DOES_NOTHING_AI);
    return types;
  }

  @Override
  public void startGame(
      final LocalPlayers localPlayers,
      final IGame game,
      final Set<Player> players,
      @Nullable final Chat chat) {
    this.game = game;
    final GameData gameData = game.getData();
    final Path mapPath =
        InstalledMapsListing.searchAllMapsForMapName(gameData.getMapName())
            .orElseThrow(
                () -> new IllegalStateException("Unable to find map: " + gameData.getMapName()));
    final List<Path> assetPaths = new ArrayList<>();
    assetPaths.add(mapPath);
    MobileEngine.getEngineAssetsFolder().ifPresent(assetPaths::add);
    game.setResourceLoader(new ResourceLoader(assetPaths));
    game.setDisplay(new MobileDisplay(listener));
    game.setSoundChannel(new MobileSoundChannel(listener));
  }

  @Override
  public Path getAutoSaveFile() {
    return autoSaveFileUtils.getOddRoundAutoSaveFile();
  }

  @Override
  public void onLaunch(final ServerGame serverGame) {}

  @Override
  public AutoSaveFileUtils getAutoSaveFileUtils() {
    return autoSaveFileUtils;
  }

  @Override
  public boolean shouldMinimizeExpensiveAiUse() {
    return false;
  }

  @Override
  public void handleError(final String error) {
    log.error(error);
    listener.message("Error", error);
  }

  @Override
  public boolean promptGameStop(
      final String status, final String title, @Nullable final Path mapLocation) {
    // as the server's ServerLaunchAction: the engine ended the game, tell the app who won
    // a map whose end-round delegate is not named "endRound" has none
    final EndRoundDelegate endRound = game == null ? null : game.getData().getEndRoundDelegate();
    final List<String> winners =
        endRound == null || endRound.getWinners() == null
            ? List.of()
            : endRound.getWinners().stream()
                .map(GamePlayer::getName)
                .collect(Collectors.toList());
    log.info("Game over: {} {}", title, winners);
    listener.gameEnded(title, winners);
    return true;
  }

  @Override
  public PlayerTypes.Type getDefaultLocalPlayerType() {
    return humanPlayerType;
  }
}
