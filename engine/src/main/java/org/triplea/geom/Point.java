package org.triplea.geom;

import java.io.Serializable;

/** Minimal replacement for {@code java.awt.Point}, usable on Android. */
public class Point implements Serializable, Cloneable {
  private static final long serialVersionUID = 1L;

  public int x;
  public int y;

  public Point() {}

  public Point(final int x, final int y) {
    this.x = x;
    this.y = y;
  }

  public Point(final Point other) {
    this(other.x, other.y);
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }

  public void translate(final int dx, final int dy) {
    x += dx;
    y += dy;
  }

  public double distance(final Point other) {
    final double dx = x - other.x;
    final double dy = y - other.y;
    return Math.sqrt(dx * dx + dy * dy);
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof Point p && p.x == x && p.y == y;
  }

  @Override
  public int hashCode() {
    return 31 * x + y;
  }

  @Override
  public Point clone() {
    return new Point(x, y);
  }

  @Override
  public String toString() {
    return "Point[x=" + x + ",y=" + y + "]";
  }
}
