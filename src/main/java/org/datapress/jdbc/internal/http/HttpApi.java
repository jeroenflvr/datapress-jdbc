package org.datapress.jdbc.internal.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import org.datapress.jdbc.internal.util.ConnectionConfig;

/**
 * Thin wrapper over the JDK {@link HttpClient} for the DataPress REST/Arrow API: bearer auth,
 * timeouts, {@code User-Agent}, and centralised error mapping via {@link SqlErrors}.
 *
 * <p>One instance per {@link org.datapress.jdbc.DataPressConnection}.
 */
public final class HttpApi implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ConnectionConfig config;
  private final HttpClient client;

  public HttpApi(ConnectionConfig config) {
    this.config = config;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public ConnectionConfig config() {
    return config;
  }

  /**
   * Performs the connect handshake: {@code GET /version}. Fails fast on unreachable/unauthorized.
   *
   * @return the parsed server version
   * @throws SQLException on any non-200 response or transport failure
   */
  public ServerVersion getVersion() throws SQLException {
    HttpRequest request =
        baseRequest(config.endpoint("/version"), config.socketTimeoutMs())
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> response = sendString(request, "GET /version");
    if (response.statusCode() != 200) {
      throw SqlErrors.fromHttpStatus(
          response.statusCode(), parseErrorBody(response.body()), SqlErrors.Context.GENERIC);
    }
    try {
      JsonNode node = MAPPER.readTree(response.body());
      return ServerVersion.fromJson(node);
    } catch (IOException e) {
      throw SqlErrors.connectFailure("could not parse /version response", e);
    }
  }

  /**
   * Readiness probe used by {@link org.datapress.jdbc.DataPressConnection#isValid(int)}: {@code GET
   * /readyz}. Returns {@code true} only on HTTP 200.
   *
   * @param timeoutSeconds per-call timeout; {@code <= 0} falls back to the socket timeout
   */
  public boolean isReady(int timeoutSeconds) throws SQLException {
    int timeoutMs = timeoutSeconds > 0 ? timeoutSeconds * 1000 : config.socketTimeoutMs();
    HttpRequest request =
        baseRequest(config.endpoint("/readyz"), timeoutMs)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<String> response = sendString(request, "GET /readyz");
    return response.statusCode() == 200;
  }

  /** Arrow IPC stream media type (verified against the server, see docs/CONTRACT.md). */
  private static final String ARROW_MEDIA_TYPE = "application/vnd.apache.arrow.stream";

  /**
   * Executes a read-only SQL statement and returns the Arrow IPC response body as a lazily-read
   * stream. The caller owns the stream and must close it (the {@code ResultSet} does).
   *
   * @param sql the statement text (already fully rendered; no server-side binding)
   * @param maxRows the {@code max_rows} cap to request; {@code 0} omits the field (server default)
   * @return the response {@link InputStream} positioned at the start of the Arrow stream
   * @throws SQLException with a mapped SQLState on any non-200 response or transport failure
   */
  public InputStream executeSql(String sql, int maxRows) throws SQLException {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("sql", sql);
    if (maxRows > 0) {
      payload.put("max_rows", maxRows);
    }
    String body;
    try {
      body = MAPPER.writeValueAsString(payload);
    } catch (IOException e) {
      throw SqlErrors.connectFailure("could not encode SQL request body", e);
    }
    HttpRequest request =
        baseRequest(config.endpoint("/api/v1/sql"), config.socketTimeoutMs())
            .header("Accept", ARROW_MEDIA_TYPE)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response = sendStream(request, "POST /api/v1/sql");
    if (response.statusCode() != 200) {
      String message = parseErrorBody(drain(response.body()));
      throw SqlErrors.fromHttpStatus(
          response.statusCode(), message, SqlErrors.Context.SQL_ENDPOINT);
    }
    return response.body();
  }

  private HttpResponse<InputStream> sendStream(HttpRequest request, String what)
      throws SQLException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw SqlErrors.connectFailure(what + " failed (" + e.getMessage() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw SqlErrors.connectFailure(what + " was interrupted", null);
    }
  }

  private static String drain(InputStream in) {
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = stream.read(buf)) != -1) {
        out.write(buf, 0, n);
        if (out.size() > 64 * 1024) {
          break; // error bodies are tiny; cap defensively
        }
      }
      return out.toString("UTF-8");
    } catch (IOException e) {
      return null;
    }
  }

  private HttpRequest.Builder baseRequest(URI uri, int timeoutMs) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("User-Agent", config.userAgent());
    if (config.token() != null && !config.token().isEmpty()) {
      builder.header("Authorization", "Bearer " + config.token());
    }
    return builder;
  }

  private HttpResponse<String> sendString(HttpRequest request, String what) throws SQLException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw SqlErrors.connectFailure(what + " failed (" + e.getMessage() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw SqlErrors.connectFailure(what + " was interrupted", null);
    }
  }

  /** Extracts the {@code error} field from a JSON error body, or returns the raw body. */
  static String parseErrorBody(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(body);
      JsonNode error = node.get("error");
      if (error != null && !error.isNull()) {
        return error.asText();
      }
      JsonNode status = node.get("status");
      if (status != null && !status.isNull()) {
        return status.asText();
      }
    } catch (IOException ignored) {
      // Not JSON — fall through to the raw body.
    }
    return body.length() > 500 ? body.substring(0, 500) : body;
  }

  @Override
  public void close() {
    // The JDK HttpClient has no explicit close on Java 11–20; nothing to release here.
    // Arrow allocator lifecycle is owned by the Connection/Statement (Phase 2).
  }
}
