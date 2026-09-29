package se.kth.sonar.web.http;

import com.sun.net.httpserver.Headers;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rejects requests that did not come from the user's own browser tab. The server answers only host
 * names on its allow list, which defeats DNS rebinding, and it refuses state-changing requests from
 * foreign origins and cross-site contexts, as well as POST bodies that are not JSON.
 */
final class Guard {
  private final Set<String> hosts;

  Guard(Set<String> hosts) {
    this.hosts = Set.copyOf(hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).toList());
  }

  void check(String method, Headers headers) {
    String host = Optional.ofNullable(headers.getFirst("Host")).map(Guard::hostName).orElse("");
    if (!hosts.contains(host)) {
      throw new HttpError(403, "the host '" + host + "' is not allowed");
    }
    if (method.equals("GET") || method.equals("HEAD")) {
      return;
    }
    String origin = headers.getFirst("Origin");
    if (origin != null && !origin.equals("null") && !hosts.contains(originHost(origin))) {
      throw new HttpError(403, "requests from origin " + origin + " are not allowed");
    }
    if ("cross-site".equalsIgnoreCase(headers.getFirst("Sec-Fetch-Site"))) {
      throw new HttpError(403, "cross-site requests are not allowed");
    }
    if (method.equals("POST")) {
      String type =
          Optional.ofNullable(headers.getFirst("Content-Type")).orElse("").toLowerCase(Locale.ROOT);
      if (!type.startsWith("application/json")) {
        throw new HttpError(415, "POST requests must send application/json");
      }
    }
  }

  private static String hostName(String header) {
    String value = header.strip().toLowerCase(Locale.ROOT);
    if (value.startsWith("[")) {
      int end = value.indexOf(']');
      return end < 0 ? value : value.substring(0, end + 1);
    }
    int colon = value.indexOf(':');
    return colon < 0 ? value : value.substring(0, colon);
  }

  private static String originHost(String origin) {
    try {
      String host = URI.create(origin).getHost();
      return host == null ? "" : host.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }
}
