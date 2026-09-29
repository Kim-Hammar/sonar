package se.kth.sonar.web.http;

/** A failure to answer a request, with the status to send and a message for the client. */
public final class HttpError extends RuntimeException {
  private final int status;

  /** Creates an error with HTTP status {@code status}. */
  public HttpError(int status, String message) {
    super(message);
    this.status = status;
  }

  /** Returns the HTTP status. */
  public int status() {
    return status;
  }

  /** Returns a 400 error. */
  public static HttpError badRequest(String message) {
    return new HttpError(400, message);
  }

  /** Returns a 404 error. */
  public static HttpError notFound(String message) {
    return new HttpError(404, message);
  }

  /** Returns a 409 error. */
  public static HttpError conflict(String message) {
    return new HttpError(409, message);
  }
}
