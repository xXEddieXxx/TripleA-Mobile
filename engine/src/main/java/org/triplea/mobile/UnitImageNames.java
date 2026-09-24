package org.triplea.mobile;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.util.UnitCategory;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Computes the image file base names for units, mirroring the desktop {@code UnitImageFactory}
 * (technology suffixes such as {@code _lr}, {@code _hit}, {@code _disabled}).
 */
@UtilityClass
public class UnitImageNames {
  public static final String UNITS_FOLDER = "units";

  public static String baseImageName(final UnitCategory category) {
    return baseImageName(
        category.getType(),
        category.getOwner(),
        category.hasDamageOrBombingUnitDamage(),
        category.getDisabled());
  }

  public static String baseImageName(final Unit unit) {
    return baseImageName(
        unit.getType(),
        unit.getOwner(),
        Matches.unitHasTakenSomeBombingUnitDamage().test(unit),
        Matches.unitIsDisabled().test(unit));
  }

  /**
   * Candidate resource paths for a unit image in priority order: the owner specific image first,
   * then the generic one.
   */
  public static List<String> candidatePaths(
      final UnitType type, final GamePlayer owner, final boolean damaged, final boolean disabled) {
    final String base = baseImageName(type, owner, damaged, disabled);
    final List<String> paths = new ArrayList<>();
    paths.add(UNITS_FOLDER + "/" + owner.getName() + "/" + base + ".png");
    paths.add(UNITS_FOLDER + "/" + base + ".png");
    if (damaged || disabled) {
      final String plain = baseImageName(type, owner, false, false);
      paths.add(UNITS_FOLDER + "/" + owner.getName() + "/" + plain + ".png");
      paths.add(UNITS_FOLDER + "/" + plain + ".png");
    }
    paths.add(UNITS_FOLDER + "/" + owner.getName() + "/" + type.getName() + ".png");
    paths.add(UNITS_FOLDER + "/" + type.getName() + ".png");
    return paths;
  }

  public static String baseImageName(
      final UnitType type, final GamePlayer gamePlayer, final boolean damaged, final boolean disabled) {
    StringBuilder name = new StringBuilder(32);
    name.append(type.getName());
    if (!type.getName().endsWith("_hit") && !type.getName().endsWith("_disabled")) {
      final UnitAttachment ua = type.getUnitAttachment();
      if (type.getName().equals(Constants.UNIT_TYPE_AAGUN)) {
        if (TechTracker.hasRocket(gamePlayer) && ua.isRocket()) {
          name = new StringBuilder("rockets");
        }
        if (TechTracker.hasAaRadar(gamePlayer) && Matches.unitTypeIsAaForAnything().test(type)) {
          name.append("_r");
        }
      } else if (ua.isRocket() && Matches.unitTypeIsAaForAnything().test(type)) {
        if (TechTracker.hasRocket(gamePlayer)) {
          name.append("_rockets");
        }
        if (TechTracker.hasAaRadar(gamePlayer)) {
          name.append("_r");
        }
      } else if (ua.isRocket()) {
        if (TechTracker.hasRocket(gamePlayer)) {
          name.append("_rockets");
        }
      } else if (Matches.unitTypeIsAaForAnything().test(type)) {
        if (TechTracker.hasAaRadar(gamePlayer)) {
          name.append("_r");
        }
      }
      if (ua.isAir() && !ua.isStrategicBomber()) {
        if (TechTracker.hasLongRangeAir(gamePlayer)) {
          name.append("_lr");
        }
        if (TechTracker.hasJetFighter(gamePlayer)
            && (ua.getAttack(gamePlayer) > 0 || ua.getDefense(gamePlayer) > 0)) {
          name.append("_jp");
        }
      }
      if (ua.isAir() && ua.isStrategicBomber()) {
        if (TechTracker.hasLongRangeAir(gamePlayer)) {
          name.append("_lr");
        }
        if (TechTracker.hasHeavyBomber(gamePlayer)) {
          name.append("_hb");
        }
      }
      if (ua.getIsFirstStrike()
          && ua.getCanEvade()
          && (ua.getAttack(gamePlayer) > 0 || ua.getDefense(gamePlayer) > 0)
          && TechTracker.hasSuperSubs(gamePlayer)) {
        name.append("_ss");
      }
      if ((type.getName().equals(Constants.UNIT_TYPE_FACTORY) || ua.canProduceUnits())
          && (TechTracker.hasIndustrialTechnology(gamePlayer)
              || TechTracker.hasIncreasedFactoryProduction(gamePlayer))) {
        name.append("_it");
      }
    }
    if (disabled) {
      name.append("_disabled");
    } else if (damaged) {
      name.append("_hit");
    }
    return name.toString();
  }
}
