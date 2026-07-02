package org.datap_rs.jdbc.internal.util;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable, fully-resolved connection configuration, produced by {@link UrlParser} from a JDBC URL
 * plus a {@link java.util.Properties} object. All defaults are already applied.
 */
public final class ConnectionConfig {

  /** Default server port when the URL omits one. */
  public static final int DEFAULT_PORT = 8000;

  /** Default connect timeout: 10 seconds. */
  public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

  /** Default socket (request) timeout: 300 seconds. */
  public static final int DEFAULT_SOCKET_TIMEOUT_MS = 300_000;

  private final String host;
  private final int port;
  private final boolean tls;
  private final String token; // nullable
  private final int connectTimeoutMs;
  private final int socketTimeoutMs;
  private final int maxRows; // 0 == no client-side cap
  private final String applicationName; // nullable

  ConnectionConfig(
      String host,
      int port,
      boolean tls,
      String token,
      int connectTimeoutMs,
      int socketTimeoutMs,
      int maxRows,
      String applicationName) {
    this.host = Objects.requireNonNull(host, "host");
    this.port = port;
    this.tls = tls;
    this.token = token;
    this.connectTimeoutMs = connectTimeoutMs;
    this.socketTimeoutMs = socketTimeoutMs;
    this.maxRows = maxRows;
    this.applicationName = applicationName;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public boolean tls() {
    return tls;
  }

  /** Bearer token, or {@code null} when none was supplied. */
  public String token() {
    return token;
  }

  public int connectTimeoutMs() {
    return connectTimeoutMs;
  }

  public int socketTimeoutMs() {
    return socketTimeoutMs;
  }

  /** Default per-statement row cap, or {@code 0} for no client-side cap. */
  public int maxRows() {
    return maxRows;
  }

  /** Application name for the User-Agent, or {@code null}. */
  public String applicationName() {
    return applicationName;
  }

  /** Base server URI, e.g. {@code http://localhost:8000}, with no trailing slash. */
  public URI baseUri() {
    String scheme = tls ? "https" : "http";
    return URI.create(scheme + "://" + host + ":" + port);
  }

  /** Absolute URI for a server path such as {@code /version} or {@code /api/v1/sql}. */
  public URI endpoint(String path) {
    String base = baseUri().toString();
    String normalized = path.startsWith("/") ? path : "/" + path;
    return URI.create(base + normalized);
  }

  /** The {@code User-Agent} header value. */
  public String userAgent() {
    String base = "datapress-jdbc/" + VersionInfo.driverVersion();
    return applicationName == null || applicationName.isEmpty()
        ? base
        : base + " (" + applicationName + ")";
  }

  /** A redacted description safe for logs and exception messages (never leaks the token). */
  @Override
  public String toString() {
    return "ConnectionConfig{"
        + baseUri()
        + ", token="
        + (token == null ? "<none>" : "<set>")
        + ", connectTimeoutMs="
        + connectTimeoutMs
        + ", socketTimeoutMs="
        + socketTimeoutMs
        + ", maxRows="
        + maxRows
        + '}';
  }
}
