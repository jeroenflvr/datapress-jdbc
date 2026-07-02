package org.datap_rs.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import org.datap_rs.jdbc.testutil.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataPressConnectionTest {

  private StubServer stub;

  @BeforeEach
  void setUp() throws Exception {
    stub = StubServer.start();
  }

  @AfterEach
  void tearDown() {
    if (stub != null) {
      stub.close();
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(stub.jdbcUrl());
  }

  @Test
  void connectsAndPerformsVersionHandshake() throws SQLException {
    try (Connection conn = connect()) {
      assertThat(conn).isInstanceOf(DataPressConnection.class);
      assertThat(conn.isClosed()).isFalse();
      assertThat(stub.versionHits()).isEqualTo(1);
    }
  }

  @Test
  void sendsBearerTokenAndUserAgent() throws SQLException {
    Properties props = new Properties();
    props.setProperty("token", "sekret");
    try (Connection conn = DriverManager.getConnection(stub.jdbcUrl(), props)) {
      assertThat(stub.lastAuthorization()).isEqualTo("Bearer sekret");
      assertThat(stub.lastUserAgent()).startsWith("datapress-jdbc/");
    }
  }

  @Test
  void handshakeFailurePropagatesAsSqlException() {
    stub.setVersionResponse(500, "{\"error\":\"boom\"}");
    assertThatThrownBy(this::connect).isInstanceOf(SQLException.class);
  }

  @Test
  void isValidReflectsReadyz() throws SQLException {
    try (Connection conn = connect()) {
      assertThat(conn.isValid(2)).isTrue();
      stub.setReadyzResponse(503, "{\"status\":\"not_ready\",\"datasets\":0}");
      assertThat(conn.isValid(2)).isFalse();
    }
  }

  @Test
  void isValidRejectsNegativeTimeout() throws SQLException {
    try (Connection conn = connect()) {
      assertThatThrownBy(() -> conn.isValid(-1)).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void closeIsIdempotentAndMarksClosed() throws SQLException {
    Connection conn = connect();
    conn.close();
    assertThat(conn.isClosed()).isTrue();
    conn.close();
    assertThat(conn.isValid(1)).isFalse();
  }

  @Test
  void readOnlyNonTransactionalSemantics() throws SQLException {
    try (Connection conn = connect()) {
      assertThat(conn.isReadOnly()).isTrue();
      assertThat(conn.getAutoCommit()).isTrue();
      assertThat(conn.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_NONE);
      assertThat(conn.getCatalog()).isEqualTo(DataPressConnection.CATALOG);
      assertThat(conn.getSchema()).isEqualTo(DataPressConnection.SCHEMA);

      // commit/rollback are silent no-ops so pools don't break.
      conn.commit();
      conn.rollback();

      assertThatThrownBy(() -> conn.setReadOnly(false))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> conn.setAutoCommit(false))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
    }
  }

  @Test
  void methodsThrowAfterClose() throws SQLException {
    Connection conn = connect();
    conn.close();
    assertThatThrownBy(conn::commit).isInstanceOf(SQLException.class);
    assertThatThrownBy(conn::rollback).isInstanceOf(SQLException.class);
  }
}
