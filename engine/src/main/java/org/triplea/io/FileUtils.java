package org.triplea.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/** A collection of useful methods related to files. */
@UtilityClass
@Slf4j
public final class FileUtils {
  // mobile: newTempFolder, createTempFile and replaceFolder (unused, Sonar S5443) removed

  /**
   * Returns a collection of abstract pathnames denoting the files and directories in the specified
   * directory.
   *
   * @param directory The directory whose files are to be listed.
   * @return An immutable collection of files. If {@code directory} does not denote a directory, the
   *     collection will be empty.
   * @see Files#list(Path)
   */
  public static Collection<Path> listFiles(final Path directory) {
    if (directory == null) {
      throw new NullPointerException();
    }
    try (Stream<Path> stream = Files.list(directory)) {
      return stream.collect(Collectors.toList());
    } catch (final IOException exception) {
      log.error("Failed to list Files in directory " + directory, exception);
      return List.of();
    }
  }

  public static URL toUrl(final Path file) {
    try {
      return file.toUri().toURL();
    } catch (final MalformedURLException e) {
      throw new IllegalStateException(
          "Invalid conversion from file to URL, file: " + file.toAbsolutePath(), e);
    }
  }

  /**
   * Searches a file system starting from a given directory looking for a file or directory with a
   * matching name. If multiple matching paths are found, returns the one closest to the root (via
   * minimum length of the absolute path).
   *
   * @param searchRoot The directory whose contents we will search (and subdirectories).
   * @param maxDepth The maximum number of subdirectories to search. Zero means only search the
   *     'searchRoot' directory.
   * @param fileName The name of the file to be search for.
   * @return A file matching the given name or empty if not found.
   */
  public Optional<Path> findClosestToRoot(
      final Path searchRoot, final int maxDepth, final String fileName) {
    return find(searchRoot, maxDepth, fileName).stream().findAny();
  }

  /**
   * Searches a file system starting from a given directory looking for files or directories with
   * matching names. The resulting list will be in ascending order by absolute path length.
   *
   * @param searchRoot The directory whose contents we will search (and subdirectories).
   * @param maxDepth The maximum number of subdirectories to search. Zero means only search the
   *     'searchRoot' directory.
   * @param fileName The name of the file to be search for.
   * @return A list of files matching the given name or an empty list if not.
   */
  public List<Path> find(final Path searchRoot, final int maxDepth, final String fileName) {
    if (!Files.isDirectory(searchRoot)) {
      throw new IllegalArgumentException(searchRoot.toAbsolutePath().toString());
    }
    if (maxDepth < 0) {
      throw new IllegalArgumentException();
    }
    if (fileName.isBlank()) {
      throw new IllegalArgumentException();
    }
    try (Stream<Path> files = Files.walk(searchRoot, maxDepth)) {
      return files
          .filter(f -> f.getFileName().toString().equals(fileName))
          // Sort by path length (shortest to longest), so that the ordering is deterministic and
          // paths closer to the root are earlier in the list.
          .sorted(Comparator.comparingInt(f -> f.toAbsolutePath().toString().length()))
          .collect(Collectors.toList());
    } catch (final IOException e) {
      log.error(
          "Unable to access files in: " + searchRoot.toAbsolutePath() + ", " + e.getMessage(), e);
      return List.of();
    }
  }

  /**
   * Recursively searches current folder and parent folders for a given file name.
   *
   * @return Path to file or empty if not found.
   */
  public Optional<Path> findFileInParentFolders(final Path searchRoot, final String fileName) {
    if (searchRoot == null) {
      return Optional.empty();
    }

    if (Files.exists(searchRoot.resolve(fileName))) {
      return Optional.of(searchRoot.resolve(fileName));
    } else {
      return findFileInParentFolders(searchRoot.getParent(), fileName);
    }
  }

