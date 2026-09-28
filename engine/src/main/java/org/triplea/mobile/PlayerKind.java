package org.triplea.mobile;

import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ai.fast.FastAi;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.weak.WeakAi;
import java.util.Arrays;
import java.util.Optional;
import org.triplea.ai.does.nothing.DoesNothingAi;

/** Who controls a nation in a local game. */
public enum PlayerKind {
  HUMAN("Human"),
  AI_EASY("Easy (AI)"),
  AI_FAST("Fast (AI)"),
  AI_HARD("Hard (AI)"),
  /** A nation that buys, moves and attacks nothing; handy to keep a side out of a hot seat game. */
  AI_NONE(PlayerTypes.DOES_NOTHING_PLAYER_LABEL);

  private final String label;

  PlayerKind(final String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }

  /**
   * The kind recorded in a save game for a nation ("Human:Human", "AI:Fast (AI)", ...). The engine
   * writes it when the game starts, so a loaded save remembers who played what. Desktop saves use
   * the same labels; an unknown AI label maps to the hard AI, a nation nobody played to empty.
   */
  public static Optional<PlayerKind> fromWhoAmI(final String whoAmI) {
    if (whoAmI == null || !whoAmI.contains(":")) {
      return Optional.empty();
    }
    final String type = whoAmI.substring(0, whoAmI.indexOf(':'));
    final String label = whoAmI.substring(whoAmI.indexOf(':') + 1);
    if ("Human".equalsIgnoreCase(type)) {
      return Optional.of(HUMAN);
    }
    if (!"AI".equalsIgnoreCase(type)) {
      return Optional.empty();
    }
    return Optional.of(
        Arrays.stream(values())
            .filter(kind -> kind != HUMAN && kind.label.equalsIgnoreCase(label))
            .findFirst()
            .orElse(AI_HARD));
  }

  /** Creates the engine player type for this kind; humans are backed by the given UI bridge. */
  public PlayerTypes.Type toPlayerType(final HumanPlayerUi humanUi) {
    return switch (this) {
      case HUMAN -> humanType(humanUi);
      case AI_EASY -> PlayerTypes.WEAK_AI;
      case AI_FAST -> PlayerTypes.FAST_AI;
      case AI_HARD -> PlayerTypes.PRO_AI;
      case AI_NONE -> DOES_NOTHING_AI;
    };
  }

  /** The engine has the class but no player type for it; the desktop client builds it the same way. */
  public static final PlayerTypes.Type DOES_NOTHING_AI =
      new PlayerTypes.Type(PlayerTypes.DOES_NOTHING_PLAYER_LABEL) {
        @Override
        public Player newPlayerWithName(final String name) {
          return new DoesNothingAi(name, getLabel());
        }
      };

  public static PlayerTypes.Type humanType(final HumanPlayerUi humanUi) {
    return new PlayerTypes.Type(HUMAN.label) {
      @Override
      public Player newPlayerWithName(final String name) {
        return new MobilePlayer(name, getLabel(), humanUi);
      }
    };
  }

  /** Only used to keep the built-in player factories referenced from one place. */
  static Player newAi(final PlayerKind kind, final String name) {
    return switch (kind) {
      case AI_EASY -> new WeakAi(name, kind.label);
      case AI_FAST -> new FastAi(name, kind.label);
      case AI_HARD -> new ProAi(name, kind.label);
      case AI_NONE -> new DoesNothingAi(name, kind.label);
      case HUMAN -> throw new IllegalArgumentException("not an AI kind: " + kind);
    };
  }
}
