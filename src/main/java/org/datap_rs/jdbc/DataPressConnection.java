package org.datap_rs.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.datap_rs.jdbc.internal.http.HttpApi;
import org.datap_rs.jdbc.internal.http.ServerVersion;
import org.datap_rs.jdbc.internal.http.SqlErrors;
import org.datap_rs.jdbc.internal.util.ConnectionConfig;

/**
 * A read-only, non-transactional {@link Connection} to a DataPress server.
 *
 * <p>Semantics (see SKILL.md → "Architecture and package layout"): {@code autoCommit=true} (only
 * {@code true} is accepted), {@code TRANSACTION_NONE}, {@code isReadOnly()=true}, {@code commit()}/
 * {@code rollback()} are silent no-ops so connection pools do not break, and {@code
 * setReadOnly(false)} is rejected. Fixed catalog {@code datapress} and schema {@code main}.
 */
public final class DataPressConnection implements Connection {

  static final String CATALOG = "datapress";
  static final String SCHEMA = "main";

  private final ConnectionConfig config;
  private final HttpApi http;
  private final ServerVersion serverVersion;
  private final Properties clientInfo = new Properties();
  private final BufferAllocator rootAllocator = new RootAllocator();
  private final Set<DataPressStatement> openStatements = ConcurrentHashMap.newKeySet();

  private volatile boolean closed = false;

  DataPressConnection(ConnectionConfig config, HttpApi http, ServerVersion serverVersion) {
    this.config = config;
    this.http = http;
    this.serverVersion = serverVersion;
  }

  // --- Package-private accessors for Statement / DatabaseMetaData (later phases) --------------

  ConnectionConfig config() {
    return config;
  }

  HttpApi http() {
    return http;
  }

  ServerVersion serverVersion() {
    return serverVersion;
  }

  BufferAllocator allocator() {
    return rootAllocator;
  }

  /** Callback from {@link DataPressStatement#close()} so the connection can drop its reference. */
  void statementClosed(DataPressStatement statement) {
    openStatements.remove(statement);
  }

  private void checkOpen() throws SQLException {
    if (closed) {
      throw SqlErrors.closed("Connection");
    }
  }

