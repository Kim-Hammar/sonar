package se.kth.sonar.web.http;

import com.fasterxml.jackson.core.JacksonException;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import se.kth.sonar.app.Json;

/** One HTTP exchange as a route handler sees it: the request and the ways to answer it. */
public final class Call {
  private static final int MAX_BODY = 1 << 20;
  private static final int COMPRESS_FROM = 1 << 10;
  private static final Pattern AMPERSAND = Pattern.compile("&");
  private static final String JSON = "application/json; charset=utf-8";

  private final HttpExchange exchange;
  private final Map<String, String> parameters;
  private final Map<String, String> query;
  private boolean responded;

  Call(HttpExchange exchange, Map<String, String> parameters) {
    this.exchange = exchange;
    this.parameters = Map.copyOf(parameters);
    this.query = parseQuery(exchange.getRequestURI().getRawQuery());
  }

  /** Returns the request method. */
  public String method() {
    return exchange.getRequestMethod();
  }

  /** Returns the decoded request path. */
  public String path() {
    return exchange.getRequestURI().getPath();
  }

  /** Returns the path parameter {@code name} of the matched route. */
  public String parameter(String name) {
    String value = parameters.get(name);
    if (value == null) {
      throw new IllegalArgumentException("the route has no parameter " + name);
    }
    return value;
  }

  /** Returns the query parameter {@code name}, if given. */
  public Optional<String> query(String name) {
    return Optional.ofNullable(query.get(name));
  }

  /** Returns the request header {@code name}, if given. */
  public Optional<String> header(String name) {
    return Optional.ofNullable(exchange.getRequestHeaders().getFirst(name));
  }

  /**
   * Reads the JSON request body as {@code type}.
   *
   * @throws HttpError 413 if the body exceeds 1 MiB, or 400 if it is not valid JSON of that type
   */
  public <T> T body(Class<T> type) throws IOException {
    byte[] bytes;
    try (InputStream input = exchange.getRequestBody()) {
      bytes = input.readNBytes(MAX_BODY + 1);
    }
    if (bytes.length > MAX_BODY) {
      throw new HttpError(413, "the request body exceeds 1 MiB");
    }
    try {
      return Json.mapper().readValue(bytes, type);
    } catch (JacksonException e) {
      throw HttpError.badRequest("the request body is not valid JSON: " + e.getOriginalMessage());
    }
  }

  /** Answers with {@code body} written as JSON. */
  public void json(int status, Object body) throws IOException {
    send(status, JSON, Json.mapper().writeValueAsBytes(body), "no-store");
  }

  /** Answers with {@code json}, which is already JSON text. */
  public void rawJson(int status, String json) throws IOException {
    send(status, JSON, json.getBytes(StandardCharsets.UTF_8), "no-store");
  }

  /** Answers with {@code body} as {@code contentType} and the given cache policy. */
  public void send(int status, String contentType, byte[] body, String cacheControl)
      throws IOException {
    byte[] payload = body;
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", cacheControl);
    exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
    if (payload.length >= COMPRESS_FROM && compressible(contentType) && acceptsGzip()) {
      ByteArrayOutputStream compressed = new ByteArrayOutputStream(payload.length / 3);
      try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
        gzip.write(payload);
      }
      payload = compressed.toByteArray();
      exchange.getResponseHeaders().set("Content-Encoding", "gzip");
    }
    responded = true;
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(payload);
    }
  }

  /** Answers with no content. */
  public void noContent() throws IOException {
    responded = true;
    exchange.sendResponseHeaders(204, -1);
  }

  /** Starts a streamed answer of {@code contentType} and returns the stream to write it to. */
  public OutputStream stream(String contentType) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
    responded = true;
    exchange.sendResponseHeaders(200, 0);
    return exchange.getResponseBody();
  }

  /** Returns whether an answer has been started. */
  boolean responded() {
    return responded;
  }

  private boolean acceptsGzip() {
    return header("Accept-Encoding")
        .map(value -> value.toLowerCase(Locale.ROOT).contains("gzip"))
        .orElse(false);
  }

  private static boolean compressible(String contentType) {
    return contentType.startsWith("application/json")
        || contentType.startsWith("text/")
        || contentType.startsWith("image/svg");
  }

  private static Map<String, String> parseQuery(String raw) {
    Map<String, String> values = new HashMap<>();
    if (raw == null || raw.isEmpty()) {
      return values;
    }
    for (String pair : AMPERSAND.split(raw, -1)) {
      int equals = pair.indexOf('=');
      String name =
          URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
      String value =
          equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
      values.putIfAbsent(name, value);
    }
    return values;
  }
}
