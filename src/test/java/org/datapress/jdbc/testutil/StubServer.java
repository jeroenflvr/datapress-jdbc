package org.datapress.jdbc.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tiny in-process DataPress stand-in built on the JDK {@link HttpServer}, for unit-testing the
 * driver's connect handshake and probes without a real server.
 */
public final class StubServer implements AutoCloseable {

  private final HttpServer server;
  private volatile int versionStatus = 200;
  private volatile String versionBody =
      "{\"name\":\"datapress-core\",\"version\":\"0.4.27\",\"backend\":\"DuckDB\",\"profile\":\"release\"}";
  private volatile int readyzStatus = 200;
  private volatile String readyzBody = "{\"status\":\"ready\",\"datasets\":3}";

  private static final String ARROW_MEDIA_TYPE = "application/vnd.apache.arrow.stream";
  private volatile int sqlStatus = 200;
  private volatile byte[] sqlBody = new byte[0];
  private volatile String sqlContentType = ARROW_MEDIA_TYPE;

  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastUserAgent = new AtomicReference<>();
  private final AtomicReference<String> lastSqlRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastSqlAccept = new AtomicReference<>();
  private final AtomicInteger versionHits = new AtomicInteger();
  private final AtomicInteger readyzHits = new AtomicInteger();
  private final AtomicInteger sqlHits = new AtomicInteger();

  private volatile String datasetsBody = "{\"datasets\":[]}";
  private final Map<String, String> schemaBodies = new ConcurrentHashMap<>();

  private StubServer(HttpServer server) {
    this.server = server;
  }

  public static StubServer start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StubServer stub = new StubServer(server);
    server.createContext("/version", stub::handleVersion);
    server.createContext("/readyz", stub::handleReadyz);
    server.createContext("/api/v1/sql", stub::handleSql);
    server.createContext("/api/v1/datasets", stub::handleDatasets);
    server.setExecutor(null);
    server.start();
    return stub;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** JDBC URL pointing at this stub, e.g. {@code jdbc:datapress://127.0.0.1:PORT/}. */
  public String jdbcUrl() {
    return "jdbc:datapress://127.0.0.1:" + port() + "/";
  }

  public void setVersionResponse(int status, String body) {
    this.versionStatus = status;
    this.versionBody = body;
  }

  public void setReadyzResponse(int status, String body) {
    this.readyzStatus = status;
    this.readyzBody = body;
  }

  /** Configure the Arrow IPC stream bytes returned by {@code POST /api/v1/sql}. */
  public void setSqlResponse(byte[] arrowBytes) {
    this.sqlStatus = 200;
    this.sqlBody = arrowBytes;
    this.sqlContentType = ARROW_MEDIA_TYPE;
  }

  /** Configure an error response (JSON envelope) for {@code POST /api/v1/sql}. */
  public void setSqlError(int status, String jsonBody) {
    this.sqlStatus = status;
    this.sqlBody = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
    this.sqlContentType = "application/json";
  }

  /** Configure the JSON body returned by {@code GET /api/v1/datasets}. */
  public void setDatasets(String jsonBody) {
    this.datasetsBody = jsonBody;
  }

  /** Register a schema JSON body for {@code GET /api/v1/datasets/{name}/schema}. */
  public void putSchema(String dataset, String jsonBody) {
    schemaBodies.put(dataset, jsonBody);
  }

  public String lastSqlRequestBody() {
    return lastSqlRequestBody.get();
  }

  public String lastSqlAccept() {
    return lastSqlAccept.get();
  }

  public int sqlHits() {
    return sqlHits.get();
  }

  public String lastAuthorization() {
    return lastAuthorization.get();
  }

  public String lastUserAgent() {
    return lastUserAgent.get();
  }

  public int versionHits() {
    return versionHits.get();
  }

  public int readyzHits() {
    return readyzHits.get();
  }

  private void handleVersion(HttpExchange exchange) throws IOException {
    versionHits.incrementAndGet();
    captureHeaders(exchange);
    respond(exchange, versionStatus, versionBody);
  }

  private void handleReadyz(HttpExchange exchange) throws IOException {
    readyzHits.incrementAndGet();
    captureHeaders(exchange);
    respond(exchange, readyzStatus, readyzBody);
  }

  private void handleSql(HttpExchange exchange) throws IOException {
    sqlHits.incrementAndGet();
    captureHeaders(exchange);
    List<String> accept = exchange.getRequestHeaders().get("Accept");
    lastSqlAccept.set(accept == null || accept.isEmpty() ? null : accept.get(0));
    lastSqlRequestBody.set(readBody(exchange.getRequestBody()));
    respondBytes(exchange, sqlStatus, sqlBody, sqlContentType);
  }

  private void handleDatasets(HttpExchange exchange) throws IOException {
    captureHeaders(exchange);
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/api/v1/datasets") || path.equals("/api/v1/datasets/")) {
      respond(exchange, 200, datasetsBody);
      return;
    }
    // Expect /api/v1/datasets/{name}/schema
    String rest = path.substring("/api/v1/datasets/".length());
    if (rest.endsWith("/schema")) {
      String name = rest.substring(0, rest.length() - "/schema".length());
      String body = schemaBodies.get(name);
      if (body != null) {
        respond(exchange, 200, body);
        return;
      }
      respond(exchange, 404, "{\"error\":\"not found: dataset: " + name + "\"}");
      return;
    }
    respond(exchange, 404, "{\"error\":\"not found\"}");
  }

  private static String readBody(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[4096];
    int read;
    while ((read = in.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }

  private void captureHeaders(HttpExchange exchange) {
    List<String> auth = exchange.getRequestHeaders().get("Authorization");
    lastAuthorization.set(auth == null || auth.isEmpty() ? null : auth.get(0));
    List<String> ua = exchange.getRequestHeaders().get("User-Agent");
    lastUserAgent.set(ua == null || ua.isEmpty() ? null : ua.get(0));
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    } else {
      exchange.close();
    }
  }

  private void respondBytes(HttpExchange exchange, int status, byte[] bytes, String contentType)
      throws IOException {
    byte[] payload = bytes == null ? new byte[0] : bytes;
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
    if (payload.length > 0) {
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(payload);
      }
    } else {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