  /**
   * Method to conveniently open an input stream reading a file and run a function on that input
   * stream producing some return value.
   *
   * @param file The file to be read.
   * @param inputStreamFunction Function to be run on the input stream (if able to open).
   * @return Return value of the inputStream function.
   * @throws UnableToReadFileException thrown if there are exceptions generated when opening the
   *     input stream.
   * @throws IllegalArgumentException thrown if input file does not exist or is not a file.
   */
  public <T> T openInputStream(
      final Path file, final Function<InputStream, T> inputStreamFunction) {
    if (!Files.exists(file)) {
      throw new IllegalArgumentException(file.toAbsolutePath().toString());
    }
    if (!Files.isReadable(file)) {
      throw new IllegalArgumentException(file.toAbsolutePath().toString());
    }

    try (InputStream inputStream = Files.newInputStream(file)) {
      return inputStreamFunction.apply(inputStream);
    } catch (final IOException e) {
      throw new UnableToReadFileException(file, e);
    }
  }

  private static class UnableToReadFileException extends RuntimeException {
    private static final long serialVersionUID = -3739909439458686372L;

    UnableToReadFileException(final Path file, final IOException e) {
      super("Unable to open file: " + file.toAbsolutePath() + ", " + e.getMessage(), e);
    }
  }

  public static Collection<Path> findXmlFiles(final Path mapFolder, final int maxXmlSearchDepth) {
    try (Stream<Path> files = Files.walk(mapFolder, maxXmlSearchDepth)) {
      return files
          .filter(Predicate.not(Files::isDirectory))
          .filter(file -> file.getFileName().toString().endsWith(".xml"))
          .collect(Collectors.toList());
    } catch (final IOException e) {
      throw new FileSystemReadError(mapFolder, e);
    }
  }

  private static class FileSystemReadError extends RuntimeException {
    private static final long serialVersionUID = -1962042508193702048L;

    FileSystemReadError(final Path file, final IOException e) {
      super("Error reading files in: " + file.toAbsolutePath(), e);
    }
  }

  /**
   * Reads and returns the contents of a given file. Returns empty if the file does not exist or if
   * there were any errors reading the file. Character encodings allowed: UTf-8, ISO_8859_1
   */
  public static Optional<String> readContents(final Path fileToRead) {
    if (!Files.exists(fileToRead)) {
      return Optional.empty();
    }

    // try to read the file with default character encoding, if fails then fallback
    // to UTF-8 then try ISO-8859_1
    try {
      try {
        return Optional.of(new String(Files.readAllBytes(fileToRead), StandardCharsets.UTF_8));
      } catch (final MalformedInputException e) {
        log.info(
            "Warning: file was not saved as UTF-8, some characters may not render:  {}, {}",
            fileToRead.toAbsolutePath(),
            e.getMessage());
      }

      try {
        return Optional.of(new String(Files.readAllBytes(fileToRead), StandardCharsets.ISO_8859_1));
      } catch (final MalformedInputException e) {
        log.warn(
            "Bad file encoding: "
                + fileToRead.toAbsolutePath()
                + ", contact the map maker and ask them to save this file as UTF-8");
        return Optional.empty();
      }
    } catch (final IOException e) {
      log.error("Error reading file: {}, {}", fileToRead.toAbsolutePath(), e.getMessage(), e);
    }
    return Optional.empty();
  }

  public static void writeToFile(final Path fileToWrite, final String contents) {
    try {
      Files.write(fileToWrite, contents.getBytes(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      log.error("Failed to write file: {}, {}", fileToWrite.toAbsolutePath(), e.getMessage(), e);
    }
  }

  /**
   * Utility to delete file specified by the given path. This method handles any needed logging if
   * the delete fails.
   */
  public static void delete(final Path pathToDelete) {
    try {
      Files.delete(pathToDelete);
    } catch (final IOException e) {
      log.error("Failed to delete file: {}, {}", pathToDelete.toAbsolutePath(), e.getMessage(), e);
    }
  }

  public static void deleteDirectory(final Path path) throws IOException {
    org.apache.commons.io.FileUtils.deleteDirectory(path.toFile());
  }

  /**
   * Returns the file system 'last modified' time stamp for a given path. Returns an empty if the
   * path does not exist or if there are any errors reading the last modified time stamp.
   */
  public static Optional<Instant> getLastModified(final Path path) {
    if (!Files.exists(path)) {
      return Optional.empty();
    }

    try {
      return Optional.of(Files.getLastModifiedTime(path).toInstant());
    } catch (final IOException e) {
      log.error("Unable to read file system at: " + path + ", " + e.getMessage(), e);
      return Optional.empty();
    }
  }
}
