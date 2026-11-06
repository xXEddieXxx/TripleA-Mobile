package org.triplea.mobile.forum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.GameOverException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MartiDiceTest {
  private final List<Http.Request> requests = new ArrayList<>();
  private final Deque<Http.Response> responses = new ArrayDeque<>();
  private final Http http =
      request -> {
        requests.add(request);
        return responses.removeFirst();
      };
  private final List<String> failures = new ArrayList<>();

  @Test
  void rollsComeFromServerMinusOne() {
    responses.add(ok("{\"status\":\"OK\",\"result\":{\"dice\":[1,6,3]}}"));

    final int[] dice = dice(false).getRandom(6, 3, "x");

    assertThat(dice).containsExactly(0, 5, 2);
    final Http.Request request = requests.get(0);
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.url()).isEqualTo("https://dice.triplea-game.org/api/roll");
    assertThat(new String(request.body(), StandardCharsets.UTF_8))
        .contains("times=3", "max=6", "email1=a%40x.org", "email2=b%40y.org");
  }

  @Test
  void failedRollAsksAndRetries() {
    responses.add(new Http.Response(500, "oops", Map.of()));
    responses.add(ok("{\"status\":\"OK\",\"result\":{\"dice\":[2]}}"));

    assertThat(dice(true).getRandom(6, "x")).isEqualTo(1);
    assertThat(failures).hasSize(1);
  }

  @Test
  void abortEndsGame() {
    responses.add(ok("{\"status\":\"ERROR\",\"errors\":[\"email not registered\"]}"));

    final MartiDice dice = dice(false);

    assertThatThrownBy(() -> dice.getRandom(6, 1, "x")).isInstanceOf(GameOverException.class);
    assertThat(failures).singleElement().asString().contains("email not registered");
  }

  private MartiDice dice(final boolean retry) {
    return new MartiDice(
        http,
        "a@x.org",
        "b@y.org",
        message -> {
          failures.add(message);
          return retry;
        });
  }

  private static Http.Response ok(final String body) {
    return new Http.Response(200, body, Map.of());
  }
}
