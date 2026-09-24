package org.triplea.geom;

import java.io.Serializable;
import java.util.Arrays;

/** Minimal replacement for {@code java.awt.Polygon}, usable on Android. */
public class Polygon implements Serializable {
  private static final long serialVersionUID = 1L;

  public int npoints;
  public int[] xpoints;
  public int[] ypoints;
  private transient Rectangle bounds;

  public Polygon() {
    xpoints = new int[4];
    ypoints = new int[4];
  }

  public Polygon(final int[] xpoints, final int[] ypoints, final int npoints) {
    if (npoints > xpoints.length || npoints > ypoints.length) {
      throw new IndexOutOfBoundsException("npoints > xpoints.length || npoints > ypoints.length");
    }
    this.npoints = npoints;
    this.xpoints = Arrays.copyOf(xpoints, npoints);
    this.ypoints = Arrays.copyOf(ypoints, npoints);
  }

  public void addPoint(final int x, final int y) {
    if (npoints >= xpoints.length) {
      xpoints = Arrays.copyOf(xpoints, Math.max(4, npoints * 2));
      ypoints = Arrays.copyOf(ypoints, Math.max(4, npoints * 2));
    }
    xpoints[npoints] = x;
    ypoints[npoints] = y;
    npoints++;
    bounds = null;
  }

  public void translate(final int dx, final int dy) {
    for (int i = 0; i < npoints; i++) {
      xpoints[i] += dx;
      ypoints[i] += dy;
    }
    bounds = null;
  }

  /** Returns a fresh copy of the bounding box so that callers may mutate it. */
  public Rectangle getBounds() {
    if (bounds == null) {
      if (npoints == 0) {
        bounds = new Rectangle();
      } else {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < npoints; i++) {
          minX = Math.min(minX, xpoints[i]);
          minY = Math.min(minY, ypoints[i]);
          maxX = Math.max(maxX, xpoints[i]);
          maxY = Math.max(maxY, ypoints[i]);
        }
        bounds = new Rectangle(minX, minY, maxX - minX, maxY - minY);
      }
    }
    return new Rectangle(bounds);
  }

  /** Even-odd point in polygon test, matching the semantics of the AWT polygon. */
  public boolean contains(final double x, final double y) {
    if (npoints <= 2 || !boundsContain(x, y)) {
      return false;
    }
    boolean inside = false;
    for (int i = 0, j = npoints - 1; i < npoints; j = i++) {
      final double xi = xpoints[i];
      final double yi = ypoints[i];
      final double xj = xpoints[j];
      final double yj = ypoints[j];
      if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
        inside = !inside;
      }
    }
    return inside;
  }

  public boolean contains(final int x, final int y) {
    return contains((double) x, (double) y);
  }

  public boolean contains(final Point p) {
    return contains(p.x, p.y);
  }

  /** Approximation of the AWT rectangle containment test: true if all corners are inside. */
  public boolean contains(final Rectangle r) {
    return contains(r.x, r.y)
        && contains(r.x + r.width, r.y)
        && contains(r.x, r.y + r.height)
        && contains(r.x + r.width, r.y + r.height);
  }

  private boolean boundsContain(final double x, final double y) {
    final Rectangle b = getBounds();
    return x >= b.x && x <= b.x + b.width && y >= b.y && y <= b.y + b.height;
  }
}
