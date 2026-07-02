package org.datapress.jdbc.internal.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Driver version, loaded once from a build-generated properties resource so the version lives in a
 * single place (the Gradle project version).
 */
public final class VersionInfo {

  private static final String RESOURCE =
      "org/datapress/jdbc/internal/util/datapress-jdbc-version.properties";

  private static final String VERSION = load();

  private VersionInfo() {}

  /** The driver version string, e.g. {@code 0.1.0-SNAPSHOT}. Never null. */
  public static String driverVersion() {
    return VERSION;
  }

  /**
   * Returns the major version component.
   *
   * @return the major version, or {@code 0} if it cannot be parsed
   */
  public static int driverMajorVersion() {
    return component(0);
  }

  /**
   * Returns the minor version component.
   *
   * @return the minor version, or {@code 0} if it cannot be parsed
   */
  public static int driverMinorVersion() {
    return component(1);
  }

  private static int component(int index) {
    String[] parts = VERSION.split("[.-]");
    if (index >= parts.length) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String load() {
    try (InputStream in = VersionInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return "unknown";
      }
      Properties props = new Properties();
      props.load(in);
      return props.getProperty("version", "unknown");
    } catch (IOException e) {
      return "unknown";
    }
  }
}
