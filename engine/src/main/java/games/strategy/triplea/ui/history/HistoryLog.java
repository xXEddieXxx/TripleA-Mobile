package games.strategy.triplea.ui.history;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.history.HistoryNode;
import games.strategy.engine.history.Renderable;
import games.strategy.engine.history.Round;
import games.strategy.engine.history.Step;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;
import org.triplea.tree.DefaultMutableTreeNode;

/**
 * A textual summary of a turn, as posted to the forum.
 *
 * <p>mobile: port of the desktop class without the Swing dialog; only the turn summary used for
 * forum posts is kept (territory, dice and production summaries are left out).
 */
@Slf4j
public class HistoryLog {
  static final String MORE_INDENT = "    ";

  private final StringBuilder stringBuilder = new StringBuilder();
  private boolean verboseLog = false;
  private StringBuilder conquerStr = new StringBuilder();

  @Override
  public String toString() {
    return stringBuilder.toString();
  }

  /**
   * Adds details about the current turn for each player in {@code playersAllowed} to the log.
   * Information about each step and event that occurred during the turn are included.
   */
  public void printFullTurn(
      final GameData data, final boolean verbose, final Collection<GamePlayer> playersAllowed) {
    HistoryNode curNode = data.getHistory().getLastNode();
    // find Step node, if exists in this path
    while (curNode != null) {
      if (curNode instanceof Step stepNode) {
        final Collection<GamePlayer> players = new HashSet<>();
        if (playersAllowed != null) {
          players.addAll(playersAllowed);
        }
        final Optional<GamePlayer> optionalCurrentPlayer = stepNode.getPlayerId();
        if (players.isEmpty() && optionalCurrentPlayer.isPresent()) {
          players.add(optionalCurrentPlayer.get());
        }
        final Step turnStartNode = getFirstStepForTurn(stepNode, players);
        printRemainingTurn(turnStartNode, verbose, data.getDiceSides(), players);
        return;
      }
      curNode = (HistoryNode) curNode.getPreviousNode();
    }
    log.error("No step node found in!");
  }

  private static Step getFirstStepForTurn(
      final Step initialStepNode, final Collection<GamePlayer> players) {
    Step stepNode = initialStepNode;
    while (true) {
      final Step turnStartNode = stepNode;
      stepNode = (Step) stepNode.getPreviousSibling();
      if (stepNode == null) {
        return turnStartNode;
      }
      final Optional<GamePlayer> optionalStepNodePlayer = stepNode.getPlayerId();
      if (optionalStepNodePlayer.isEmpty() || !players.contains(optionalStepNodePlayer.get())) {
        return turnStartNode;
      }
    }
  }

  /**
   * Adds details about {@code printNode} and all its sibling and child nodes that are part of the
   * current turn for each player in {@code playersAllowed} to the log.
   */
  public void printRemainingTurn(
      final HistoryNode printNode,
      final boolean verbose,
      final int diceSides,
      final Collection<GamePlayer> playersAllowed) {
    this.verboseLog = verbose;
    // print out the parent nodes
    // mobile: getPath() returns a list; the parent path is all but the last element
    final List<DefaultMutableTreeNode> path = printNode.getPath();
    Optional<GamePlayer> optionalCurrentPlayer = Optional.empty();
    for (final DefaultMutableTreeNode pathNode : path.subList(0, path.size() - 1)) {
      final HistoryNode node = (HistoryNode) pathNode;
      stringBuilder.append(MORE_INDENT.repeat(Math.max(0, node.getLevel())));
      stringBuilder.append(node.getTitle()).append('\n');
      if (node.getLevel() == 0) {
        stringBuilder.append('\n');
      }
      if (node instanceof Step nodeStep) {
        optionalCurrentPlayer = nodeStep.getPlayerId();
      }
    }
    final Collection<GamePlayer> players = new HashSet<>();
    if (playersAllowed != null) {
      players.addAll(playersAllowed);
    }
    optionalCurrentPlayer.ifPresent(players::add);
    fillStringBuilderForRemainingTurn(printNode, diceSides, players);
  }

