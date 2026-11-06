package org.triplea.mobile.forum;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The single network seam of the forum and dice code, so tests can answer with recorded JSON. */
@FunctionalInterface
public interface Http {
  int TIMEOUT_MILLIS = 30_000;

  Response send(Request request) throws IOException;

  /** A request; {@code body} may be null for GET. */
  record Request(
      String method, String url, Map<String, String> headers, byte[] body, String contentType) {
    public static Request get(final String url, final Map<String, String> headers) {
      return new Request("GET", url, headers, null, null);
    }

    public static Request form(
        final String url, final Map<String, String> fields, final Map<String, String> headers) {
      final String body =
          fields.entrySet().stream()
              .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
              .collect(Collectors.joining("&"));
      return new Request(
          "POST",
          url,
          headers,
          body.getBytes(StandardCharsets.UTF_8),
          "application/x-www-form-urlencoded");
    }

    public static Request json(
        final String url, final String json, final Map<String, String> headers) {
      return new Request(
          "POST", url, headers, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** One file as multipart form data, as a browser uploads it. */
    public static Request multipart(
        final String url,
        final String field,
        final String fileName,
        final byte[] bytes,
        final String mime,
        final Map<String, String> headers) {
      final String boundary = "----triplea" + java.util.UUID.randomUUID().toString().replace("-", "");
      final String head =
          "--"
              + boundary
              + "\r\nContent-Disposition: form-data; name=\""
              + field
              + "\"; filename=\""
              + fileName.replace("\"", "")
              + "\"\r\nContent-Type: "
              + mime
              + "\r\n\r\n";
      final byte[] start = head.getBytes(StandardCharsets.UTF_8);
      final byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
      // not ByteArrayOutputStream.writeBytes: Android has it from API 33 only, the app runs on 29
      return new Request(
          "POST",
          url,
          headers,
          com.google.common.primitives.Bytes.concat(start, bytes, end),
          "multipart/form-data; boundary=" + boundary);
    }

    private static String encode(final String value) {
      return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
  }

  record Response(int status, byte[] bytes, Map<String, List<String>> headers) {
    public Response(final int status, final String body, final Map<String, List<String>> headers) {
      this(status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    public String body() {
      return new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean isOk() {
      return status >= 200 && status < 300;
    }
  }

  /**
   * The real network, via {@link HttpURLConnection} (works on the JVM and on Android).
   *
   * @param followRedirects false for the forum, so the session cookie never goes elsewhere
   */
  static Http real(final boolean followRedirects) {
    return request -> {
      final HttpURLConnection connection =
          (HttpURLConnection) new URL(request.url()).openConnection();
      connection.setConnectTimeout(TIMEOUT_MILLIS);
      connection.setReadTimeout(TIMEOUT_MILLIS);
      connection.setInstanceFollowRedirects(followRedirects);
      connection.setRequestMethod(request.method());
      connection.setRequestProperty("User-Agent", "triplea-mobile");
      request.headers().forEach(connection::setRequestProperty);
      if (request.body() != null) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", request.contentType());
        try (OutputStream out = connection.getOutputStream()) {
          out.write(request.body());
        }
      }
      try {
        final int status = connection.getResponseCode();
        final InputStream stream =
            status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        // not InputStream.readAllBytes: Android has it from API 33 only, the app runs on 29
        final byte[] bytes =
            stream == null ? new byte[0] : com.google.common.io.ByteStreams.toByteArray(stream);
        return new Response(status, bytes, connection.getHeaderFields());
      } finally {
        connection.disconnect();
      }
    };
  }
}
