package games.strategy.engine.data.properties;

import java.io.Serializable;

/**
 * A property that can be edited by the user. The mobile engine keeps the value model only; the
 * platform UI is responsible for rendering an editor for each property type.
 *
 * @param <T> The type of the property value.
 */
public interface IEditableProperty<T> extends Serializable {
  /** Returns the name of the property. */
  String getName();

  /** Returns the value of the property. */
  T getValue();

  boolean validate(Object value);

  /** Sets the value of the property programmatically. */
  void setValue(T value);

  String getDescription();

  int getRowsNeeded();

  @SuppressWarnings("unchecked")
  default boolean setValueIfValid(final Object object) {
    if (validate(object)) {
      setValue((T) object);
      return true;
    }
    return false;
  }

  default void validateAndSet(final Object object) {
    if (!setValueIfValid(object)) {
      throw new IllegalArgumentException(
          "Invalid value " + object + " for class " + getClass().getCanonicalName());
    }
  }
}
