package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import java.util.Collection;
import org.triplea.sound.ISound;

/**
 * Sound channel of the mobile client: every sound the engine broadcasts is handed to the {@link
 * GameEventListener}, which decides whether and how to play it. All players share one device, so
 * sounds addressed to specific players are played as well.
 */
public class MobileSoundChannel implements ISound {
  private final GameEventListener listener;

  public MobileSoundChannel(final GameEventListener listener) {
    this.listener = listener;
  }

  @Override
  public void playSoundForAll(final String clipName, final GamePlayer gamePlayer) {
    listener.playSound(clipName, gamePlayer);
  }

  @Override
  public void playSoundToPlayers(
      final String clipName,
      final Collection<GamePlayer> playersToSendTo,
      final Collection<GamePlayer> butNotThesePlayers,
      final boolean includeObservers) {
    if (playersToSendTo == null || playersToSendTo.isEmpty()) {
      return;
    }
    listener.playSound(clipName, playersToSendTo.iterator().next());
  }
}
