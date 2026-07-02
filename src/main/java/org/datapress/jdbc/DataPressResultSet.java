package org.datapress.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.datapress.jdbc.internal.arrow.ArrowResultIterator;
import org.datapress.jdbc.internal.arrow.ColumnMeta;
import org.datapress.jdbc.internal.arrow.Convert;
import org.datapress.jdbc.internal.arrow.TypeMapping;
import org.datapress.jdbc.internal.arrow.ValueAccessor;
import org.datapress.jdbc.internal.arrow.ValueAccessors;
import org.datapress.jdbc.internal.http.SqlErrors;

/**
 * Forward-only, read-only {@link ResultSet} over a streamed Arrow IPC response. Batches are decoded
 * lazily as {@link #next()} advances; only one batch is resident at a time. All navigation other
 * than {@code next()}/{@code close()} and every update method is unsupported.
 */
final class DataPressResultSet implements ResultSet {

  private final DataPressStatement statement;
  private final ArrowResultIterator iterator;
  private final BufferAllocator allocator; // owned; closed on close()
  private final VectorSchemaRoot root;
  private final List<ColumnMeta> columns;
  private final ValueAccessor[] accessors;
  private final DataPressResultSetMetaData metaData;
  private final Map<String, Integer> columnIndex; // case-insensitive, first-wins

  private int rowInBatch = -1;
  private int rowCount;
  private long currentRow; // 1-based; 0 = before first
  private boolean onRow;
  private boolean exhausted;
  private boolean wasNull;
  private boolean closed;
  private int fetchSize;

  DataPressResultSet(
      DataPressStatement statement, ArrowResultIterator iterator, BufferAllocator allocator)
      throws SQLException {
    this.statement = statement;
    this.iterator = iterator;
    this.allocator = allocator;
    try {
      this.root = iterator.getRoot();
      List<FieldVector> vectors = root.getFieldVectors();
      this.accessors = new ValueAccessor[vectors.size()];
      this.columns = new java.util.ArrayList<>(vectors.size());
      this.columnIndex = new HashMap<>();
      for (int i = 0; i < vectors.size(); i++) {
        FieldVector fv = vectors.get(i);
        columns.add(TypeMapping.of(fv.getField(), iterator.provider()));
        accessors[i] = ValueAccessors.forField(fv, iterator.provider());
        columnIndex.putIfAbsent(fv.getField().getName().toLowerCase(java.util.Locale.ROOT), i + 1);
      }
      this.metaData = new DataPressResultSetMetaData(columns);
    } catch (IOException e) {
      throw SqlErrors.connectFailure("reading Arrow result schema", e);
    }
  }

  // --- Cursor movement ------------------------------------------------------------------------

  @Override
  public boolean next() throws SQLException {
    checkOpen();
    if (exhausted) {
      onRow = false;
      return false;
    }
    while (true) {
      rowInBatch++;
      if (rowInBatch < rowCount) {
        currentRow++;
        onRow = true;
        return true;
      }
      if (!advanceBatch()) {
        exhausted = true;
        onRow = false;
        return false;
      }
      rowInBatch = -1;
    }
  }

