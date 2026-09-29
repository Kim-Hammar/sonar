package se.kth.sonar.app;

import java.util.Optional;
import se.kth.sonar.batfish.BatfishInfo;

/** The versions of sonar and of what it embeds. */
public final class Versions {
  private Versions() {}

  /** Returns the version of sonar, or {@code development} outside a packaged build. */
  public static String sonar() {
    return Optional.ofNullable(Versions.class.getPackage().getImplementationVersion())
        .orElse("development");
  }

  /** Returns the version of the embedded Batfish. */
  public static String batfish() {
    return BatfishInfo.version();
  }

  /** Returns the version of the Java runtime. */
  public static String java() {
    return Runtime.version().toString();
  }
}
