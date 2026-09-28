package games.strategy.engine.data.properties;

import org.triplea.geom.Color;

/** Implementation of {@link IEditableProperty} for a color value. */
public class ColorProperty extends AbstractEditableProperty<Color> {
  private static final long serialVersionUID = 6826763550643504789L;

  private Color color;

  public ColorProperty(final String name, final String description, final Color def) {
    super(name, description);
    color = def == null ? Color.BLACK : def; // mobile: geom Color has no lowercase aliases
  }

  @Override
  public Color getValue() {
    return color;
  }

  @Override
  public void setValue(final Color value) {
    color = value == null ? Color.BLACK : value; // mobile: see above
  }

  @Override
  public boolean validate(final Object value) {
    return (value == null) || (value instanceof Color);
  }
}
