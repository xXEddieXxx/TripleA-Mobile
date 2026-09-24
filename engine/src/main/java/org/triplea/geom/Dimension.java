package org.triplea.geom;

import java.io.Serializable;

/** Minimal replacement for {@code java.awt.Dimension}. */
public class Dimension implements Serializable {
  private static final long serialVersionUID = 1L;

  public int width;
  public int height;

  public Dimension() {}

  public Dimension(final int width, final int height) {
    this.width = width;
    this.height = height;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof Dimension d && d.width == width && d.height == height;
  }

  @Override
  public int hashCode() {
    return 31 * width + height;
  }

  @Override
  public String toString() {
    return "Dimension[" + width + "x" + height + "]";
  }
}
