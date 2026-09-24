package games.strategy.triplea.settings;

import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Mobile replacement for the desktop client's preference backed settings. Only the handful of
 * settings that the engine itself reads are kept. Values live in memory and are configured by the
 * host application at startup (for example, the save games folder).
 *
 * @param <T> The value type of the setting.
 */
public final class ClientSetting<T> {
  public static final ClientSetting<Integer> aiMovePauseDuration = new ClientSetting<>(0);
  public static final ClientSetting<Integer> aiCombatStepPauseDuration = new ClientSetting<>(0);
  public static final ClientSetting<Integer> serverObserverJoinWaitTime = new ClientSetting<>(180);
  public static final ClientSetting<Boolean> useWebsocketNetwork = new ClientSetting<>(false);
  public static final ClientSetting<Boolean> showSerializeFeatures = new ClientSetting<>(false);
  public static final ClientSetting<Path> saveGamesFolderPath = new ClientSetting<>(null);
  public static final ClientSetting<Path> mapFolderOverride = new ClientSetting<>(null);

  @Nullable private final T defaultValue;
  @Nullable private volatile T value;

  private ClientSetting(@Nullable final T defaultValue) {
    this.defaultValue = defaultValue;
    this.value = defaultValue;
  }

  public Optional<T> getValue() {
    return Optional.ofNullable(value);
  }

  public T getValueOrThrow() {
    return getValue()
        .orElseThrow(() -> new IllegalStateException("Setting has no value configured"));
  }

  public void setValue(@Nullable final T newValue) {
    value = newValue;
  }

  public void resetValue() {
    value = defaultValue;
  }
}
