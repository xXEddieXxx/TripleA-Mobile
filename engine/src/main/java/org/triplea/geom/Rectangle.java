package org.triplea.geom;

import java.io.Serializable;

/** Minimal replacement for {@code java.awt.Rectangle}. */
public class Rectangle implements Serializable {
  private static final long serialVersionUID = 1L;

  public int x;
  public int y;
  public int width;
  public int height;

  public Rectangle() {}

  public Rectangle(final int x, final int y, final int width, final int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }

  public Rectangle(final Rectangle r) {
    this(r.x, r.y, r.width, r.height);
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public double getMaxX() {
    return x + (double) width;
  }

  public double getMaxY() {
    return y + (double) height;
  }

  public double getCenterX() {
    return x + width / 2.0;
  }

  public double getCenterY() {
    return y + height / 2.0;
  }

  public Dimension getSize() {
    return new Dimension(width, height);
  }

  public Point getLocation() {
    return new Point(x, y);
  }

  public void translate(final int dx, final int dy) {
    x += dx;
    y += dy;
  }

  public boolean contains(final int px, final int py) {
    return px >= x && py >= y && px < x + width && py < y + height;
  }

  public boolean contains(final Point p) {
    return contains(p.x, p.y);
  }

  public boolean intersects(final Rectangle r) {
    return r.x < x + width && r.x + r.width > x && r.y < y + height && r.y + r.height > y;
  }

  /** Grows this rectangle to also cover the given rectangle (union). */
  public void add(final Rectangle r) {
    final int x1 = Math.min(x, r.x);
    final int y1 = Math.min(y, r.y);
    final int x2 = Math.max(x + width, r.x + r.width);
    final int y2 = Math.max(y + height, r.y + r.height);
    x = x1;
    y = y1;
    width = x2 - x1;
    height = y2 - y1;
  }

  public Rectangle union(final Rectangle r) {
    final Rectangle result = new Rectangle(this);
    result.add(r);
    return result;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof Rectangle r
        && r.x == x
        && r.y == y
        && r.width == width
        && r.height == height;
  }

  @Override
  public int hashCode() {
    return ((x * 31 + y) * 31 + width) * 31 + height;
  }

  @Override
  public String toString() {
    return "Rectangle[x=" + x + ",y=" + y + ",w=" + width + ",h=" + height + "]";
  }
}
