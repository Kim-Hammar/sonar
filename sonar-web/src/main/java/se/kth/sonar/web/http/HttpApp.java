package se.kth.sonar.web.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Set;

/**
 * The single request handler of the server: it applies the security checks and the common response
 * headers, sends API paths to the router and every other path to the bundled interface, and turns
 * failures into JSON errors.
 */
public final class HttpApp implements HttpHandler {
  private static final System.Logger LOG = System.getLogger(HttpApp.class.getName());
  private static final String POLICY =
      "default-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

  private final Router router;
  private final Guard guard;
  private final StaticFiles files;

  /** Creates the handler for {@code router}, answering only the host names {@code hosts}. */
  public HttpApp(Router router, Set<String> hosts) {
    this.router = router;
    this.guard = new Guard(hosts);
    this.files = new StaticFiles(HttpApp.class.getClassLoader());
  }

  /** Returns whether the web interface is bundled. */
  public boolean bundled() {
    return files.bundled();
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.getResponseHeaders().set("Content-Security-Policy", POLICY);
    Call call = new Call(exchange, Map.of());
    try {
      guard.check(exchange.getRequestMethod(), exchange.getRequestHeaders());
      String path = exchange.getRequestURI().getPath();
      if (path.equals("/api") || path.startsWith("/api/")) {
        Router.Match match = router.find(exchange.getRequestMethod(), path);
        call = new Call(exchange, match.parameters());
        match.handler().handle(call);
      } else {
        files.serve(call);
      }
    } catch (HttpError error) {
      fail(call, error.status(), error.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      LOG.log(Level.DEBUG, "the client went away: " + e.getMessage());
    } catch (RuntimeException e) {
      LOG.log(Level.ERROR, "request " + exchange.getRequestURI() + " failed", e);
      fail(call, 500, "internal error: " + e.getMessage());
    } finally {
      exchange.close();
    }
  }

  private static void fail(Call call, int status, String message) throws IOException {
    if (!call.responded()) {
      call.json(status, Map.of("error", message == null ? "error" : message));
    }
  }
}
