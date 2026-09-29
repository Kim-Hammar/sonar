package se.kth.sonar.jev;

import java.nio.file.Path;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;

/**
 * Answers requests from a directory of earlier replies, one file per request key, and records the
 * replies of new requests, so that a repeated run costs no provider calls and receives the same
 * answers.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class CachingTransport implements DecisionTransport {
  private final DecisionTransport delegate;
  private final Path directory;

  /** Creates a cache in {@code directory} in front of {@code delegate}. */
  public CachingTransport(DecisionTransport delegate, Path directory) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  @Override
  public Reply send(Request request) {
    throw new NotImplemented("CachingTransport.send");
  }
}
