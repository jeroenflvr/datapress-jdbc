package org.datapress.jdbc.internal.meta;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A forward-only, read-only {@link ResultSet} over an in-memory {@code List<Object[]>} and a fixed
 * set of {@link Column} definitions. Used for every synthetic {@code DatabaseMetaData} result set
 * (and available for {@code DESCRIBE} fallbacks and tests). Correct {@link ResultSetMetaData} is
 * exposed even when there are zero rows.
 */
public final class SyntheticResultSet implements ResultSet {

  /** A synthetic column definition: name plus its {@link java.sql.Types} code. */
  public static final class Column {
    final String name;
    final int sqlType;
    final String typeName;
    final String className;
    final int displaySize;

    private Column(String name, int sqlType, String typeName, String className, int displaySize) {
      this.name = name;
      this.sqlType = sqlType;
      this.typeName = typeName;
      this.className = className;
      this.displaySize = displaySize;
    }

    /** Builds a column, deriving type/class names from the {@link java.sql.Types} code. */
    public static Column of(String name, int sqlType) {
      switch (sqlType) {
        case java.sql.Types.VARCHAR:
          return new Column(name, sqlType, "VARCHAR", "java.lang.String", 65535);
        case java.sql.Types.SMALLINT:
          return new Column(name, sqlType, "SMALLINT", "java.lang.Short", 6);
        case java.sql.Types.INTEGER:
          return new Column(name, sqlType, "INTEGER", "java.lang.Integer", 11);
        case java.sql.Types.BIGINT:
          return new Column(name, sqlType, "BIGINT", "java.lang.Long", 20);
        case java.sql.Types.BOOLEAN:
          return new Column(name, sqlType, "BOOLEAN", "java.lang.Boolean", 5);
        case java.sql.Types.BIT:
          return new Column(name, sqlType, "BIT", "java.lang.Boolean", 1);
        default:
          return new Column(name, sqlType, "VARCHAR", "java.lang.String", 65535);
      }
    }
  }

  /** Fluent builder for a {@link SyntheticResultSet}. */
  public static final class Builder {
    private final List<Column> columns = new ArrayList<>();
    private final Map<String, Integer> index = new LinkedHashMap<>();
    private final List<Object[]> rows = new ArrayList<>();

    /** Adds a column of the given {@link java.sql.Types} code. */
    public Builder column(String name, int sqlType) {
      index.put(name.toUpperCase(java.util.Locale.ROOT), columns.size());
      columns.add(Column.of(name, sqlType));
      return this;
    }

    /** Adds one row. The array length must match the number of declared columns. */
    public Builder row(Object... values) {
      if (values.length != columns.size()) {
        throw new IllegalArgumentException(
            "row has " + values.length + " values but " + columns.size() + " columns declared");
      }
      rows.add(values.clone());
      return this;
    }

