package se.kth.sonar.web.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Routes requests to handlers by method and path template, such as {@code /api/runs/{id}}. */
public final class Router {
  private static final Pattern SLASH = Pattern.compile("/");

  private final List<Route> routes = new ArrayList<>();

  /** Handles one call. */
  @FunctionalInterface
  public interface Handler {
    /** Answers {@code call}. */
    void handle(Call call) throws IOException, InterruptedException;
  }

  private record Route(String method, List<String> segments, Handler handler) {}

  /**
   * A handler with the path parameters of its route.
   *
   * @param handler the handler
   * @param parameters the values of the template's parameters
   */
  record Match(Handler handler, Map<String, String> parameters) {}

  /** Adds a GET route. */
  public Router get(String template, Handler handler) {
    return add("GET", template, handler);
  }

  /** Adds a POST route. */
  public Router post(String template, Handler handler) {
    return add("POST", template, handler);
  }

  /** Adds a DELETE route. */
  public Router delete(String template, Handler handler) {
    return add("DELETE", template, handler);
  }

  private Router add(String method, String template, Handler handler) {
    routes.add(new Route(method, segments(template), handler));
    return this;
  }

  /**
   * Finds the route for {@code method} and {@code path}.
   *
   * @throws HttpError 404 if no route has the path, or 405 if none allows the method
   */
  Match find(String method, String path) {
    List<String> given = segments(path);
    boolean pathMatched = false;
    for (Route route : routes) {
      Optional<Map<String, String>> parameters = bind(route.segments(), given);
      if (parameters.isPresent()) {
        pathMatched = true;
        if (route.method().equals(method)) {
          return new Match(route.handler(), parameters.get());
        }
      }
    }
    if (pathMatched) {
      throw new HttpError(405, method + " is not allowed on " + path);
    }
    throw HttpError.notFound("no resource at " + path);
  }

  private static Optional<Map<String, String>> bind(List<String> template, List<String> path) {
    if (template.size() != path.size()) {
      return Optional.empty();
    }
    Map<String, String> parameters = new HashMap<>();
    for (int index = 0; index < template.size(); index++) {
      String expected = template.get(index);
      String actual = path.get(index);
      if (expected.startsWith("{") && expected.endsWith("}")) {
        parameters.put(expected.substring(1, expected.length() - 1), actual);
      } else if (!expected.equals(actual)) {
        return Optional.empty();
      }
    }
    return Optional.of(parameters);
  }

  private static List<String> segments(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : SLASH.split(path, -1)) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    return segments;
  }
}
