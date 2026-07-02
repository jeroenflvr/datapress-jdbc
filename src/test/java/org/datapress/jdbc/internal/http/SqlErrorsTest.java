package org.datapress.jdbc.internal.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

  @Test
  void connectFailureMapsTo08001() {
    SQLException e = SqlErrors.connectFailure("boom", new IOException("io"));
    assertThat((Throwable) e)
        .isInstanceOf(SQLNonTransientConnectionException.class)
        .hasCauseInstanceOf(IOException.class);
    assertThat(e.getSQLState()).isEqualTo("08001");
  }

  @Test
  void closedMapsTo08003() {
    SQLException e = SqlErrors.closed("Connection");
    assertThat((Throwable) e).isInstanceOf(SQLNonTransientConnectionException.class);
    assertThat(e.getSQLState()).isEqualTo("08003");
  }

  @Test
  void unsupportedMapsTo0A000() {
    SQLException e = SqlErrors.unsupported("something");
    assertThat((Throwable) e).isInstanceOf(SQLFeatureNotSupportedException.class);
    assertThat(e.getSQLState()).isEqualTo("0A000");
  }

  @Test
  void badRequestMapsTo42000() {
    SQLException e = SqlErrors.fromHttpStatus(400, "bad sql", SqlErrors.Context.GENERIC);
    assertThat((Throwable) e).isInstanceOf(SQLSyntaxErrorException.class);
    assertThat(e.getSQLState()).isEqualTo("42000");
    assertThat(e.getMessage()).contains("400").contains("bad sql");
  }

  @Test
  void authFailuresMapTo28000() {
    SQLException unauthorized = SqlErrors.fromHttpStatus(401, null, SqlErrors.Context.GENERIC);
    assertThat((Throwable) unauthorized).isInstanceOf(SQLInvalidAuthorizationSpecException.class);
    assertThat(unauthorized.getSQLState()).isEqualTo("28000");

    SQLException forbidden = SqlErrors.fromHttpStatus(403, null, SqlErrors.Context.GENERIC);
    assertThat((Throwable) forbidden).isInstanceOf(SQLInvalidAuthorizationSpecException.class);
  }

  @Test
  void sqlEndpoint404MeansDisabled() {
    SQLException e = SqlErrors.fromHttpStatus(404, "not found", SqlErrors.Context.SQL_ENDPOINT);
    assertThat((Throwable) e).isInstanceOf(SQLFeatureNotSupportedException.class);
    assertThat(e.getSQLState()).isEqualTo("0A000");
    assertThat(e.getMessage()).contains("[sql].enabled");
  }

  @Test
  void dataset404MeansUnknownTable() {
    SQLException e =
        SqlErrors.fromHttpStatus(404, "dataset x not found", SqlErrors.Context.DATASET);
    assertThat((Throwable) e).isInstanceOf(SQLSyntaxErrorException.class);
    assertThat(e.getSQLState()).isEqualTo("42S02");
  }

  @Test
  void transientStatusesMapToTransient() {
    for (int status : new int[] {408, 429, 503, 504}) {
      SQLException e = SqlErrors.fromHttpStatus(status, null, SqlErrors.Context.GENERIC);
      assertThat((Throwable) e).as("status %d", status).isInstanceOf(SQLTransientException.class);
    }
  }

  @Test
  void serverErrorsMapTo58000() {
    SQLException e = SqlErrors.fromHttpStatus(500, "kaboom", SqlErrors.Context.GENERIC);
    assertThat((Throwable) e).isInstanceOf(SQLNonTransientException.class);
    assertThat(e.getSQLState()).isEqualTo("58000");
  }

  @Test
  void parseErrorBodyExtractsErrorField() {
    assertThat(HttpApi.parseErrorBody("{\"error\":\"nope\"}")).isEqualTo("nope");
    assertThat(HttpApi.parseErrorBody("{\"status\":\"not_ready\",\"datasets\":0}"))
        .isEqualTo("not_ready");
    assertThat(HttpApi.parseErrorBody("plain text")).isEqualTo("plain text");
    assertThat(HttpApi.parseErrorBody("")).isNull();
    assertThat(HttpApi.parseErrorBody(null)).isNull();
  }
}
