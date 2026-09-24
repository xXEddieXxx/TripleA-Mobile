package org.triplea.mobile;

import games.strategy.engine.ClientFileSystemHelper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.map.file.system.loader.InstalledMap;
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing;
import games.strategy.triplea.settings.ClientSetting;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Value;
import lombok.experimental.UtilityClass;

/** Entry point for the hosting app: configuration, map discovery and loading games. */
@UtilityClass
@Slf4j
public class MobileEngine {

  /** One playable game XML of an installed map. */
  @Value
  public static class InstalledGame {
    String mapName;
    String gameName;
    Path xmlPath;
  }

  @Nullable private static volatile Path engineAssetsFolder;

  /**
   * Must be called once before anything else. All maps and save games live below {@code
   * userRootFolder}; on Android pass an app private directory.
   */
  public static void configure(final Path userRootFolder) {
    ClientFileSystemHelper.setUserRootFolder(userRootFolder);
    ClientSetting.saveGamesFolderPath.setValue(ClientFileSystemHelper.getSaveGamesFolder());
  }

  /**
   * Folder with the engine's default images (units, flags, misc). Maps that do not ship their own
   * unit images fall back to these. Optional.
   */
  public static void setEngineAssetsFolder(@Nullable final Path folder) {
    engineAssetsFolder = folder;
  }

  public static Optional<Path> getEngineAssetsFolder() {
    return Optional.ofNullable(engineAssetsFolder);
  }

  public static Path getMapsFolder() {
    return ClientFileSystemHelper.getUserMapsFolder();
  }

  public static Path getSaveGamesFolder() {
    return ClientFileSystemHelper.getSaveGamesFolder();
  }

  /** Lists every game of every installed map, sorted by map and game name. */
  public static List<InstalledGame> listInstalledGames() {
    final List<InstalledGame> games = new ArrayList<>();
    for (final InstalledMap map : InstalledMapsListing.parseMapFiles().getInstalledMaps()) {
      try {
        for (final String gameName : map.getGameNames()) {
          map.getGameXmlFilePath(gameName)
              .ifPresent(xml -> games.add(new InstalledGame(map.getMapName(), gameName, xml)));
        }
      } catch (final RuntimeException e) {
        // a map folder being installed or removed right now, or a broken map.yml: skip it
        log.warn("Skipping map with unreadable game list: " + map.getMapName(), e);
      }
    }
    games.sort(
        Comparator.comparing(InstalledGame::getMapName).thenComparing(InstalledGame::getGameName));
    return games;
  }

  /** Parses a game XML into a new game ready to start. */
  public static Optional<GameData> parseGame(final Path gameXml) {
    return GameParser.parse(gameXml, false);
  }

  /** Loads a save game written by {@link #saveGame} or {@link LocalGameSession#saveGame}. */
  public static Optional<GameData> loadSaveGame(final Path saveFile) {
    return GameDataManager.loadGame(saveFile);
  }

  public static void saveGame(final GameData gameData, final Path saveFile) throws IOException {
    Files.createDirectories(saveFile.getParent());
    try (OutputStream out = Files.newOutputStream(saveFile)) {
      GameDataManager.saveGame(out, gameData);
    }
  }

  /** Lists save games, newest first. */
  public static List<Path> listSaveGames() {
    final Path folder = getSaveGamesFolder();
    try (var files = Files.list(folder)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".tsvg"))
          .sorted(
              Comparator.comparingLong(
                      (Path p) -> {
                        try {
                          return Files.getLastModifiedTime(p).toMillis();
                        } catch (final IOException e) {
                          return 0L;
                        }
                      })
                  .reversed())
          .toList();
    } catch (final IOException e) {
      return List.of();
    }
  }
}
