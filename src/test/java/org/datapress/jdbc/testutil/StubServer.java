package org.datapress.jdbc.testutil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastUserAgent = new AtomicReference<>();
  private final AtomicInteger versionHits = new AtomicInteger();
  private final AtomicInteger readyzHits = new AtomicInteger();

  private StubServer(HttpServer server) {
    this.server = server;
  }

  public static StubServer start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    StubServer stub = new StubServer(server);
    server.createContext("/version", stub::handleVersion);
    server.createContext("/readyz", stub::handleReadyz);
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

  @Override
  public void close() {
    server.stop(0);
  }
}
