package se.kth.sonar.web.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the bundled web interface from the class path. Hashed assets are cached for a year, {@code
 * index.html} is always revalidated, and page routes of the single-page application fall back to
 * {@code index.html}. Paths with dot segments or backslashes are rejected.
 */
final class StaticFiles {
  private static final String ROOT = "se/kth/sonar/web/ui/";
  private static final String INDEX = "index.html";
  private static final String IMMUTABLE = "public, max-age=31536000, immutable";
  private static final String REVALIDATE = "no-cache";
  private static final Map<String, String> TYPES =
      Map.ofEntries(
          Map.entry("html", "text/html; charset=utf-8"),
          Map.entry("js", "text/javascript; charset=utf-8"),
          Map.entry("mjs", "text/javascript; charset=utf-8"),
          Map.entry("css", "text/css; charset=utf-8"),
          Map.entry("json", "application/json; charset=utf-8"),
          Map.entry("map", "application/json; charset=utf-8"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("png", "image/png"),
          Map.entry("ico", "image/x-icon"),
          Map.entry("woff2", "font/woff2"),
          Map.entry("txt", "text/plain; charset=utf-8"));
  private static final String MISSING =
      """
      <!doctype html><html lang="en"><head><meta charset="utf-8"><title>sonar</title></head>
      <body><p>The web interface is not built. Run <code>scripts/ui.sh build</code>, then restart
      the server. The API is available under <code>/api</code>.</p></body></html>
      """;

  private final ClassLoader loader;
  private final Map<String, Optional<byte[]>> cache = new ConcurrentHashMap<>();

  StaticFiles(ClassLoader loader) {
    this.loader = loader;
  }

  boolean bundled() {
    return resource(INDEX).isPresent();
  }

  void serve(Call call) throws IOException {
    if (!call.method().equals("GET")) {
      throw new HttpError(405, call.method() + " is not allowed on " + call.path());
    }
    String path = call.path();
    if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0) {
      throw HttpError.notFound("no resource at " + path);
    }
    String name = path.equals("/") ? INDEX : path.substring(1);
    Optional<byte[]> body = resource(name);
    if (body.isPresent()) {
      String cache = name.startsWith("assets/") ? IMMUTABLE : REVALIDATE;
      call.send(200, type(name), body.get(), cache);
      return;
    }
    boolean page =
        !name.startsWith("assets/")
            && call.header("Accept").map(accept -> accept.contains("text/html")).orElse(false);
    if (!page && !name.equals(INDEX)) {
      throw HttpError.notFound("no resource at " + path);
    }
    Optional<byte[]> index = resource(INDEX);
    call.send(
        200, TYPES.get("html"), index.orElse(MISSING.getBytes(StandardCharsets.UTF_8)), REVALIDATE);
  }

  private Optional<byte[]> resource(String name) {
    return cache.computeIfAbsent(name, this::load);
  }

  private Optional<byte[]> load(String name) {
    try (InputStream input = loader.getResourceAsStream(ROOT + name)) {
      return input == null ? Optional.empty() : Optional.of(input.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the bundled resource " + name, e);
    }
  }

  private static String type(String name) {
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    return TYPES.getOrDefault(extension, "application/octet-stream");
  }
}
