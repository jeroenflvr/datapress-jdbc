package org.datapress.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared helpers for the integration suite. Every concrete test class is gated with
 * {@code @EnabledIfEnvironmentVariable(named = "DATAPRESS_URL", ...)} so the suite is silently
 * skipped when no live server is configured (e.g. {@code ./gradlew test} on a laptop).
 *
 * <p>Start a server with {@code scripts/run-server.sh up [datafusion|duckdb]} which prints the
 * {@code DATAPRESS_URL} to export, then run {@code ./gradlew integrationTest}.
 */
abstract class IntegrationTestBase {

  static final String URL_ENV = "DATAPRESS_URL";

  static Connection connect() throws SQLException {
    return DriverManager.getConnection(System.getenv(URL_ENV));
  }
}