    public SyntheticResultSet build() {
      return new SyntheticResultSet(new ArrayList<>(columns), index, rows);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private final List<Column> columns;
  private final Map<String, Integer> index; // upper-cased name -> 0-based position
  private final List<Object[]> rows;
  private final Meta meta;

  private int cursor = -1; // before first
  private boolean lastWasNull = false;
  private boolean closed = false;

  private SyntheticResultSet(
      List<Column> columns, Map<String, Integer> index, List<Object[]> rows) {
    this.columns = columns;
    this.index = index;
    this.rows = rows;
    this.meta = new Meta(columns);
  }

  // --- Navigation ----------------------------------------------------------------------------

  @Override
  public boolean next() throws SQLException {
    checkOpen();
    if (cursor + 1 < rows.size()) {
      cursor++;
      return true;
    }
    cursor = rows.size(); // after last
    return false;
  }

  @Override
  public void close() {
    closed = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isBeforeFirst() {
    return cursor < 0 && !rows.isEmpty();
  }

  @Override
  public boolean isAfterLast() {
    return cursor >= rows.size() && !rows.isEmpty();
  }

  @Override
  public boolean isFirst() {
    return cursor == 0 && !rows.isEmpty();
  }

  @Override
  public boolean isLast() {
    return !rows.isEmpty() && cursor == rows.size() - 1;
  }

  @Override
  public int getRow() {
    return (cursor >= 0 && cursor < rows.size()) ? cursor + 1 : 0;
  }

  // --- Cell access ---------------------------------------------------------------------------

  private Object raw(int columnIndex) throws SQLException {
    checkOpen();
    if (cursor < 0 || cursor >= rows.size()) {
      throw new SQLException("Not on a valid row; call next() first");
    }
    if (columnIndex < 1 || columnIndex > columns.size()) {
      throw new SQLException(
          "Invalid column index: " + columnIndex + " (1.." + columns.size() + ")");
    }
    Object value = rows.get(cursor)[columnIndex - 1];
    lastWasNull = (value == null);
    return value;
  }

  @Override
  public boolean wasNull() {
    return lastWasNull;
  }

  @Override
  public String getString(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : String.valueOf(v);
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    return raw(columnIndex);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return false;
    }
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    if (v instanceof Number) {
      return ((Number) v).longValue() != 0;
    }
    return "true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString());
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    return (byte) getLong(columnIndex);
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    return (short) getLong(columnIndex);
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    return (int) getLong(columnIndex);
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return 0L;
    }
    if (v instanceof Number) {
      return ((Number) v).longValue();
    }
    if (v instanceof Boolean) {
      return ((Boolean) v) ? 1L : 0L;
    }
    try {
      return Long.parseLong(v.toString().trim());
    } catch (NumberFormatException e) {
      throw new SQLException("Cannot convert '" + v + "' to long", "22018", e);
    }
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    return (float) getDouble(columnIndex);
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return 0d;
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    try {
      return Double.parseDouble(v.toString().trim());
    } catch (NumberFormatException e) {
      throw new SQLException("Cannot convert '" + v + "' to double", "22018", e);
    }
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return null;
    }
    if (v instanceof BigDecimal) {
      return (BigDecimal) v;
    }
    return new BigDecimal(v.toString().trim());
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal d = getBigDecimal(columnIndex);
    return d == null ? null : d.setScale(scale, java.math.RoundingMode.HALF_UP);
  }

