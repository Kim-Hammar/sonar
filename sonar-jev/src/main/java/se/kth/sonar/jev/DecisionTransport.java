package se.kth.sonar.jev;

import java.io.IOException;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;

/**
 * Sends one request to the decision provider and returns its reply. Used by one thread at a time.
 */
public interface DecisionTransport {
  /** Sends {@code request} and waits for the reply. */
  Reply send(Request request) throws IOException, InterruptedException;

  /**
   * A request.
   *
   * @param key the content key of the request, for caching
   * @param body the JSON body
   */
  record Request(ContentHash key, String body) {
    /** Checks the components. */
    public Request {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(body, "body");
    }
  }

  /**
   * A reply.
   *
   * @param status the HTTP status code
   * @param body the JSON body
   * @param cached whether the reply was stored earlier instead of coming from the provider now
   */
  record Reply(int status, String body, boolean cached) {
    /** Checks the body. */
    public Reply {
      Objects.requireNonNull(body, "body");
    }

    /** A reply from the provider. */
    public Reply(int status, String body) {
      this(status, body, false);
    }
  }
}
