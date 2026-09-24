package org.triplea.java;

import java.util.Random;
import lombok.experimental.UtilityClass;
import org.triplea.geom.Color;

/** Utility methods for parsing and generating colors. */
@UtilityClass
public class ColorUtils {
  /**
   * Returns a color parsed from the provided input hex string.
   *
   * @param colorString EG: 00FF00, FF00FF, 000000
   */
  public Color fromHexString(final String colorString) {
    if (colorString.length() != 6) {
      throw new IllegalArgumentException(
          "Colors must be 6 digit hex numbers, eg FF0011, not: " + colorString);
    }
    try {
      return new Color(Integer.decode("0x" + colorString));
    } catch (final NumberFormatException nfe) {
      throw new IllegalArgumentException(
          "Colors must be 6 digit hex numbers, eg FF0011, not: "
              + colorString
              + ", "
              + nfe.getMessage(),
          nfe);
    }
  }

  public Color randomColor(final long randomSeed) {
    final Random random = new Random(randomSeed);
    return Color.getHSBColor(random.nextFloat(), random.nextFloat(), random.nextFloat());
  }
}