  // --- Label-based delegates -----------------------------------------------------------------

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    checkOpen();
    Integer pos = index.get(columnLabel.toUpperCase(java.util.Locale.ROOT));
    if (pos == null) {
      throw new SQLException("No such column: " + columnLabel);
    }
    return pos + 1;
  }

  @Override
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return null;
    }
    if (v instanceof byte[]) {
      return (byte[]) v;
    }
    throw unsupported("getBytes for non-binary column");
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  // --- Metadata / properties -----------------------------------------------------------------

  @Override
  public ResultSetMetaData getMetaData() {
    return meta;
  }

  @Override
  public int getType() {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getFetchDirection() {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction != ResultSet.FETCH_FORWARD) {
      throw unsupported("Only FETCH_FORWARD is supported");
    }
  }

  @Override
  public int getFetchSize() {
    return rows.size();
  }

  @Override
  public void setFetchSize(int rows) {
    // Ignored: everything is already in memory.
  }

  @Override
  public int getHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public Statement getStatement() {
    return null;
  }

  @Override
  public String getCursorName() throws SQLException {
    throw unsupported("getCursorName");
  }

  @Override
  public SQLWarning getWarnings() {
    return null;
  }

  @Override
  public void clearWarnings() {
    // No warnings are ever generated.
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

  // --- Temporal accessors (metadata never stores these, but keep them well-behaved) ----------

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Date.valueOf(v.toString());
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Time.valueOf(v.toString());
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Timestamp.valueOf(v.toString());
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    return getDate(columnIndex);
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(columnLabel);
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    return getTime(columnIndex);
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(columnLabel);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    return getTimestamp(columnIndex);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(columnLabel);
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return null;
    }
    if (type.isInstance(v)) {
      return type.cast(v);
    }
    if (type == String.class) {
      return type.cast(String.valueOf(v));
    }
    throw unsupported("getObject(Class) conversion to " + type.getName());
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnIndex);
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnLabel);
  }

  // --- Everything below is unsupported for a forward-only, read-only synthetic set ------------

  private void checkOpen() throws SQLException {
    if (closed) {
      throw new SQLException("ResultSet is closed", "24000");
    }
  }

  private static SQLFeatureNotSupportedException unsupported(String what) {
    return new SQLFeatureNotSupportedException(what + " is not supported", "0A000");
  }

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    throw unsupported("getAsciiStream");
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    throw unsupported("getUnicodeStream");
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    throw unsupported("getBinaryStream");
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    throw unsupported("getAsciiStream");
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    throw unsupported("getUnicodeStream");
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    throw unsupported("getBinaryStream");
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    throw unsupported("getCharacterStream");
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    throw unsupported("getCharacterStream");
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    throw unsupported("getNCharacterStream");
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    throw unsupported("getNCharacterStream");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex);
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getString(columnLabel);
  }

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    throw unsupported("getRef");
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    throw unsupported("getRef");
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    throw unsupported("getBlob");
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    throw unsupported("getBlob");
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    throw unsupported("getClob");
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    throw unsupported("getClob");
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    throw unsupported("getNClob");
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    throw unsupported("getNClob");
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw unsupported("getSQLXML");
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw unsupported("getSQLXML");
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    throw unsupported("getArray");
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    throw unsupported("getArray");
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    throw unsupported("getURL");
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    throw unsupported("getURL");
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    throw unsupported("getRowId");
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    throw unsupported("getRowId");
  }

  // --- Absolute/relative navigation: unsupported (forward-only) -------------------------------

  @Override
  public void beforeFirst() throws SQLException {
    throw unsupported("beforeFirst");
  }

  @Override
  public void afterLast() throws SQLException {
    throw unsupported("afterLast");
  }

  @Override
  public boolean first() throws SQLException {
    throw unsupported("first");
  }

  @Override
  public boolean last() throws SQLException {
    throw unsupported("last");
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw unsupported("absolute");
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw unsupported("relative");
  }

  @Override
  public boolean previous() throws SQLException {
    throw unsupported("previous");
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    return false;
  }

  @Override
  public boolean rowInserted() throws SQLException {
    return false;
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    return false;
  }

  // --- Update methods: all unsupported (read-only) -------------------------------------------

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw unsupported("updateNull");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw unsupported("updateBoolean");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw unsupported("updateByte");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw unsupported("updateShort");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw unsupported("updateInt");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw unsupported("updateLong");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw unsupported("updateFloat");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw unsupported("updateDouble");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw unsupported("updateBigDecimal");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw unsupported("updateString");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw unsupported("updateBytes");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw unsupported("updateDate");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw unsupported("updateTime");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw unsupported("updateTimestamp");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw unsupported("updateNull");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw unsupported("updateBoolean");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw unsupported("updateByte");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw unsupported("updateShort");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw unsupported("updateInt");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw unsupported("updateLong");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw unsupported("updateFloat");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw unsupported("updateDouble");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw unsupported("updateBigDecimal");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw unsupported("updateString");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw unsupported("updateBytes");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw unsupported("updateDate");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw unsupported("updateTime");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw unsupported("updateTimestamp");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw unsupported("updateObject");
  }

  @Override
  public void insertRow() throws SQLException {
    throw unsupported("insertRow");
  }

  @Override
  public void updateRow() throws SQLException {
    throw unsupported("updateRow");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw unsupported("deleteRow");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw unsupported("refreshRow");
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw unsupported("cancelRowUpdates");
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw unsupported("moveToInsertRow");
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw unsupported("moveToCurrentRow");
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw unsupported("updateRef");
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw unsupported("updateRef");
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw unsupported("updateArray");
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw unsupported("updateArray");
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw unsupported("updateRowId");
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw unsupported("updateRowId");
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw unsupported("updateNString");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw unsupported("updateNString");
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw unsupported("updateSQLXML");
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw unsupported("updateSQLXML");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw unsupported("updateAsciiStream");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw unsupported("updateBinaryStream");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateCharacterStream");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw unsupported("updateBlob");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateClob");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateNClob");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw unsupported("updateNCharacterStream");
  }

  /** {@link ResultSetMetaData} over the synthetic {@link Column} list. */
  private static final class Meta implements ResultSetMetaData {
    private final List<Column> columns;

    Meta(List<Column> columns) {
      this.columns = columns;
    }

    private Column col(int column) throws SQLException {
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
      return col(column).sqlType == java.sql.Types.VARCHAR;
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
      col(column);
      return ResultSetMetaData.columnNullableUnknown;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
      int t = col(column).sqlType;
      return t == java.sql.Types.INTEGER
          || t == java.sql.Types.SMALLINT
          || t == java.sql.Types.BIGINT
          || t == java.sql.Types.TINYINT;
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
      return col(column).displaySize;
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
      return col(column).name;
    }

    @Override
    public String getColumnName(int column) throws SQLException {
      return col(column).name;
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
      col(column);
      return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
      return col(column).displaySize;
    }

    @Override
    public int getScale(int column) throws SQLException {
      col(column);
      return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
      col(column);
      return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
      col(column);
      return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
      return col(column).sqlType;
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
      return col(column).typeName;
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
      return col(column).className;
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
}
