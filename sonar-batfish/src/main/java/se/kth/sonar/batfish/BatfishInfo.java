package se.kth.sonar.batfish;

import org.batfish.version.BatfishVersion;

/** Facts about the embedded Batfish library. */
public final class BatfishInfo {
  private BatfishInfo() {}

  /** Returns the version of the embedded Batfish, such as {@code 0.36.0}. */
  public static String version() {
    return BatfishVersion.getVersionStatic();
  }
}