  private void fillStringBuilderForRemainingTurn(
      final HistoryNode printNode, final int diceSides, final Collection<GamePlayer> players) {
    final List<String> moveList = new ArrayList<>();
    final Map<String, Double> hitDifferentialMap = new HashMap<>();
    final boolean moving =
        fillStringBuilderForEachTurnNode(
            diceSides, players, printNode, moveList, hitDifferentialMap);
    // if we are mid-phase, this might not get flushed
    if (moving && !moveList.isEmpty()) {
      final Iterator<String> moveIter = moveList.iterator();
      while (moveIter.hasNext()) {
        stringBuilder.append(moveIter.next()).append('\n');
        moveIter.remove();
      }
    }
    stringBuilder.append('\n');
    if (verboseLog) {
      stringBuilder.append("Combat Hit Differential Summary :\n\n");
      for (final var playerHitEntry : hitDifferentialMap.entrySet()) {
        stringBuilder
            .append(MORE_INDENT)
            .append(playerHitEntry.getKey())
            .append(" : ")
            .append(String.format("%.2f", playerHitEntry.getValue()))
            .append('\n');
      }
    }
    stringBuilder.append('\n');
  }

  private boolean fillStringBuilderForEachTurnNode(
      final int diceSides,
      final Collection<GamePlayer> players,
      DefaultMutableTreeNode turnNode,
      final List<String> moveList,
      final Map<String, Double> hitDifferentialMap) {
    boolean moving = false;
    do {
      // keep track of conquered territory during combat
      final Enumeration<?> nodeEnum = turnNode.preorderEnumeration();
      while (nodeEnum.hasMoreElements()) {
        final HistoryNode node = (HistoryNode) nodeEnum.nextElement();
        final String title = node.getTitle();
        final String indent = MORE_INDENT.repeat(Math.max(0, node.getLevel()));
        moving = flushMoveList(moveList, moving, node);
        if (node instanceof Renderable renderableNode) {
          moving =
              fillSbWithNodeRenderable(
                  diceSides, renderableNode, title, indent, hitDifferentialMap, moveList, moving);
        } else if (node instanceof Step nodeStep) {
          fillSbWithNodeStep(players, nodeStep, title, indent);
        } else if (node instanceof Round) {
          stringBuilder.append('\n').append(indent).append(title).append('\n');
        } else {
          stringBuilder.append(indent).append(title).append('\n');
        }
      }
      turnNode = turnNode.getNextSibling();
    } while (turnNode instanceof Step nodeStep
        && players.contains(nodeStep.getPlayerId().orElse(null)));
    return moving;
  }

  private boolean flushMoveList(
      final List<String> moveList, final boolean moving, final HistoryNode node) {
    if (moving && !(node instanceof Renderable)) {
      final Iterator<String> moveIter = moveList.iterator();
      while (moveIter.hasNext()) {
        stringBuilder.append(moveIter.next()).append('\n');
        moveIter.remove();
      }
      return false;
    }
    return moving;
  }

  private void fillSbWithNodeStep(
      final Collection<GamePlayer> players,
      final Step nodeStep,
      final String title,
      final String indent) {
    if (!title.equals("Initializing Delegates")) {
      stringBuilder.append('\n').append(indent).append(title);
      final Optional<GamePlayer> optionalGamePlayer = nodeStep.getPlayerId();
      optionalGamePlayer.ifPresent(
          gamePlayer -> {
            players.add(gamePlayer);
            stringBuilder.append(" - ").append(gamePlayer.getName());
          });
      stringBuilder.append('\n');
    }
  }

