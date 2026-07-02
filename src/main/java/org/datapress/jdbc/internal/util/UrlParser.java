package org.datapress.jdbc.internal.util;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Parses {@code jdbc:datapress://host[:port][/][?prop=value&...]} URLs and merges them with a
 * {@link Properties} object into a {@link ConnectionConfig}.
 *
 * <p>Precedence rules (see SKILL.md → "JDBC URL and connection properties"):
 *
 * <ul>
 *   <li>Values in {@code Properties} win over URL query parameters.
 *   <li>{@code token} may also be supplied as {@code password} (alias); {@code user} is ignored.
 *   <li>The URL scheme {@code jdbc:datapress:https://…} implies {@code tls=true}; an explicit
 *       {@code tls} property or query parameter overrides it.
 * </ul>
 */
public final class UrlParser {

  public static final String URL_PREFIX = "jdbc:datapress:";

  private static final String SQLSTATE_CONNECTION = "08001";

  private UrlParser() {}

  /** True only for URLs beginning with {@code jdbc:datapress:}. Null-safe. */
  public static boolean acceptsUrl(String url) {
    return url != null && url.startsWith(URL_PREFIX);
  }

  /**
   * Parses the URL and merges the given properties.
   *
   * @param url a {@code jdbc:datapress:} URL
   * @param info driver properties (may be {@code null})
   * @return the fully-resolved configuration
   * @throws SQLException if the URL is not a DataPress URL or a value is malformed
   */
  public static ConnectionConfig parse(String url, Properties info) throws SQLException {
    if (!acceptsUrl(url)) {
      throw new SQLNonTransientConnectionException(
          "Not a DataPress JDBC URL: " + url, SQLSTATE_CONNECTION);
    }

    String remainder = url.substring(URL_PREFIX.length());
    boolean schemeTls = false;
    if (startsWithIgnoreCase(remainder, "https://")) {
      schemeTls = true;
      remainder = remainder.substring("https://".length());
    } else if (startsWithIgnoreCase(remainder, "http://")) {
      remainder = remainder.substring("http://".length());
    } else if (remainder.startsWith("//")) {
      remainder = remainder.substring("//".length());
    } else {
      throw new SQLNonTransientConnectionException(
          "Malformed DataPress URL, expected jdbc:datapress://host[:port][?...]: " + url,
          SQLSTATE_CONNECTION);
    }

    // Split off the query string.
    String authorityAndPath = remainder;
    String query = "";
    int qIdx = remainder.indexOf('?');
    if (qIdx >= 0) {
      authorityAndPath = remainder.substring(0, qIdx);
      query = remainder.substring(qIdx + 1);
    }

    // Authority is everything up to the first '/'. Any path segment is ignored.
    String authority = authorityAndPath;
    int slashIdx = authorityAndPath.indexOf('/');
    if (slashIdx >= 0) {
      authority = authorityAndPath.substring(0, slashIdx);
    }
    if (authority.isEmpty()) {
      throw new SQLNonTransientConnectionException(
          "Malformed DataPress URL, missing host: " + url, SQLSTATE_CONNECTION);
    }

    String host;
    int port = ConnectionConfig.DEFAULT_PORT;
    int colonIdx = authority.lastIndexOf(':');
    if (colonIdx >= 0) {
      host = authority.substring(0, colonIdx);
      String portStr = authority.substring(colonIdx + 1);
      port = parsePort(portStr, url);
    } else {
      host = authority;
    }
    if (host.isEmpty()) {
      throw new SQLNonTransientConnectionException(
          "Malformed DataPress URL, missing host: " + url, SQLSTATE_CONNECTION);
    }

    Map<String, String> queryParams = parseQuery(query);
    Properties props = info == null ? new Properties() : info;

    // token / password alias — Properties win over URL, token preferred over password.
    String token =
        coalesce(
            props.getProperty("token"),
            props.getProperty("password"),
            queryParams.get("token"),
            queryParams.get("password"));

    boolean tls = schemeTls;
    tls = resolveBoolean("tls", queryParams.get("tls"), tls, url);
    tls = resolveBoolean("tls", props.getProperty("tls"), tls, url);

    int connectTimeout =
        resolveInt(
            "connectTimeout",
            firstNonNull(props.getProperty("connectTimeout"), queryParams.get("connectTimeout")),
            ConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MS,
            url);
    int socketTimeout =
        resolveInt(
            "socketTimeout",
            firstNonNull(props.getProperty("socketTimeout"), queryParams.get("socketTimeout")),
            ConnectionConfig.DEFAULT_SOCKET_TIMEOUT_MS,
            url);
    int maxRows =
        resolveInt(
            "maxRows",
            firstNonNull(props.getProperty("maxRows"), queryParams.get("maxRows")),
            0,
            url);

    String applicationName =
        firstNonNull(props.getProperty("applicationName"), queryParams.get("applicationName"));

    return new ConnectionConfig(
        host, port, tls, token, connectTimeout, socketTimeout, maxRows, applicationName);
  }

  /**
   * Builds the {@link DriverPropertyInfo} array describing the recognised properties, with any
   * already-known values filled in from the URL and supplied properties.
   */
  public static DriverPropertyInfo[] propertyInfo(String url, Properties info) {
    ConnectionConfig cfg = null;
    try {
      if (acceptsUrl(url)) {
        cfg = parse(url, info);
      }
    } catch (SQLException ignored) {
      // Fall back to defaults / supplied values below.
    }

    DriverPropertyInfo token =
        prop("token", cfg == null ? null : cfg.token(), info, "Bearer token for authentication.");
    DriverPropertyInfo password =
        prop("password", null, info, "Alias for 'token' (for user/password-only UIs).");
    DriverPropertyInfo user =
        prop("user", null, info, "Ignored by the driver; accepted for tool compatibility.");
    DriverPropertyInfo tls =
        prop(
            "tls",
            cfg == null ? "false" : Boolean.toString(cfg.tls()),
            info,
            "Use HTTPS (true|false). Default false.");
    tls.choices = new String[] {"true", "false"};
    DriverPropertyInfo connectTimeout =
        prop(
            "connectTimeout",
            Integer.toString(
                cfg == null ? ConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MS : cfg.connectTimeoutMs()),
            info,
            "Connect timeout in milliseconds.");
    DriverPropertyInfo socketTimeout =
        prop(
            "socketTimeout",
            Integer.toString(
                cfg == null ? ConnectionConfig.DEFAULT_SOCKET_TIMEOUT_MS : cfg.socketTimeoutMs()),
            info,
            "Socket/request timeout in milliseconds.");
    DriverPropertyInfo maxRows =
        prop(
            "maxRows",
            Integer.toString(cfg == null ? 0 : cfg.maxRows()),
            info,
            "Default per-statement row cap (0 = no client-side cap).");
    DriverPropertyInfo applicationName =
        prop(
            "applicationName",
            cfg == null ? null : cfg.applicationName(),
            info,
            "Application name added to the User-Agent header.");

    return new DriverPropertyInfo[] {
      token, password, user, tls, connectTimeout, socketTimeout, maxRows, applicationName
    };
  }

  private static DriverPropertyInfo prop(
      String name, String resolved, Properties info, String description) {
    String value = resolved;
    if (value == null && info != null) {
      value = info.getProperty(name);
    }
    DriverPropertyInfo dpi = new DriverPropertyInfo(name, value);
    dpi.required = false;
    dpi.description = description;
    return dpi;
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> params = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) {
      return params;
    }
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key;
      String value;
      if (eq >= 0) {
        key = urlDecode(pair.substring(0, eq));
        value = urlDecode(pair.substring(eq + 1));
      } else {
        key = urlDecode(pair);
        value = "";
      }
      params.put(key, value);
    }
    return params;
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      return s; // UTF-8 is always available; unreachable.
    }
  }

  private static int parsePort(String portStr, String url) throws SQLException {
    try {
      int port = Integer.parseInt(portStr);
      if (port < 1 || port > 65535) {
        throw new NumberFormatException();
      }
      return port;
    } catch (NumberFormatException e) {
      throw new SQLNonTransientConnectionException(
          "Invalid port '" + portStr + "' in URL: " + url, SQLSTATE_CONNECTION);
    }
  }

  private static boolean resolveBoolean(String name, String value, boolean fallback, String url)
      throws SQLException {
    if (value == null) {
      return fallback;
    }
    switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "true":
      case "1":
      case "yes":
        return true;
      case "false":
      case "0":
      case "no":
        return false;
      default:
        throw new SQLNonTransientConnectionException(
            "Invalid boolean value for '" + name + "': " + value + " in URL: " + url,
            SQLSTATE_CONNECTION);
    }
  }

  private static int resolveInt(String name, String value, int fallback, String url)
      throws SQLException {
    if (value == null || value.isEmpty()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      if (parsed < 0) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw new SQLNonTransientConnectionException(
          "Invalid integer value for '" + name + "': " + value + " in URL: " + url,
          SQLSTATE_CONNECTION);
    }
  }

  private static String coalesce(String... values) {
    return firstNonNull(values);
  }

  private static String firstNonNull(String... values) {
    for (String v : values) {
      if (v != null && !v.isEmpty()) {
        return v;
      }
    }
    return null;
  }

  private static boolean startsWithIgnoreCase(String s, String prefix) {
    return s.regionMatches(true, 0, prefix, 0, prefix.length());
  }
}
