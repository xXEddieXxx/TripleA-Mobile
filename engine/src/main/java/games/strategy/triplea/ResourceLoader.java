package games.strategy.triplea;

import games.strategy.triplea.ui.OrderedProperties;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds map resources (polygon files, properties, images) below one or more asset folders. The
 * first folder that contains a requested path wins. Unlike the desktop version this does not use a
 * class loader, so it behaves identically on Android and on the JVM.
 */
@Slf4j
public class ResourceLoader implements Closeable {
  public static final String ASSETS_FOLDER = "assets";

  @Getter private final List<Path> assetPaths;

  /** Lower-cased file name to real path, per directory; for case-insensitive fallbacks. */
  private final Map<Path, Map<String, Path>> directoryIndex = new ConcurrentHashMap<>();

  public static String getAssetsFileLocation(final String... assetsImageFileStrings) {
    final StringBuilder sb = new StringBuilder(ASSETS_FOLDER);
    for (final String element : assetsImageFileStrings) {
      sb.append('/').append(element);
    }
    return sb.toString();
  }

  public ResourceLoader(@Nonnull final Path assetFolderPath) {
    this(List.of(assetFolderPath));
  }

  public ResourceLoader(final List<Path> assetPaths) {
    this.assetPaths = List.copyOf(assetPaths);
  }

  @Override
  public void close() {}

  public boolean hasPathString(final String pathString) {
    return optionalResource(pathString).isPresent();
  }

  public @Nullable URL getResource(final String inputPathString) {
    return optionalResource(inputPathString).flatMap(ResourceLoader::toUrl).orElse(null);
  }

  public @Nullable URL getResource(final String inputPathString, final String inputPathString2) {
    return optionalResource(inputPathString)
        .or(() -> optionalResource(inputPathString2))
        .flatMap(ResourceLoader::toUrl)
        .orElse(null);
  }

  /**
   * Returns the file for the given resource path, searching the map folders in order. Many maps
   * were made on Windows where "Infantry.png" and "infantry.png" are the same file; Android's file
   * system is case sensitive, so an exact miss is retried ignoring case.
   */
  public Optional<Path> optionalResource(final String pathString) {
    final String normalized = stripTrailingSlash(pathString);
    for (final Path assetPath : assetPaths) {
      final Path candidate = assetPath.resolve(normalized);
      if (Files.exists(candidate)) {
        return Optional.of(candidate);
      }
      final Path fallback = assetPath.resolve(ASSETS_FOLDER).resolve(normalized);
      if (Files.exists(fallback)) {
        return Optional.of(fallback);
      }
    }
    for (final Path assetPath : assetPaths) {
      final Optional<Path> insensitive = resolveIgnoringCase(assetPath, normalized);
      if (insensitive.isPresent()) {
        return insensitive;
      }
    }
    return Optional.empty();
  }

  private Optional<Path> resolveIgnoringCase(final Path root, final String relative) {
    Path current = root;
    for (final String segment : relative.replace('\\', '/').split("/")) {
      if (segment.isEmpty() || segment.equals(".")) {
        continue;
      }
      final Path exact = current.resolve(segment);
      if (Files.exists(exact)) {
        current = exact;
        continue;
      }
      final Path match = indexDirectory(current).get(segment.toLowerCase(Locale.ROOT));
      if (match == null) {
        return Optional.empty();
      }
      current = match;
    }
    return current.equals(root) ? Optional.empty() : Optional.of(current);
  }

  private Map<String, Path> indexDirectory(final Path directory) {
    return directoryIndex.computeIfAbsent(
        directory,
        dir -> {
          final Map<String, Path> index = new HashMap<>();
          if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
              files.forEach(
                  f -> index.putIfAbsent(f.getFileName().toString().toLowerCase(Locale.ROOT), f));
            } catch (final IOException e) {
              log.warn("Failed to list directory: " + dir, e);
            }
          }
          return index;
        });
  }

  public Path requiredResource(final String pathString) throws IOException {
    return optionalResource(pathString).orElseThrow(() -> new FileNotFoundException(pathString));
  }

  /** Opens a resource for reading, empty if it does not exist. */
  public Optional<InputStream> openResource(final String pathString) {
    return optionalResource(pathString)
        .flatMap(
            path -> {
              try {
                return Optional.of(Files.newInputStream(path));
              } catch (final IOException e) {
                log.error("Failed to open resource: " + path, e);
                return Optional.empty();
              }
            });
  }

  /** Lists direct children of a resource directory, or the file itself if it is a file. */
  public List<URL> listResources(final String directoryPath) {
    final Optional<Path> dir = optionalResource(directoryPath);
    if (dir.isEmpty()) {
      return List.of();
    }
    if (Files.isDirectory(dir.get())) {
      try (var files = Files.list(dir.get())) {
        return files.map(ResourceLoader::toUrl).flatMap(Optional::stream).toList();
      } catch (final IOException e) {
        log.error("Failed to list resources at: " + directoryPath, e);
        return List.of();
      }
    }
    return toUrl(dir.get()).map(List::of).orElse(List.of());
  }

  public Properties loadPropertyFile(final String fileName) {
    final Properties properties = new OrderedProperties();
    final Optional<InputStream> stream = openResource(fileName);
    if (stream.isPresent()) {
      try (InputStream inputStream = stream.get()) {
        properties.load(inputStream);
      } catch (final IOException e) {
        log.error("Error reading " + fileName, e);
      }
    }
    return properties;
  }

  private static String stripTrailingSlash(final String pathString) {
    String result = pathString;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static Optional<URL> toUrl(final Path path) {
    try {
      return Optional.of(path.toUri().toURL());
    } catch (final MalformedURLException e) {
      log.error("Failed to convert path to URL: " + path, e);
      return Optional.empty();
    }
  }
}
