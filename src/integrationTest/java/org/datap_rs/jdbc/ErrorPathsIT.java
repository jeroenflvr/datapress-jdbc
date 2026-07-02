package org.datap_rs.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = IntegrationTestBase.URL_ENV, matches = ".+")
class ErrorPathsIT extends IntegrationTestBase {

  @Test
  void unknownTableSurfacesAsSyntaxError() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      assertThatThrownBy(() -> st.executeQuery("SELECT * FROM does_not_exist"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).startsWith("42"));
    }
  }

  /**
   * Runs only when a second server with the SQL endpoint disabled is available (its URL in {@code
   * DATAPRESS_NOSQL_URL}). Start one with {@code scripts/run-server.sh up-nosql}.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "DATAPRESS_NOSQL_URL", matches = ".+")
  void sqlDisabledReportsFeatureNotSupported() throws SQLException {
    try (Connection conn = DriverManager.getConnection(System.getenv("DATAPRESS_NOSQL_URL"));
        Statement st = conn.createStatement()) {
      assertThatThrownBy(() -> st.executeQuery("SELECT 1"))
          .isInstanceOf(SQLException.class)
          .satisfies(
              e -> {
                assertThat(((SQLException) e).getSQLState()).isEqualTo("0A000");
                assertThat(e.getMessage().toLowerCase()).contains("sql");
              });
    }
  }
}
