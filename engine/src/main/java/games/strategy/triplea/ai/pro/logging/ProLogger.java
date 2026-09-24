package games.strategy.triplea.ai.pro.logging;

import java.util.logging.Level;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logger used by the Pro AI. The desktop client shows these messages in a dedicated log window;
 * on mobile the messages go to the standard logger. Logging is off by default because the AI logs
 * a great deal and the messages are only useful when debugging AI behaviour.
 */
public final class ProLogger {
  private static final Logger log = LoggerFactory.getLogger(ProLogger.class);

  private static volatile boolean enabled = false;
  private static volatile Level logLevel = Level.FINE;

  private ProLogger() {}

  /** Enables or disables Pro AI logging globally. */
  public static void setEnabled(final boolean value) {
    enabled = value;
  }

  /** The most detailed level that is still logged when logging is enabled. */
  public static void setLogLevel(final Level level) {
    logLevel = level;
  }

  public static void warn(final String message) {
    log(Level.WARNING, message);
  }

  public static void info(final String message) {
    log(Level.FINE, message);
  }

  public static void debug(final String message) {
    log(Level.FINER, message);
  }

  public static void trace(final String message) {
    log(Level.FINEST, message);
  }

  private static void log(final Level level, final String message) {
    log(level, message, null);
  }

  /** Logs the message if AI logging is enabled and the message level is coarse enough. */
  public static void log(final Level level, final String message, final @Nullable Throwable t) {
    if (!enabled) {
      return;
    }
    if (level.intValue() < logLevel.intValue()) {
      return;
    }
    final String formatted = formatMessage(message, t, level);
    if (level.intValue() >= Level.WARNING.intValue()) {
      log.warn(formatted);
    } else {
      log.info(formatted);
    }
  }

  /**
   * Adds extra spaces to get logs to lineup correctly. (Adds two spaces to fine, one to finer, none
   * to finest, etc.)
   */
  private static String formatMessage(
      final String message, final @Nullable Throwable t, final Level level) {
    final int compensateLength = (level.toString().length() - 4) * 2;
    final StringBuilder builder = new StringBuilder(" ".repeat(Math.max(0, compensateLength)));
    builder.append(message);
    if (t != null) {
      builder.append(" (error: ").append(t.getMessage()).append(")");
    }
    return builder.toString();
  }
}
