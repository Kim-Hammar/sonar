package se.kth.sonar.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads text resources from the class path. */
public final class Resources {
  private Resources() {}

  /**
   * Reads the UTF-8 resource at the absolute class-path location {@code path}, such as {@code
   * /se/kth/sonar/dialect/cisco-ios.grammar}.
   *
   * @throws IllegalArgumentException if there is no such resource
   */
  public static String read(String path) {
    try (InputStream input = Resources.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalArgumentException("no class-path resource " + path);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read class-path resource " + path, e);
    }
  }
}
