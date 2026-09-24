package org.triplea.geom;

import java.io.Serializable;

/** Minimal immutable replacement for {@code java.awt.Color}, usable on Android. */
public final class Color implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final Color WHITE = new Color(255, 255, 255);
  public static final Color LIGHT_GRAY = new Color(192, 192, 192);
  public static final Color GRAY = new Color(128, 128, 128);
  public static final Color DARK_GRAY = new Color(64, 64, 64);
  public static final Color BLACK = new Color(0, 0, 0);
  public static final Color RED = new Color(255, 0, 0);
  public static final Color PINK = new Color(255, 175, 175);
  public static final Color ORANGE = new Color(255, 200, 0);
  public static final Color YELLOW = new Color(255, 255, 0);
  public static final Color GREEN = new Color(0, 255, 0);
  public static final Color MAGENTA = new Color(255, 0, 255);
  public static final Color CYAN = new Color(0, 255, 255);
  public static final Color BLUE = new Color(0, 0, 255);

  public static final Color white = WHITE;
  public static final Color lightGray = LIGHT_GRAY;
  public static final Color gray = GRAY;
  public static final Color darkGray = DARK_GRAY;
  public static final Color black = BLACK;
  public static final Color red = RED;
  public static final Color pink = PINK;
  public static final Color orange = ORANGE;
  public static final Color yellow = YELLOW;
  public static final Color green = GREEN;
  public static final Color magenta = MAGENTA;
  public static final Color cyan = CYAN;
  public static final Color blue = BLUE;

  private final int argb;

  public Color(final int r, final int g, final int b) {
    this(r, g, b, 255);
  }

  public Color(final int r, final int g, final int b, final int a) {
    argb = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
  }

  /** Creates an opaque color from a packed 0xRRGGBB value. */
  public Color(final int rgb) {
    argb = 0xFF000000 | (rgb & 0xFFFFFF);
  }

  public Color(final int rgba, final boolean hasAlpha) {
    argb = hasAlpha ? rgba : (0xFF000000 | (rgba & 0xFFFFFF));
  }

  public int getRed() {
    return (argb >> 16) & 0xFF;
  }

  public int getGreen() {
    return (argb >> 8) & 0xFF;
  }

  public int getBlue() {
    return argb & 0xFF;
  }

  public int getAlpha() {
    return (argb >> 24) & 0xFF;
  }

  /** Packed ARGB value, identical to the AWT getRGB(). */
  public int getRGB() {
    return argb;
  }

  public Color withAlpha(final int alpha) {
    return new Color(getRed(), getGreen(), getBlue(), alpha);
  }

  public Color brighter() {
    return new Color(
        Math.min(255, (int) (getRed() / 0.7)),
        Math.min(255, (int) (getGreen() / 0.7)),
        Math.min(255, (int) (getBlue() / 0.7)),
        getAlpha());
  }

  public Color darker() {
    return new Color(
        (int) (getRed() * 0.7), (int) (getGreen() * 0.7), (int) (getBlue() * 0.7), getAlpha());
  }

  public static Color getHSBColor(final float h, final float s, final float b) {
    return new Color(hsbToRgb(h, s, b));
  }

  public static int hsbToRgb(final float hue, final float saturation, final float brightness) {
    int r = 0;
    int g = 0;
    int b = 0;
    if (saturation == 0) {
      r = g = b = (int) (brightness * 255.0f + 0.5f);
    } else {
      final float h = (hue - (float) Math.floor(hue)) * 6.0f;
      final float f = h - (float) Math.floor(h);
      final float p = brightness * (1.0f - saturation);
      final float q = brightness * (1.0f - saturation * f);
      final float t = brightness * (1.0f - (saturation * (1.0f - f)));
      switch ((int) h) {
        case 0 -> {
          r = (int) (brightness * 255.0f + 0.5f);
          g = (int) (t * 255.0f + 0.5f);
          b = (int) (p * 255.0f + 0.5f);
        }
        case 1 -> {
          r = (int) (q * 255.0f + 0.5f);
          g = (int) (brightness * 255.0f + 0.5f);
          b = (int) (p * 255.0f + 0.5f);
        }
        case 2 -> {
          r = (int) (p * 255.0f + 0.5f);
          g = (int) (brightness * 255.0f + 0.5f);
          b = (int) (t * 255.0f + 0.5f);
        }
        case 3 -> {
          r = (int) (p * 255.0f + 0.5f);
          g = (int) (q * 255.0f + 0.5f);
          b = (int) (brightness * 255.0f + 0.5f);
        }
        case 4 -> {
          r = (int) (t * 255.0f + 0.5f);
          g = (int) (p * 255.0f + 0.5f);
          b = (int) (brightness * 255.0f + 0.5f);
        }
        case 5 -> {
          r = (int) (brightness * 255.0f + 0.5f);
          g = (int) (p * 255.0f + 0.5f);
          b = (int) (q * 255.0f + 0.5f);
        }
        default -> {}
      }
    }
    return 0xff000000 | (r << 16) | (g << 8) | b;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof Color c && c.argb == argb;
  }

  @Override
  public int hashCode() {
    return argb;
  }

  @Override
  public String toString() {
    return String.format("Color[#%06X]", argb & 0xFFFFFF);
  }
}
