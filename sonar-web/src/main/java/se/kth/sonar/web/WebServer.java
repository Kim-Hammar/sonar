package se.kth.sonar.web;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import se.kth.sonar.app.RunStore;
import se.kth.sonar.app.Workspace;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.web.api.InfoRoutes;
import se.kth.sonar.web.api.RunRoutes;
import se.kth.sonar.web.api.ScenarioRoutes;
import se.kth.sonar.web.http.HttpApp;
import se.kth.sonar.web.http.Router;
import se.kth.sonar.web.runs.PipelineLauncher;
import se.kth.sonar.web.runs.RunLauncher;
import se.kth.sonar.web.runs.RunManager;

/**
 * The web interface: the JDK's HTTP server with one virtual thread per exchange, serving the
 * bundled single-page application and the JSON and streaming API that runs the agent.
 */
public final class WebServer implements AutoCloseable {
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  private final HttpServer server;
  private final ExecutorService executor;
  private final RunManager runs;
  private final HttpApp app;
  private volatile boolean stopping;

  /**
   * How to serve.
   *
   * @param host the address to bind, {@code 127.0.0.1} by default
   * @param port the port, or 0 for any free port
   * @param workspace the dataset and the output directory
   * @param decisions where the engine asks the decision model
   * @param demo whether runs use the scripted repairer instead of the engine
   * @param demoPace the pause between the scripted repairer's steps
   * @param allowedHosts further host names the server answers, besides the local ones
   */
  public record Config(
      String host,
      int port,
      Workspace workspace,
      HttpTransport.Settings decisions,
      boolean demo,
      Duration demoPace,
      List<String> allowedHosts) {
    /** Checks the components. */
    public Config {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(workspace, "workspace");
      Objects.requireNonNull(decisions, "decisions");
      Objects.requireNonNull(demoPace, "demoPace");
      allowedHosts = List.copyOf(allowedHosts);
      if (port < 0 || port > 65_535) {
        throw new IllegalArgumentException("invalid port " + port);
      }
    }
  }

  private WebServer(Config config, RunLauncher launcher) throws IOException {
    ScenarioRepository scenarios = new ScenarioRepository(config.workspace().dataset());
    this.runs =
        new RunManager(
            new RunStore(config.workspace().runs(), Clock.systemUTC()),
            launcher,
            Clock.systemUTC());
    Router router = new Router();
    InfoRoutes.register(router, config);
    ScenarioRoutes.register(router, scenarios, Dialect.load("cisco-ios").grammar());
    RunRoutes.register(
        router, runs, scenarios, config.demo(), config.decisions().model(), () -> stopping);
    Set<String> hosts = new HashSet<>(LOCAL_HOSTS);
    config.allowedHosts().forEach(host -> hosts.add(host.toLowerCase(Locale.ROOT)));
    this.app = new HttpApp(router, hosts);
    this.executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sonar-http-", 0).factory());
    this.server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
    server.createContext("/", app);
    server.setExecutor(executor);
    server.start();
  }

  /** Starts a server that runs the agent through the repair pipeline. */
  public static WebServer start(Config config) throws IOException {
    return start(
        config, new PipelineLauncher(config.workspace(), config.decisions(), config.demoPace()));
  }

  /** Starts a server that runs the agent through {@code launcher}. */
  public static WebServer start(Config config, RunLauncher launcher) throws IOException {
    return new WebServer(
        Objects.requireNonNull(config, "config"), Objects.requireNonNull(launcher, "launcher"));
  }

  /** Returns the address the server answers at. */
  public URI uri() {
    InetSocketAddress address = server.getAddress();
    String host = address.getAddress().isAnyLocalAddress() ? "127.0.0.1" : address.getHostString();
    return URI.create(
        "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort() + "/");
  }

  /** Returns whether the web interface is bundled, as opposed to only the API. */
  public boolean bundled() {
    return app.bundled();
  }

  /** Stops the running run, closes open streams, and stops the server. */
  @Override
  public void close() {
    stopping = true;
    runs.close();
    server.stop(1);
    executor.close();
  }
}
