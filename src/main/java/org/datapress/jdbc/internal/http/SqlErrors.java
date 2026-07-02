package org.datapress.jdbc.internal.http;

import java.io.IOException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;

/**
 * Centralised HTTP-status &rarr; {@link SQLException} mapping (see docs/CONTRACT.md). All driver
 * code paths funnel server failures through here so SQLStates stay consistent.
 */
public final class SqlErrors {

  /**
   * SQLState for the caller-facing "SQL endpoint disabled" and generic unsupported-feature cases.
   */
  public static final String SQLSTATE_FEATURE_NOT_SUPPORTED = "0A000";

  private static final String SQLSTATE_CONNECTION = "08001";
  private static final String SQLSTATE_CLOSED = "08003";
  private static final String SQLSTATE_AUTH = "28000";
  private static final String SQLSTATE_SYNTAX = "42000";
  private static final String SQLSTATE_BAD_TABLE = "42S02";
  private static final String SQLSTATE_TRANSIENT = "57014";
  private static final String SQLSTATE_SERVER = "58000";
  private static final String SQLSTATE_NUMERIC_OVERFLOW = "22003";
  private static final String SQLSTATE_DATA_CONVERSION = "22018";

  /** Disambiguates how a {@code 404} should be interpreted for a given endpoint. */
  public enum Context {
    /** Generic endpoint (probes, etc.); a 404 is treated as a server error. */
    GENERIC,
    /** The raw-SQL endpoint; a 404 means SQL is disabled on the server. */
    SQL_ENDPOINT,
    /** A dataset-scoped endpoint; a 404 means the dataset is unknown. */
    DATASET
  }

  private SqlErrors() {}

  /** Network or connect failure (host unreachable, TLS handshake, socket read, …). */
  public static SQLException connectFailure(String detail, IOException cause) {
    SQLException e =
        new SQLNonTransientConnectionException(
            "Cannot reach DataPress server: " + detail, SQLSTATE_CONNECTION);
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  /** Operation attempted on a closed resource. */
  public static SQLException closed(String what) {
    return new SQLNonTransientConnectionException(what + " is closed", SQLSTATE_CLOSED);
  }

  /** A driver-unsupported JDBC feature. */
  public static SQLFeatureNotSupportedException unsupported(String feature) {
    return new SQLFeatureNotSupportedException(
        feature + " is not supported by the DataPress driver", SQLSTATE_FEATURE_NOT_SUPPORTED);
  }

  /** The specific "SQL endpoint disabled" error, with the server-config hint. */
  public static SQLFeatureNotSupportedException sqlEndpointDisabled() {
    return new SQLFeatureNotSupportedException(
        "SQL endpoint disabled on server — set [sql].enabled=true in the DataPress config",
        SQLSTATE_FEATURE_NOT_SUPPORTED);
  }

  /** A numeric getter overflowed the requested Java type (JDBC {@code 22003}). */
  public static SQLDataException numericOverflow(String detail) {
    return new SQLDataException(detail, SQLSTATE_NUMERIC_OVERFLOW);
  }

  /** A value could not be converted to the requested type (JDBC {@code 22018}). */
  public static SQLDataException dataConversion(String detail) {
    return new SQLDataException(detail, SQLSTATE_DATA_CONVERSION);
  }

  /**
   * Maps a non-2xx HTTP response to a {@link SQLException}.
   *
   * @param status the HTTP status code
   * @param serverMessage the parsed {@code error} message from the body (may be {@code null})
   * @param context how to interpret a 404 for this endpoint
   */
  public static SQLException fromHttpStatus(int status, String serverMessage, Context context) {
    String msg = detail(status, serverMessage);
    switch (status) {
      case 400:
      case 413:
        return new SQLSyntaxErrorException(msg, SQLSTATE_SYNTAX);
      case 401:
      case 403:
        return new SQLInvalidAuthorizationSpecException(msg, SQLSTATE_AUTH);
      case 404:
        switch (context) {
          case SQL_ENDPOINT:
            return sqlEndpointDisabled();
          case DATASET:
            return new SQLSyntaxErrorException(msg, SQLSTATE_BAD_TABLE);
          case GENERIC:
          default:
            return new SQLNonTransientException(msg, SQLSTATE_SERVER);
        }
      case 408:
      case 429:
      case 503:
      case 504:
        return new SQLTransientException(msg, SQLSTATE_TRANSIENT);
      default:
        if (status >= 500) {
          return new SQLNonTransientException(msg, SQLSTATE_SERVER);
        }
        // Any other 4xx.
        return new SQLNonTransientException(msg, SQLSTATE_SERVER);
    }
  }

  private static String detail(int status, String serverMessage) {
    if (serverMessage == null || serverMessage.isEmpty()) {
      return "DataPress server returned HTTP " + status;
    }
    return "DataPress server returned HTTP " + status + ": " + serverMessage;
  }
}
