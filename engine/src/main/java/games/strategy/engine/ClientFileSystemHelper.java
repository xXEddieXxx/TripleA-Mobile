package games.strategy.engine;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NonNls;

/**
 * Locates the folders the engine reads and writes: installed maps and save games. On the desktop
 * these live below the user's home folder. On Android there is no home folder, so the hosting app
 * must call {@link #setUserRootFolder(Path)} once at startup with an app-private directory.
 */
@Slf4j
public final class ClientFileSystemHelper {
  @NonNls public static final String USER_ROOT_FOLDER_NAME = "triplea";
  @NonNls public static final String MAPS_FOLDER_NAME = "downloadedMaps";
  @NonNls public static final String SAVE_GAMES_FOLDER_NAME = "savedGames";

  @Nullable private static volatile Path userRootFolder;

  private ClientFileSystemHelper() {}

  /** Configures where user data (maps, save games) lives. Must be called before any game loads. */
  public static void setUserRootFolder(final Path rootFolder) {
    Preconditions.checkNotNull(rootFolder);
    userRootFolder = rootFolder;
    createDirectoriesQuietly(rootFolder);
    createDirectoriesQuietly(rootFolder.resolve(MAPS_FOLDER_NAME));
    createDirectoriesQuietly(rootFolder.resolve(SAVE_GAMES_FOLDER_NAME));
  }

  /** Returns the folder that holds all user data. Falls back to the JVM working directory. */
  public static Path getUserRootFolder() {
    final Path configured = userRootFolder;
    if (configured != null) {
      return configured;
    }
    final Path fallback = Path.of(System.getProperty("user.dir")).resolve(USER_ROOT_FOLDER_NAME);
    log.warn("User root folder not configured, falling back to {}", fallback);
    setUserRootFolder(fallback);
    return fallback;
  }

  /** Returns the folder containing installed maps, one sub folder per map. */
  public static Path getUserMapsFolder() {
    final Path mapsFolder = getUserRootFolder().resolve(MAPS_FOLDER_NAME);
    createDirectoriesQuietly(mapsFolder);
    return mapsFolder;
  }

  /** Returns the folder where save games are stored. */
  public static Path getSaveGamesFolder() {
    final Path saveFolder = getUserRootFolder().resolve(SAVE_GAMES_FOLDER_NAME);
    createDirectoriesQuietly(saveFolder);
    return saveFolder;
  }

  private static void createDirectoriesQuietly(final Path folder) {
    if (!Files.exists(folder)) {
      try {
        Files.createDirectories(folder);
      } catch (final IOException e) {
        log.error("Could not create folder: {}", folder.toAbsolutePath(), e);
      }
    }
  }
}