  // --- Lifecycle ------------------------------------------------------------------------------

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (DataPressStatement statement : openStatements) {
      statement.close();
    }
    openStatements.clear();
    rootAllocator.close();
    http.close();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw new SQLNonTransientConnectionException("isValid timeout must not be negative", "08003");
    }
    if (closed) {
      return false;
    }
    try {
      return http.isReady(timeout);
    } catch (SQLException e) {
      return false;
    }
  }

  @Override
  public void abort(Executor executor) {
    close();
  }

  // --- Transactions (no-op / read-only) -------------------------------------------------------

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    checkOpen();
    if (!autoCommit) {
      throw SqlErrors.unsupported("Disabling auto-commit (the driver is read-only)");
    }
  }

  @Override
  public boolean getAutoCommit() throws SQLException {
    checkOpen();
    return true;
  }

  @Override
  public void commit() throws SQLException {
    checkOpen();
    // No-op: nothing is ever written. Throwing would break connection pools.
  }

  @Override
  public void rollback() throws SQLException {
    checkOpen();
    // No-op: read-only.
  }

  @Override
  public void rollback(Savepoint savepoint) throws SQLException {
    throw SqlErrors.unsupported("Savepoints");
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    throw SqlErrors.unsupported("Savepoints");
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    throw SqlErrors.unsupported("Savepoints");
  }

  @Override
  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    throw SqlErrors.unsupported("Savepoints");
  }

  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    checkOpen();
    if (level != Connection.TRANSACTION_NONE) {
      throw SqlErrors.unsupported("Transaction isolation levels other than TRANSACTION_NONE");
    }
  }

  @Override
  public int getTransactionIsolation() throws SQLException {
    checkOpen();
    return Connection.TRANSACTION_NONE;
  }

  // --- Read-only flag -------------------------------------------------------------------------

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    checkOpen();
    if (!readOnly) {
      throw SqlErrors.unsupported("A writable connection (the driver is read-only)");
    }
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    checkOpen();
    return true;
  }

  // --- Catalog / schema (fixed) ---------------------------------------------------------------

  @Override
  public void setCatalog(String catalog) throws SQLException {
    checkOpen();
    // Fixed catalog; silently ignored per JDBC guidance for drivers without catalog support.
  }

  @Override
  public String getCatalog() throws SQLException {
    checkOpen();
    return CATALOG;
  }

  @Override
  public void setSchema(String schema) throws SQLException {
    checkOpen();
    // Fixed schema; silently ignored.
  }

  @Override
  public String getSchema() throws SQLException {
    checkOpen();
    return SCHEMA;
  }

  // --- Statement factories (implemented in later phases) --------------------------------------

  @Override
  public Statement createStatement() throws SQLException {
    checkOpen();
    BufferAllocator statementAllocator =
        rootAllocator.newChildAllocator("statement", 0, Long.MAX_VALUE);
    DataPressStatement statement = new DataPressStatement(this, statementAllocator);
    openStatements.add(statement);
    return statement;
  }

  @Override
  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkOpen();
    requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
    return createStatement();
  }

  @Override
  public Statement createStatement(
      int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    checkOpen();
    requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
    return createStatement();
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    checkOpen();
    throw SqlErrors.unsupported("prepareStatement");
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkOpen();
    requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    checkOpen();
    requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
    return prepareStatement(sql);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    throw SqlErrors.unsupported("Auto-generated keys");
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    throw SqlErrors.unsupported("Auto-generated keys");
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    throw SqlErrors.unsupported("Auto-generated keys");
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    throw SqlErrors.unsupported("Callable statements");
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    throw SqlErrors.unsupported("Callable statements");
  }

  @Override
  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    throw SqlErrors.unsupported("Callable statements");
  }

  private void requireForwardOnlyReadOnly(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    if (resultSetType != java.sql.ResultSet.TYPE_FORWARD_ONLY) {
      throw SqlErrors.unsupported("Scrollable/updatable result sets (only TYPE_FORWARD_ONLY)");
    }
    if (resultSetConcurrency != java.sql.ResultSet.CONCUR_READ_ONLY) {
      throw SqlErrors.unsupported("Updatable result sets (only CONCUR_READ_ONLY)");
    }
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    checkOpen();
    return sql;
  }

  @Override
  public DatabaseMetaData getMetaData() throws SQLException {
    checkOpen();
    return new DataPressDatabaseMetaData(this);
  }

  // --- Holdability ----------------------------------------------------------------------------

  @Override
  public void setHoldability(int holdability) throws SQLException {
    checkOpen();
    if (holdability != java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      throw SqlErrors.unsupported("HOLD_CURSORS_OVER_COMMIT");
    }
  }

  @Override
  public int getHoldability() throws SQLException {
    checkOpen();
    return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  // --- Warnings -------------------------------------------------------------------------------

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    checkOpen();
  }

  // --- Client info ----------------------------------------------------------------------------

  @Override
  public void setClientInfo(String name, String value) throws SQLClientInfoException {
    if (closed) {
      throw new SQLClientInfoException("Connection is closed", "08003", Collections.emptyMap());
    }
    if (value == null) {
      clientInfo.remove(name);
    } else {
      clientInfo.setProperty(name, value);
    }
  }

  @Override
  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    if (closed) {
      throw new SQLClientInfoException("Connection is closed", "08003", Collections.emptyMap());
    }
    clientInfo.clear();
    if (properties != null) {
      clientInfo.putAll(properties);
    }
  }

  @Override
  public String getClientInfo(String name) throws SQLException {
    checkOpen();
    return clientInfo.getProperty(name);
  }

  @Override
  public Properties getClientInfo() throws SQLException {
    checkOpen();
    Properties copy = new Properties();
    copy.putAll(clientInfo);
    return copy;
  }

  // --- Type map -------------------------------------------------------------------------------

  @Override
  public Map<String, Class<?>> getTypeMap() throws SQLException {
    checkOpen();
    return Collections.emptyMap();
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    throw SqlErrors.unsupported("Custom type maps");
  }

  // --- LOB / advanced object factories (unsupported) ------------------------------------------

  @Override
  public Clob createClob() throws SQLException {
    throw SqlErrors.unsupported("createClob");
  }

  @Override
  public Blob createBlob() throws SQLException {
    throw SqlErrors.unsupported("createBlob");
  }

  @Override
  public NClob createNClob() throws SQLException {
    throw SqlErrors.unsupported("createNClob");
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    throw SqlErrors.unsupported("createSQLXML");
  }

  @Override
  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    throw SqlErrors.unsupported("createArrayOf");
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    throw SqlErrors.unsupported("createStruct");
  }

  // --- Network timeout ------------------------------------------------------------------------

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    checkOpen();
    // The per-request timeout is fixed at connection time (socketTimeout); ignored here.
  }

  @Override
  public int getNetworkTimeout() throws SQLException {
    checkOpen();
    return config.socketTimeoutMs();
  }

  // --- Wrapper --------------------------------------------------------------------------------

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}
