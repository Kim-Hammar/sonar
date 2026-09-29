package se.kth.sonar.jev;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;

/**
 * Sends requests to the decision endpoint over HTTP/2 with the JDK's HTTP client. The API key is
 * read from the environment for each request and never logged. Rate limits are respected by waiting
 * as the provider's {@code Retry-After} header asks.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class HttpTransport implements DecisionTransport {
  private final Settings settings;

  /**
   * Where and how to send requests.
   *
   * @param endpoint the decision endpoint
   * @param model the model name
   * @param apiKeyVariable the environment variable that holds the API key
   * @param timeout the time to wait for one reply
   */
  public record Settings(URI endpoint, String model, String apiKeyVariable, Duration timeout) {
    /** Checks the components. */
    public Settings {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(apiKeyVariable, "apiKeyVariable");
      Objects.requireNonNull(timeout, "timeout");
    }

    /** Returns the settings of the public Jev endpoint on OpenRouter. */
    public static Settings openRouter() {
      return new Settings(
          URI.create("https://openrouter.ai/api/alpha/decisions"),
          "~typesafe/jev-latest",
          "OPENROUTER_API_KEY",
          Duration.ofSeconds(45));
    }
  }

  /** Creates a transport; no connection is opened until the first request. */
  public HttpTransport(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public Reply send(Request request) {
    throw new NotImplemented("HttpTransport.send");
  }
}
