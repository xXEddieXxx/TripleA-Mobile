package org.triplea.mobile.forum;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.GameOverException;
import games.strategy.engine.random.IRandomSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Dice from the MARTI dice server (dice.triplea-game.org), which mails every roll to both players.
 * Same request and reply as desktop's {@code MartiDiceRoller}. Never falls back to local dice: a
 * failed roll asks {@code retryOnFailure}, which answers true to retry or false to end the game.
 */
public final class MartiDice implements IRandomSource {
  public static final String ROLL_URL = "https://dice.triplea-game.org/api/roll";

  private final Http http;
  private final String email1;
  private final String email2;
  private final Predicate<String> retryOnFailure;

  public MartiDice(
      final Http http,
      final String email1,
      final String email2,
      final Predicate<String> retryOnFailure) {
    this.http = http;
    this.email1 = email1;
    this.email2 = email2;
    this.retryOnFailure = retryOnFailure;
  }

  @Override
  public int getRandom(final int max, final String annotation) {
    return getRandom(max, 1, annotation)[0];
  }

  @Override
  public int[] getRandom(final int max, final int count, final String annotation) {
    while (true) {
      try {
        return roll(max, count);
      } catch (final Exception e) {
        if (!retryOnFailure.test(e.getMessage())) {
          throw new GameOverException("Dice server failed: " + e.getMessage(), e);
        }
      }
    }
  }

  private int[] roll(final int max, final int count) throws Exception {
    final Http.Response response =
        http.send(
            Http.Request.form(
                ROLL_URL,
                Map.of(
                    "times", String.valueOf(count),
                    "max", String.valueOf(max),
                    "email1", email1,
                    "email2", email2),
                Map.of()));
    if (!response.isOk()) {
      throw new IllegalStateException("HTTP " + response.status());
    }
    final JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
    if (!"OK".equals(json.get("status").getAsString())) {
      throw new IllegalStateException(errors(json));
    }
    final JsonArray dice = json.getAsJsonObject("result").getAsJsonArray("dice");
    if (dice.size() != count) {
      throw new IllegalStateException("Expected " + count + " dice, got " + dice.size());
    }
    final int[] result = new int[count];
    for (int i = 0; i < count; i++) {
      // the server rolls 1..max, the engine counts from 0
      result[i] = dice.get(i).getAsInt() - 1;
    }
    return result;
  }

  private static String errors(final JsonObject json) {
    final JsonArray errors = json.getAsJsonArray("errors");
    if (errors == null || errors.isEmpty()) {
      return "Unknown dice server error";
    }
    final List<String> messages = new ArrayList<>();
    for (final JsonElement error : errors) {
      messages.add(error.getAsString());
    }
    return String.join("; ", messages);
  }
}