  private boolean fillSbWithNodeRenderable(
      final int diceSides,
      final Renderable renderableNode,
      final String title,
      final String indent,
      final Map<String, Double> hitDifferentialMap,
      final List<String> moveList,
      final boolean moving) {
    final Object details = renderableNode.getRenderingData();
    if (details instanceof DiceRoll diceRoll && verboseLog) {
      fillSbWithDetailsDiceRoll(diceSides, diceRoll, title, indent, hitDifferentialMap);
    } else if (details instanceof MoveDescription) {
      // movement
      final Matcher m = UNDO_MOVE.matcher(title);
      if (m.matches()) {
        moveList.remove(Integer.parseInt(m.group(1)) - 1);
      } else {
        moveList.add(indent + title);
        return true;
      }
    } else if (details instanceof Collection) {
      fillSbWithDetailsCollection(details, title, indent);
    } else if (details instanceof Territory) {
      // territory details
      stringBuilder.append(indent).append(title).append('\n');
    } else if (details == null) {
      fillSbWithDetailsNull(title, indent, moving, moveList, conquerStr);
    } else {
      // unknown details object
      stringBuilder.append(indent).append(title).append('\n');
    }
    return moving;
  }

  private static final Pattern UNDO_MOVE = Pattern.compile("\\w+ undo move (\\d+).");

  private void fillSbWithDetailsCollection(
      final Object details, final String title, final String indent) {
    @SuppressWarnings("unchecked")
    final Collection<Object> objects = (Collection<Object>) details;
    final Iterator<Object> objIter = objects.iterator();
    if (objIter.hasNext()) {
      final Object obj = objIter.next();
      if (obj instanceof Unit unit) {
        @SuppressWarnings("unchecked")
        final Collection<Unit> allUnitsInDetails = (Collection<Unit>) details;
        fillSbWithDetailsCollectionUnits(title, indent, unit, objIter, allUnitsInDetails);
      } else {
        // collection of unhandled objects
        stringBuilder.append(indent).append(title).append('\n');
      }
    } else {
      // empty collection of something
      if (title.matches("\\w+ win")) {
        conquerStr = new StringBuilder(title + conquerStr + " with no units remaining");
      } else {
        // empty collection of unhandled objects
        stringBuilder.append(indent).append(title).append('\n');
      }
    }
  }

  private void fillSbWithDetailsCollectionUnits(
      final String title,
      final String indent,
      final Unit unit,
      final Iterator<Object> objIter,
      final Collection<Unit> allUnitsInDetails) {
    // purchase/place units don't need details
    if (title.matches("\\w+ buy .*")
        || title.matches("\\w+ attack with .*")
        || title.matches("\\w+ defend with .*")) {
      stringBuilder.append(indent).append(title).append('\n');
    } else if (title.matches("\\d+ \\w+ owned by the .*? lost .*")
        || title.matches("\\d+ \\w+ owned by the .*? lost")) {
      if (verboseLog) {
        stringBuilder.append(indent).append(MORE_INDENT).append(title).append('\n');
      }
    } else if (title.startsWith("Battle casualty summary:")) {
      fillSbWithBattleSummary(title, indent, unit, objIter, allUnitsInDetails);
    } else if (title.matches(".*? placed in .*")
        || title.matches(".* owned by the \\w+ retreated to .*")) {
      stringBuilder.append(indent).append(title).append('\n');
    } else if (title.matches("\\w+ win")) {
      conquerStr =
          new StringBuilder(
              title
                  + conquerStr
                  + " with "
                  + MyFormatter.unitsToTextNoOwner(allUnitsInDetails)
                  + " remaining");
    } else {
      stringBuilder.append(indent).append(title).append('\n');
    }
  }

