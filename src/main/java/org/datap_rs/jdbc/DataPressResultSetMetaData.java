package org.datap_rs.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import org.datap_rs.jdbc.internal.arrow.ColumnMeta;

/** {@link ResultSetMetaData} over the {@link ColumnMeta} list derived from the Arrow schema. */
final class DataPressResultSetMetaData implements ResultSetMetaData {

  private final List<ColumnMeta> columns;

  DataPressResultSetMetaData(List<ColumnMeta> columns) {
    this.columns = columns;
  }

  private ColumnMeta col(int column) throws SQLException {
    if (column < 1 || column > columns.size()) {
      throw new SQLException("Invalid column index: " + column + " (1.." + columns.size() + ")");
    }
    return columns.get(column - 1);
  }

  @Override
  public int getColumnCount() {
    return columns.size();
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    col(column);
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    return col(column).caseSensitive();
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    col(column);
    return true;
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    col(column);
    return false;
  }

  @Override
  public int isNullable(int column) throws SQLException {
    return col(column).nullable();
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    return col(column).signed();
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    return col(column).displaySize();
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    return col(column).name();
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    return col(column).name();
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    col(column);
    return DataPressConnection.SCHEMA;
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    return col(column).precision();
  }

  @Override
  public int getScale(int column) throws SQLException {
    return col(column).scale();
  }

  @Override
  public String getTableName(int column) throws SQLException {
    col(column);
    return "";
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    col(column);
    return DataPressConnection.CATALOG;
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    return col(column).sqlType();
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    return col(column).typeName();
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    col(column);
    return true;
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    col(column);
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    col(column);
    return false;
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    return col(column).className();
  }

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
