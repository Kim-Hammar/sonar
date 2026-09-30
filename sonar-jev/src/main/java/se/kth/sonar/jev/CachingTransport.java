package se.kth.sonar.jev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Answers requests from a directory of earlier replies, one file per request key, and records the
 * successful replies of new requests, so that a repeated run costs no provider calls and receives
 * the same answers.
 */
public final class CachingTransport implements DecisionTransport {
  private static final int OK = 200;

  private final DecisionTransport delegate;
  private final Path directory;

  /** Creates a cache in {@code directory} in front of {@code delegate}. */
  public CachingTransport(DecisionTransport delegate, Path directory) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.directory = Objects.requireNonNull(directory, "directory");
  }

  @Override
  public Reply send(Request request) throws IOException, InterruptedException {
    Path file = directory.resolve(request.key() + ".json");
    if (Files.isRegularFile(file)) {
      return new Reply(OK, Files.readString(file, StandardCharsets.UTF_8), true);
    }
    Reply reply = delegate.send(request);
    if (reply.status() == OK) {
      Files.createDirectories(directory);
      Path temporary = Files.createTempFile(directory, "reply", ".tmp");
      Files.writeString(temporary, reply.body(), StandardCharsets.UTF_8);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    return reply;
  }
}
