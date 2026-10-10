package se.kth.sonar.jev;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends requests to the decision endpoint over HTTP/2 with the JDK's HTTP client. The API key is
 * read from the environment for each request and never logged. Rate limits are respected by waiting
 * as the provider's {@code Retry-After} header asks before the next request.
 */
public final class HttpTransport implements DecisionTransport {
  private static final int TOO_MANY_REQUESTS = 429;
  private static final Duration LONGEST_WAIT = Duration.ofSeconds(30);

  private final Settings settings;
  private final HttpClient client;
  private Instant notBefore = Instant.MIN;

  /**
   * Where and how to send requests.
   *
   * @param endpoint the decision endpoint
   * @param model the model name
   * @param apiKeyVariable the environment variable that holds the API key
   * @param timeout the time to wait for one reply
   * @param api the protocol the endpoint speaks
   */
  public record Settings(
      URI endpoint, String model, String apiKeyVariable, Duration timeout, Api api) {
    /** Checks the components. */
    public Settings {
      Objects.requireNonNull(endpoint, "endpoint");
      Objects.requireNonNull(model, "model");
      Objects.requireNonNull(apiKeyVariable, "apiKeyVariable");
      Objects.requireNonNull(timeout, "timeout");
      Objects.requireNonNull(api, "api");
    }

    /** Creates settings for an endpoint that speaks Jev's decision protocol. */
    public Settings(URI endpoint, String model, String apiKeyVariable, Duration timeout) {
      this(endpoint, model, apiKeyVariable, timeout, Api.DECISIONS);
    }

    /** Returns the transport these settings describe. */
    public DecisionTransport transport() {
      HttpTransport http = new HttpTransport(this);
      return switch (api) {
        case DECISIONS -> http;
        case CHAT -> new ChatTransport(http, model);
      };
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

  /** The protocols a decision endpoint may speak. */
  public enum Api {
    /** Jev's typed decision questions, as on OpenRouter's decisions endpoint. */
    DECISIONS,
    /** OpenAI-compatible chat completions, asked the same questions by {@link ChatTransport}. */
    CHAT
  }

  /** Creates a transport; no connection is opened until the first request. */
  public HttpTransport(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(settings.timeout())
            .build();
  }

  @Override
  public Reply send(Request request) throws IOException, InterruptedException {
    String key = Optional.ofNullable(System.getenv(settings.apiKeyVariable())).orElse("");
    if (key.isBlank()) {
      throw new IOException("the environment variable " + settings.apiKeyVariable() + " is unset");
    }
    Duration wait = Duration.between(Instant.now(), notBefore);
    if (wait.isPositive()) {
      Thread.sleep(wait);
    }
    HttpRequest http =
        HttpRequest.newBuilder(settings.endpoint())
            .timeout(settings.timeout())
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response =
        client.send(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() == TOO_MANY_REQUESTS) {
      response
          .headers()
          .firstValueAsLong("Retry-After")
          .ifPresent(
              seconds -> {
                Duration pause = Duration.ofSeconds(Math.max(0, seconds));
                notBefore =
                    Instant.now().plus(pause.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : pause);
              });
    }
    return new Reply(response.statusCode(), response.body());
  }
}
