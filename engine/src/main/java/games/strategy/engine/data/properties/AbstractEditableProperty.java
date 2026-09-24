package games.strategy.engine.data.properties;

import java.io.Serializable;
import java.util.Objects;

/**
 * Base class for editable properties, handles name and description.
 *
 * @param <T> The type of the property value.
 */
public abstract class AbstractEditableProperty<T>
    implements IEditableProperty<T>, Serializable, Comparable<AbstractEditableProperty<?>> {
  private static final long serialVersionUID = -5005729898242568847L;

  private final String name;
  private final String description;

  public AbstractEditableProperty(final String name, final String description) {
    this.name = name;
    this.description = description;
  }

  @Override
  public int getRowsNeeded() {
    return 1;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name);
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof AbstractEditableProperty
        && ((AbstractEditableProperty<?>) other).name.equals(name);
  }

  @Override
  public int compareTo(final AbstractEditableProperty<?> other) {
    return name.compareTo(other.getName());
  }

  @Override
  public String toString() {
    return getName() + "=" + getValue().toString();
  }
}
