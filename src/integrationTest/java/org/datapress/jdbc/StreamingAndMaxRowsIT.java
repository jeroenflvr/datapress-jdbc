package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = IntegrationTestBase.URL_ENV, matches = ".+")
class StreamingAndMaxRowsIT extends IntegrationTestBase {

  private static final int SERVER_MAX_ROWS = 10_000; // must match datasets.toml [sql].max_rows

  @Test
  void statementMaxRowsLimitsRows() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      st.setMaxRows(5);
      try (ResultSet rs = st.executeQuery("SELECT n FROM numbers ORDER BY n")) {
        assertThat(count(rs)).isEqualTo(5);
      }
    }
  }

  /** numbers has 20_000 rows; with no client limit the server clamps to its configured cap. */
  @Test
  void serverClampsToConfiguredMaxRows() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT n FROM numbers")) {
      assertThat(count(rs)).isEqualTo(SERVER_MAX_ROWS);
    }
  }

  /** Reads a multi-batch stream to completion and asserts the allocator subtree is released. */
  @Test
  void streamsMultipleBatchesWithoutLeaking() throws SQLException {
    try (Connection conn = connect()) {
      DataPressConnection dp = conn.unwrap(DataPressConnection.class);
      try (Statement st = conn.createStatement()) {
        ResultSet rs = st.executeQuery("SELECT n FROM numbers ORDER BY n");
        int rows = 0;
        long peak = 0;
        while (rs.next()) {
          assertThat(rs.getInt(1)).isEqualTo(rows);
          rows++;
          peak = Math.max(peak, dp.allocator().getAllocatedMemory());
        }
        assertThat(rows).isEqualTo(SERVER_MAX_ROWS); // > one batch
        assertThat(peak).isPositive();
        rs.close();
        assertThat(dp.allocator().getAllocatedMemory()).isZero();
      }
    }
  }

  private static int count(ResultSet rs) throws SQLException {
    int n = 0;
    while (rs.next()) {
      n++;
    }
    return n;
  }
}
