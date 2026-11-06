package org.triplea.mobile.forum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.triplea.mobile.forum.NodeBbClient.GameThread;

/**
 * Posts one turn: uploads the save, then replies with the text that links it. A retry after a
 * failed reply reuses the uploaded save instead of uploading it again.
 */
public final class TurnPoster {
  private final NodeBbClient client;
  private final GameThread thread;
  private final Path save;
  private String saveUrl;

  public TurnPoster(final NodeBbClient client, final GameThread thread, final Path save) {
    this.client = client;
    this.thread = thread;
    this.save = save;
  }

  /**
   * @param contentForSaveUrl the post's text, given the forum's address of the uploaded save
   * @return whether the forum holds the post for a moderator
   */
  public synchronized boolean send(final UnaryOperator<String> contentForSaveUrl)
      throws IOException {
    if (saveUrl == null) {
      saveUrl = client.upload(save, "application/octet-stream");
    }
    return client.reply(thread, contentForSaveUrl.apply(saveUrl));
  }
}