  private void fillSbWithBattleSummary(
      final String title,
      final String indent,
      Unit unit,
      final Iterator<Object> objIter,
      final Collection<Unit> allUnitsInDetails) {
    stringBuilder
        .append(indent)
        .append(conquerStr)
        .append(". Battle score ")
        .append(title.substring(title.indexOf("for attacker is")))
        .append('\n');
    conquerStr = new StringBuilder();
    // separate units by player and show casualty summary
    final IntegerMap<GamePlayer> unitCount = new IntegerMap<>();
    unitCount.add(unit.getOwner(), 1);
    while (objIter.hasNext()) {
      unit = (Unit) objIter.next();
      unitCount.add(unit.getOwner(), 1);
    }
    for (final GamePlayer player : unitCount.keySet()) {
      stringBuilder
          .append(indent)
          .append("Casualties for ")
          .append(player.getName())
          .append(": ")
          .append(MyFormatter.unitsToTextNoOwner(allUnitsInDetails, player))
          .append('\n');
    }
  }

  private void fillSbWithDetailsNull(
      final String title,
      final String indent,
      final boolean moving,
      final List<String> moveList,
      final StringBuilder conquered) {
    if (titleNeedsFurtherProcessing(title)) {
      if (title.matches("\\w+ collect \\d+ PUs?.*")) {
        stringBuilder.append(indent).append(title).append('\n');
      } else if (title.matches("\\w+ takes? .*? from \\w+")) {
        // British take Libya from Germans
        if (moving) {
          final String str = moveList.remove(moveList.size() - 1);
          moveList.add(str + "\n  " + indent + title.replace(" takes ", " take "));
        } else {
          conquered.append(title.replaceAll("^\\w+ takes ", ", taking "));
        }
      } else if (!title.startsWith("Rolls to resolve tech hits:")) {
        stringBuilder.append(indent).append(title).append('\n');
      }
    }
  }

  private void fillSbWithDetailsDiceRoll(
      final int diceSides,
      final DiceRoll diceRoll,
      final String title,
      final String indent,
      final Map<String, Double> hitDifferentialMap) {
    final String diceMsg1 = title.substring(0, title.indexOf(':') + 1);
    if (diceMsg1.isEmpty()) {
      // tech roll
      stringBuilder.append(indent).append(MORE_INDENT).append(title).append('\n');
    } else {
      // dice roll
      stringBuilder.append(indent).append(MORE_INDENT).append(diceMsg1);
      final String hitDifferentialKey = parseHitDifferentialKeyFromDiceRollMessage(diceMsg1);
      final int hits = diceRoll.getHits();
      int rolls = 0;
      for (int i = 1; i <= diceSides; i++) {
        rolls += diceRoll.getRolls(i).size();
      }
      final double expectedHits = diceRoll.getExpectedHits();
      stringBuilder
          .append(" ")
          .append(hits)
          .append("/")
          .append(rolls)
          .append(" hits, ")
          .append(String.format("%.2f", expectedHits))
          .append(" expected hits")
          .append('\n');
      hitDifferentialMap.merge(hitDifferentialKey, hits - expectedHits, Double::sum);
    }
  }

  private static boolean titleNeedsFurtherProcessing(final String title) {
    return !(title.equals("Adding original owners")
        || title.equals(MoveDelegate.CLEANING_UP_DURING_MOVEMENT_PHASE)
        || title.equals("Game Loaded")
        || title.contains("now being played by")
        || title.contains("Turn Summary")
        || title.contains("Move Summary")
        || title.contains("Setting uses for triggers used")
        || title.equals("Resetting and Giving Bonus Movement to Units")
        || title.equals("Recording Battle Statistics")
        || title.equals("Preparing Airbases for Possible Scrambling"));
  }

  static String parseHitDifferentialKeyFromDiceRollMessage(final String message) {
    final Matcher matcher = DICE_ROLL.matcher(message);
    if (matcher.find()) {
      return matcher.group(1) + " " + Optional.ofNullable(matcher.group(2)).orElse("regular");
    }
    final int lastColonIndex = message.lastIndexOf(" :");
    return (lastColonIndex != -1) ? message.substring(0, lastColonIndex) : message;
  }

  private static final Pattern DICE_ROLL = Pattern.compile("^(.+) roll(?: (.+))? dice");
}
