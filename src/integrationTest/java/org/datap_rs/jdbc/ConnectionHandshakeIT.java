package org.datap_rs.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = IntegrationTestBase.URL_ENV, matches = ".+")
class ConnectionHandshakeIT extends IntegrationTestBase {

  @Test
  void connectsAndIsValid() throws SQLException {
    try (Connection conn = connect()) {
      assertThat(conn.isValid(5)).isTrue();
      assertThat(conn.isReadOnly()).isTrue();
      assertThat(conn.getCatalog()).isEqualTo("datapress");
      assertThat(conn.getSchema()).isEqualTo("main");
    }
  }

  @Test
  void cachesServerVersionFromHandshake() throws SQLException {
    try (Connection conn = connect()) {
      DataPressConnection dp = conn.unwrap(DataPressConnection.class);
      assertThat(dp.serverVersion().version()).isNotBlank();
    }
  }

  @Test
  void setReadOnlyFalseIsRejected() throws SQLException {
    try (Connection conn = connect()) {
      assertThatThrownBy(() -> conn.setReadOnly(false)).isInstanceOf(SQLException.class);
    }
  }
}