  private boolean advanceBatch() throws SQLException {
    try {
      boolean loaded = iterator.loadNextBatch();
      if (loaded) {
        rowCount = root.getRowCount();
      }
      return loaded;
    } catch (IOException e) {
      throw SqlErrors.connectFailure("reading Arrow result batch", e);
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      iterator.close();
    } catch (IOException ignored) {
      // best-effort; the allocator close below still runs
    }
    allocator.close();
    if (statement != null) {
      statement.resultSetClosed(this);
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  // --- Value plumbing -------------------------------------------------------------------------

  private void checkOpen() throws SQLException {
    if (closed) {
      throw SqlErrors.closed("ResultSet");
    }
  }

  private Object raw(int columnIndex1) throws SQLException {
    checkOpen();
    if (!onRow) {
      throw new SQLException("No current row; call next() first", "24000");
    }
    if (columnIndex1 < 1 || columnIndex1 > accessors.length) {
      throw new SQLException(
          "Invalid column index: " + columnIndex1 + " (1.." + accessors.length + ")");
    }
    Object value = accessors[columnIndex1 - 1].getObject(rowInBatch);
    wasNull = value == null;
    return value;
  }

  @Override
  public boolean wasNull() throws SQLException {
    checkOpen();
    return wasNull;
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    checkOpen();
    Integer idx =
        columnIndex.get(
            columnLabel == null ? null : columnLabel.toLowerCase(java.util.Locale.ROOT));
    if (idx == null) {
      throw new SQLException("Unknown column: " + columnLabel);
    }
    return idx;
  }

  // --- Typed getters by index -----------------------------------------------------------------

  @Override
  public String getString(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Convert.toDisplayString(v);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v != null && Convert.toBoolean(v);
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0 : Convert.toByte(v);
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0 : Convert.toShort(v);
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0 : Convert.toInt(v);
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0L : Convert.toLong(v);
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0f : Convert.toFloat(v);
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? 0d : Convert.toDouble(v);
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal bd = getBigDecimal(columnIndex);
    return bd == null ? null : bd.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Convert.toBigDecimal(v);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : Convert.toBytes(v);
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return getDate(columnIndex, null);
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : toDate(v, zoneOf(cal));
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return getTime(columnIndex, null);
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : toTime(v, zoneOf(cal));
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    return getTimestamp(columnIndex, null);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    Object v = raw(columnIndex);
    return v == null ? null : toTimestamp(v, zoneOf(cal));
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    Object v = raw(columnIndex);
    if (v == null) {
      return null;
    }
    if (v instanceof LocalDate) {
      return Date.valueOf((LocalDate) v);
    }
    if (v instanceof LocalTime) {
      return Time.valueOf((LocalTime) v);
    }
    if (v instanceof LocalDateTime) {
      return Timestamp.valueOf((LocalDateTime) v);
    }
    return v;
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("Target type must not be null");
    }
    Object v = raw(columnIndex);
    if (v == null) {
      return null;
    }
    return type.cast(coerce(v, type));
  }

  private Object coerce(Object v, Class<?> type) throws SQLException {
    if (type == String.class) {
      return Convert.toDisplayString(v);
    }
    if (type == Boolean.class) {
      return Convert.toBoolean(v);
    }
    if (type == Byte.class) {
      return Convert.toByte(v);
    }
    if (type == Short.class) {
      return Convert.toShort(v);
    }
    if (type == Integer.class) {
      return Convert.toInt(v);
    }
    if (type == Long.class) {
      return Convert.toLong(v);
    }
    if (type == Float.class) {
      return Convert.toFloat(v);
    }
    if (type == Double.class) {
      return Convert.toDouble(v);
    }
    if (type == BigDecimal.class) {
      return Convert.toBigDecimal(v);
    }
    if (type == byte[].class) {
      return Convert.toBytes(v);
    }
    if (type == Date.class) {
      return toDate(v, ZoneId.systemDefault());
    }
    if (type == Time.class) {
      return toTime(v, ZoneId.systemDefault());
    }
    if (type == Timestamp.class) {
      return toTimestamp(v, ZoneId.systemDefault());
    }
    if (type == LocalDate.class && v instanceof LocalDate) {
      return v;
    }
    if (type == LocalTime.class && v instanceof LocalTime) {
      return v;
    }
    if (type == LocalDateTime.class && v instanceof LocalDateTime) {
      return v;
    }
    if (type == OffsetDateTime.class && v instanceof OffsetDateTime) {
      return v;
    }
    if (type.isInstance(v)) {
      return v;
    }
    throw SqlErrors.dataConversion(
        "Cannot convert " + v.getClass().getSimpleName() + " to " + type.getName());
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnIndex);
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    String s = getString(columnIndex);
    return s == null ? null : new StringReader(s);
  }

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    String s = getString(columnIndex);
    return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    String s = getString(columnIndex);
    return s == null ? null : new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    byte[] b = getBytes(columnIndex);
    return b == null ? null : new ByteArrayInputStream(b);
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex);
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    return getCharacterStream(columnIndex);
  }

  // --- Typed getters by label (delegate) ------------------------------------------------------

  @Override
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
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
  @Deprecated
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(findColumn(columnLabel), cal);
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(findColumn(columnLabel), cal);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(findColumn(columnLabel), cal);
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return getObject(findColumn(columnLabel), map);
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn(columnLabel));
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return getAsciiStream(findColumn(columnLabel));
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return getUnicodeStream(findColumn(columnLabel));
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return getBinaryStream(findColumn(columnLabel));
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getNString(findColumn(columnLabel));
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    return getNCharacterStream(findColumn(columnLabel));
  }

  // --- Temporal conversion helpers ------------------------------------------------------------

  private static ZoneId zoneOf(Calendar cal) {
    return cal == null ? ZoneId.systemDefault() : cal.getTimeZone().toZoneId();
  }

  private Date toDate(Object v, ZoneId zone) throws SQLException {
    LocalDate ld;
    if (v instanceof LocalDate) {
      ld = (LocalDate) v;
    } else if (v instanceof LocalDateTime) {
      ld = ((LocalDateTime) v).toLocalDate();
    } else if (v instanceof OffsetDateTime) {
      ld = ((OffsetDateTime) v).atZoneSameInstant(zone).toLocalDate();
    } else if (v instanceof String) {
      ld = LocalDate.parse(((String) v).trim());
    } else {
      throw SqlErrors.dataConversion("Cannot convert " + v.getClass().getSimpleName() + " to DATE");
    }
    return new Date(ld.atStartOfDay(zone).toInstant().toEpochMilli());
  }

  private Time toTime(Object v, ZoneId zone) throws SQLException {
    LocalTime lt;
    if (v instanceof LocalTime) {
      lt = (LocalTime) v;
    } else if (v instanceof LocalDateTime) {
      lt = ((LocalDateTime) v).toLocalTime();
    } else if (v instanceof OffsetDateTime) {
      lt = ((OffsetDateTime) v).atZoneSameInstant(zone).toLocalTime();
    } else if (v instanceof String) {
      lt = LocalTime.parse(((String) v).trim());
    } else {
      throw SqlErrors.dataConversion("Cannot convert " + v.getClass().getSimpleName() + " to TIME");
    }
    Instant instant = lt.atDate(LocalDate.ofEpochDay(0)).atZone(zone).toInstant();
    return new Time(instant.toEpochMilli());
  }

  private Timestamp toTimestamp(Object v, ZoneId zone) throws SQLException {
    Instant instant;
    if (v instanceof LocalDateTime) {
      instant = ((LocalDateTime) v).atZone(zone).toInstant();
    } else if (v instanceof OffsetDateTime) {
      instant = ((OffsetDateTime) v).toInstant();
    } else if (v instanceof LocalDate) {
      instant = ((LocalDate) v).atStartOfDay(zone).toInstant();
    } else if (v instanceof LocalTime) {
      instant = ((LocalTime) v).atDate(LocalDate.ofEpochDay(0)).atZone(zone).toInstant();
    } else if (v instanceof String) {
      String s = ((String) v).trim();
      instant = LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant();
    } else {
      throw SqlErrors.dataConversion(
          "Cannot convert " + v.getClass().getSimpleName() + " to TIMESTAMP");
    }
    return Timestamp.from(instant);
  }

  // --- Metadata / misc ------------------------------------------------------------------------

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkOpen();
    return metaData;
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkOpen();
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    checkOpen();
  }

  @Override
  public String getCursorName() throws SQLException {
    throw SqlErrors.unsupported("Named cursors");
  }

  @Override
  public Statement getStatement() throws SQLException {
    checkOpen();
    return statement;
  }

  @Override
  public int getType() throws SQLException {
    checkOpen();
    return TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() throws SQLException {
    checkOpen();
    return CONCUR_READ_ONLY;
  }

  @Override
  public int getHoldability() throws SQLException {
    checkOpen();
    return CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkOpen();
    return fetchSize;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkOpen();
    this.fetchSize = Math.max(0, rows);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkOpen();
    return FETCH_FORWARD;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkOpen();
    if (direction != FETCH_FORWARD) {
      throw SqlErrors.unsupported("Fetch direction other than FETCH_FORWARD");
    }
  }

  @Override
  public int getRow() throws SQLException {
    checkOpen();
    return onRow ? (int) currentRow : 0;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkOpen();
    return currentRow == 0 && !exhausted;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    checkOpen();
    return exhausted && currentRow > 0;
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkOpen();
    return currentRow == 1;
  }

  @Override
  public boolean isLast() throws SQLException {
    throw SqlErrors.unsupported("isLast on a forward-only streaming ResultSet");
  }

  // --- Unsupported navigation (forward-only) --------------------------------------------------

  @Override
  public void beforeFirst() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public void afterLast() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public boolean first() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public boolean last() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw forwardOnly();
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw forwardOnly();
  }

  @Override
  public boolean previous() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw forwardOnly();
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw forwardOnly();
  }

  private static SQLException forwardOnly() {
    return new SQLException("ResultSet is TYPE_FORWARD_ONLY; only next() is supported", "0A000");
  }

  // --- Advanced getters (unsupported) ---------------------------------------------------------

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getRef");
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getBlob");
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getClob");
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getArray");
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getRef");
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getBlob");
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getClob");
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getArray");
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getRowId");
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getRowId");
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getNClob");
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getNClob");
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getSQLXML");
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getSQLXML");
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    throw SqlErrors.unsupported("getURL");
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    throw SqlErrors.unsupported("getURL");
  }

  // --- Row status -----------------------------------------------------------------------------

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

  @Override
  public void insertRow() throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateRow() throws SQLException {
    throw readOnly();
  }

  @Override
  public void deleteRow() throws SQLException {
    throw readOnly();
  }

  @Override
  public void refreshRow() throws SQLException {
    throw readOnly();
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw readOnly();
  }

  private static SQLFeatureNotSupportedException readOnly() {
    return SqlErrors.unsupported("Updatable result sets (the driver is read-only)");
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

  // --- Update methods (all unsupported, read-only driver) -------------------------------------

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
    throw readOnly();
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType)
      throws SQLException {
    throw readOnly();
  }
}
