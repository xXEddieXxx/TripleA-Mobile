package org.triplea.mobile;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.message.PlayerListing;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.message.IRemote;
import games.strategy.engine.player.Player;
import games.strategy.engine.random.PlainRandomSource;
import games.strategy.net.LocalNoOpMessenger;
import games.strategy.net.Messengers;
import games.strategy.net.websocket.ClientNetworkBridge;
import games.strategy.triplea.ResourceLoader;
import games.strategy.triplea.ui.mapdata.MapData;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A running (or ready to run) single device game: the engine, its data and the map graphics data.
 * Create one with {@link #create}, then either call {@link #start()} to run the game loop on a
 * background thread, or drive it step by step with {@link #runNextStep()} (used by tests).
 */
@Slf4j
public final class LocalGameSession {
  @Getter private final GameData gameData;
  @Getter private final ServerGame serverGame;
  @Getter private final ResourceLoader resourceLoader;
  @Getter private final MapData mapData;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile Thread gameThread;

  private LocalGameSession(
      final GameData gameData,
      final ServerGame serverGame,
      final ResourceLoader resourceLoader,
      final MapData mapData) {
    this.gameData = gameData;
    this.serverGame = serverGame;
    this.resourceLoader = resourceLoader;
    this.mapData = mapData;
  }

  /**
   * Prepares a local game.
   *
   * @param gameData Freshly parsed game or a loaded save game.
   * @param playerKinds Who controls each nation, keyed by nation name. Nations missing from the map
   *     are controlled by the default AI.
   * @param humanUi The UI bridge for human players.
   * @param listener Receives engine events (battles, dice, messages).
   */
  public static LocalGameSession create(
      final GameData gameData,
      final Map<String, PlayerKind> playerKinds,
      final HumanPlayerUi humanUi,
      final GameEventListener listener) {
    final PlayerTypes.Type humanType = PlayerKind.humanType(humanUi);
    final MobileLaunchAction launchAction = new MobileLaunchAction(listener, humanType);
    final PlayerTypes playerTypes = new PlayerTypes(launchAction.getPlayerTypes());

    final Map<String, PlayerTypes.Type> typeByPlayer = new HashMap<>();
    final Map<String, Boolean> enabledByPlayer = new HashMap<>();
    gameData
        .getPlayerList()
        .getPlayers()
        .forEach(
            gamePlayer -> {
              final PlayerKind kind =
                  playerKinds.getOrDefault(gamePlayer.getName(), PlayerKind.AI_FAST);
              typeByPlayer.put(gamePlayer.getName(), kind.toPlayerType(humanUi));
              enabledByPlayer.put(gamePlayer.getName(), true);
            });

    final PlayerListing playerListing =
        new PlayerListing(
            enabledByPlayer,
            typeByPlayer,
            gameData.getGameName(),
            String.valueOf(gameData.getSequence().getRound()));
    playerListing.doPreGameStartDataModifications(gameData);

    final Messengers messengers = new Messengers(new LocalNoOpMessenger());
    final Set<Player> players =
        gameData.getGameLoader().newPlayers(playerListing.getLocalPlayerTypeMap(playerTypes));
    final ServerGame game =
        new ServerGame(
            gameData,
            players,
            new HashMap<>(),
            messengers,
            ClientNetworkBridge.NO_OP_SENDER,
            launchAction);
    game.setRandomSource(new PlainRandomSource());
    // step-by-step autosaves are slow on a phone; the app offers manual saves instead
    game.setDelegateAutosavesEnabled(false);
    gameData.addGameDataEventListener(
        GameDataEvent.GAME_STEP_CHANGED,
        () -> {
          final GameStep step = gameData.getSequence().getStep();
          listener.gameStepChanged(
              step.getName(),
              step.getDisplayName(),
              step.getPlayerId(),
              gameData.getSequence().getRound());
        });
    gameData.getGameLoader().startGame(game, players, launchAction, null);

    final ResourceLoader resourceLoader = game.getResourceLoader();
    final MapData mapData = new MapData(resourceLoader);
    return new LocalGameSession(gameData, game, resourceLoader, mapData);
  }

  /** Runs the game loop on a new daemon thread until the game is over or stopped. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("game already started");
    }
    final Thread thread =
        new Thread(
            () -> {
              try {
                serverGame.startGame();
              } catch (final RuntimeException e) {
                log.error("Game loop terminated with an error", e);
              }
            },
            "triplea-game");
    thread.setDaemon(true);
    gameThread = thread;
    thread.start();
  }

  /** Prepares the engine for step-wise execution (only needed when not calling {@link #start}). */
  public void setUpForSteps() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("game already started");
    }
    serverGame.setUpGameForRunningSteps();
  }

  /** Runs a single game step. Requires {@link #setUpForSteps()} first. */
  public void runNextStep() {
    serverGame.runNextStep();
  }

  public boolean isGameOver() {
    return serverGame.isGameOver();
  }

  /** Notified after every change to the game data, on the thread that made the change. */
  public void addChangeListener(final GameDataChangeListener listener) {
    gameData.addDataChangeListener(listener);
  }

  public void removeChangeListener(final GameDataChangeListener listener) {
    gameData.removeDataChangeListener(listener);
  }

  /**
   * Returns the remote proxy for a delegate, e.g. {@code getRemoteDelegate("move",
   * IMoveDelegate.class)}. The UI may call read-only queries and undo operations on it from any
   * thread while the delegate is active, exactly like the desktop client does.
   */
  public <T extends IRemote> T getRemoteDelegate(final String delegateName, final Class<T> type) {
    final IDelegate delegate = gameData.getDelegate(delegateName);
    return type.cast(serverGame.getMessengers().getRemote(ServerGame.getRemoteName(delegate)));
  }

  /** Returns the remote proxy of the delegate that is currently executing. */
  public <T extends IRemote> T getCurrentRemoteDelegate(final Class<T> type) {
    final String delegateName = gameData.getSequence().getStep().getDelegate().getName();
    return getRemoteDelegate(delegateName, type);
  }

  public String describePlayers() {
    return serverGame.getPlayerManager().getPlayers().stream()
        .map(Object::toString)
        .collect(Collectors.joining(", "));
  }

  public void saveGame(final Path file) {
    serverGame.saveGame(file);
  }

  /** The thread running the game loop, null before start. */
  public Thread getGameThread() {
    return gameThread;
  }

  /** Stops the game; blocks the calling thread only briefly. */
  public void stop() {
    if (!serverGame.isGameOver()) {
      serverGame.stopGame();
    }
    final Thread thread = gameThread;
    if (thread != null) {
      thread.interrupt();
    }
  }
}
