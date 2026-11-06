package org.triplea.mobile.forum;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A forum answering from fixed bodies by URL, recording what it was asked; 404 otherwise. */
final class FakeForum implements Http {
  static final String URL = "https://forums.triplea-game.org/";

  final List<Request> requests = new ArrayList<>();
  private final Map<String, Response> answers = new HashMap<>();

  FakeForum answer(final String path, final String body) {
    return answer(path, 200, body);
  }

  FakeForum answer(final String path, final int status, final String body) {
    answers.put(URL + path, new Response(status, body, Map.of()));
    return this;
  }

  @Override
  public Response send(final Request request) {
    requests.add(request);
    return answers.getOrDefault(request.url(), new Response(404, "", Map.of()));
  }

  static String text(final Request request) {
    return new String(request.body(), StandardCharsets.UTF_8);
  }
}
