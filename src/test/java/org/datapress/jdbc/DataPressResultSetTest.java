package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.datapress.jdbc.testutil.ArrowStreams;
import org.datapress.jdbc.testutil.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataPressResultSetTest {

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

  // --- Type matrix ----------------------------------------------------------------------------

  @Test
  void readsEveryMappedTypeAndHonoursNulls() throws SQLException {
    stub.setSqlResponse(buildTypeMatrixStream());
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM matrix")) {

      // Row 0: fully populated.
      assertThat(rs.next()).isTrue();
      assertThat(rs.getBoolean(1)).isTrue();
      assertThat(rs.getByte(2)).isEqualTo((byte) 7);
      assertThat(rs.getShort(3)).isEqualTo((short) 300);
      assertThat(rs.getInt(4)).isEqualTo(100_000);
      assertThat(rs.getLong(5)).isEqualTo(10_000_000_000L);
      assertThat(rs.getLong(6)).isEqualTo(4_000_000_000L); // uint32
      assertThat(rs.getBigDecimal(7)).isEqualByComparingTo(new BigDecimal("42")); // uint64
      assertThat(rs.getFloat(8)).isEqualTo(1.5f);
      assertThat(rs.getDouble(9)).isEqualTo(2.5d);
      assertThat(rs.getBigDecimal(10)).isEqualByComparingTo(new BigDecimal("123.45"));
      assertThat(rs.getString(11)).isEqualTo("hello");
      assertThat(rs.getBytes(12)).containsExactly(1, 2, 3);
      assertThat(rs.getString(13)).isEqualTo("2021-01-15");
      assertThat(rs.getString(14)).isEqualTo("12:30:15");
      assertThat(rs.getTimestamp(15)).isNotNull();
      assertThat(rs.getObject(16)).isNotNull();
      assertThat(rs.wasNull()).isFalse();

      // Row 1: all NULL.
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(4)).isEqualTo(0);
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getString(11)).isNull();
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getObject(1)).isNull();
      assertThat(rs.wasNull()).isTrue();

      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void metadataDescribesColumns() throws SQLException {
    stub.setSqlResponse(buildTypeMatrixStream());
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM matrix")) {
      ResultSetMetaData md = rs.getMetaData();
      assertThat(md.getColumnCount()).isEqualTo(16);
      assertThat(md.getColumnType(4)).isEqualTo(Types.INTEGER);
      assertThat(md.getColumnClassName(5)).isEqualTo("java.lang.Long");
      assertThat(md.getColumnType(11)).isEqualTo(Types.VARCHAR);
      assertThat(md.getColumnType(10)).isEqualTo(Types.DECIMAL);
      assertThat(md.getPrecision(10)).isEqualTo(10);
      assertThat(md.getScale(10)).isEqualTo(2);
      assertThat(md.isReadOnly(1)).isTrue();
      assertThat(md.getColumnLabel(4)).isEqualTo("c_int");
    }
  }

  @Test
  void findColumnIsCaseInsensitive() throws SQLException {
    stub.setSqlResponse(buildTypeMatrixStream());
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM matrix")) {
      assertThat(rs.findColumn("C_INT")).isEqualTo(4);
      assertThat(rs.findColumn("c_varchar")).isEqualTo(11);
    }
  }

  // --- Error mapping --------------------------------------------------------------------------

  @Test
  void numericOverflowRaises22003() throws SQLException {
    stub.setSqlResponse(buildBigIntStream(Long.MAX_VALUE));
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT big FROM t")) {
      assertThat(rs.next()).isTrue();
      assertThatThrownBy(() -> rs.getInt(1))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22003"));
    }
  }

  @Test
  void executeUpdateRaises0A000() throws SQLException {
    stub.setSqlResponse(buildBigIntStream(1L));
    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      assertThatThrownBy(() -> st.executeUpdate("DELETE FROM t"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("0A000"));
    }
  }

  @Test
  void emptyResultYieldsNoRows() throws SQLException {
    stub.setSqlResponse(buildEmptyIntStream());
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT n FROM empty")) {
      assertThat(rs.next()).isFalse();
      assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
    }
  }

  // --- Streaming / allocator lifecycle --------------------------------------------------------

  @Test
  void consumesMultipleBatchesWithConstantMemoryAndNoLeak() throws SQLException {
    int batches = 3;
    int rowsPerBatch = 1_000;
    stub.setSqlResponse(buildMultiBatchIntStream(batches, rowsPerBatch));

    try (Connection conn = connect();
        Statement st = conn.createStatement()) {
      DataPressConnection dp = (DataPressConnection) conn;
      long[] highWater = new long[batches];

      try (ResultSet rs = st.executeQuery("SELECT n FROM big")) {
        int seen = 0;
        int batchIndex = -1;
        int rowInBatch = 0;
        while (rs.next()) {
          if (rowInBatch == 0) {
            batchIndex++;
            highWater[batchIndex] = dp.allocator().getAllocatedMemory();
          }
          assertThat(rs.getInt(1)).isEqualTo(seen);
          seen++;
          rowInBatch = (rowInBatch + 1) % rowsPerBatch;
        }
        assertThat(seen).isEqualTo(batches * rowsPerBatch);
      }

      // One batch resident at a time: per-batch memory is non-zero and identical across batches.
      assertThat(highWater[0]).isPositive();
      assertThat(highWater[1]).isEqualTo(highWater[0]);
      assertThat(highWater[2]).isEqualTo(highWater[0]);

      // Result set closed → its allocator subtree released; only the connection root remains.
      assertThat(dp.allocator().getAllocatedMemory()).isZero();
    }
  }

  @Test
  void closeCascadeReleasesAllBuffers() throws SQLException {
    stub.setSqlResponse(buildMultiBatchIntStream(2, 500));
    Connection conn = connect();
    DataPressConnection dp = (DataPressConnection) conn;
    Statement st = conn.createStatement();
    ResultSet rs = st.executeQuery("SELECT n FROM big");
    assertThat(rs.next()).isTrue();
    assertThat(dp.allocator().getAllocatedMemory()).isPositive();

    conn.close(); // must cascade through statement → result set → allocators
    assertThat(dp.allocator().getAllocatedMemory()).isZero();
  }

  // --- Stream builders ------------------------------------------------------------------------

  private static byte[] buildTypeMatrixStream() {
    try (BufferAllocator allocator = new RootAllocator()) {
      BitVector cBool = new BitVector("c_bool", allocator);
      TinyIntVector cTinyint = new TinyIntVector("c_tinyint", allocator);
      SmallIntVector cSmallint = new SmallIntVector("c_smallint", allocator);
      IntVector cInt = new IntVector("c_int", allocator);
      BigIntVector cBigint = new BigIntVector("c_bigint", allocator);
      UInt4Vector cUint32 = new UInt4Vector("c_uint32", allocator);
      UInt8Vector cUint64 = new UInt8Vector("c_uint64", allocator);
      Float4Vector cFloat = new Float4Vector("c_float", allocator);
      Float8Vector cDouble = new Float8Vector("c_double", allocator);
      DecimalVector cDecimal = new DecimalVector("c_decimal", allocator, 10, 2);
      VarCharVector cVarchar = new VarCharVector("c_varchar", allocator);
      VarBinaryVector cBinary = new VarBinaryVector("c_binary", allocator);
      DateDayVector cDate = new DateDayVector("c_date", allocator);
      TimeMilliVector cTime = new TimeMilliVector("c_time", allocator);
      TimeStampMicroVector cTs = new TimeStampMicroVector("c_ts", allocator);
      TimeStampMicroTZVector cTstz = new TimeStampMicroTZVector("c_tstz", allocator, "UTC");

      List<FieldVector> vectors =
          new ArrayList<>(
              List.of(
                  cBool, cTinyint, cSmallint, cInt, cBigint, cUint32, cUint64, cFloat, cDouble,
                  cDecimal, cVarchar, cBinary, cDate, cTime, cTs, cTstz));

      long micros =
          LocalDateTime.of(2021, 1, 15, 12, 30, 15).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
      int epochDay = (int) LocalDate.of(2021, 1, 15).toEpochDay();
      int millisOfDay = (12 * 3600 + 30 * 60 + 15) * 1000;

      VectorSchemaRoot root = new VectorSchemaRoot(vectors);
      Runnable fill =
          () -> {
            cBool.setSafe(0, 1);
            cBool.setNull(1);
            cTinyint.setSafe(0, 7);
            cTinyint.setNull(1);
            cSmallint.setSafe(0, 300);
            cSmallint.setNull(1);
            cInt.setSafe(0, 100_000);
            cInt.setNull(1);
            cBigint.setSafe(0, 10_000_000_000L);
            cBigint.setNull(1);
            cUint32.setSafe(0, (int) 4_000_000_000L);
            cUint32.setNull(1);
            cUint64.setSafe(0, 42L);
            cUint64.setNull(1);
            cFloat.setSafe(0, 1.5f);
            cFloat.setNull(1);
            cDouble.setSafe(0, 2.5d);
            cDouble.setNull(1);
            cDecimal.setSafe(0, new BigDecimal("123.45"));
            cDecimal.setNull(1);
            cVarchar.setSafe(0, "hello".getBytes(StandardCharsets.UTF_8));
            cVarchar.setNull(1);
            cBinary.setSafe(0, new byte[] {1, 2, 3});
            cBinary.setNull(1);
            cDate.setSafe(0, epochDay);
            cDate.setNull(1);
            cTime.setSafe(0, millisOfDay);
            cTime.setNull(1);
            cTs.setSafe(0, micros);
            cTs.setNull(1);
            cTstz.setSafe(0, micros);
            cTstz.setNull(1);
            root.setRowCount(2);
          };

      byte[] bytes = ArrowStreams.write(root, List.of(fill));
      root.close();
      return bytes;
    }
  }

  private static byte[] buildBigIntStream(long value) {
    try (BufferAllocator allocator = new RootAllocator()) {
      BigIntVector big = new BigIntVector("big", allocator);
      VectorSchemaRoot root = new VectorSchemaRoot(List.of((FieldVector) big));
      byte[] bytes =
          ArrowStreams.write(
              root,
              List.of(
                  () -> {
                    big.setSafe(0, value);
                    big.setValueCount(1);
                    root.setRowCount(1);
                  }));
      root.close();
      return bytes;
    }
  }

  private static byte[] buildEmptyIntStream() {
    try (BufferAllocator allocator = new RootAllocator()) {
      IntVector n = new IntVector("n", allocator);
      VectorSchemaRoot root = new VectorSchemaRoot(List.of((FieldVector) n));
      byte[] bytes = ArrowStreams.write(root, Collections.emptyList());
      root.close();
      return bytes;
    }
  }

  private static byte[] buildMultiBatchIntStream(int batches, int rowsPerBatch) {
    try (BufferAllocator allocator = new RootAllocator()) {
      IntVector n = new IntVector("n", allocator);
      VectorSchemaRoot root = new VectorSchemaRoot(List.of((FieldVector) n));
      List<Runnable> fillers = new ArrayList<>();
      for (int b = 0; b < batches; b++) {
        final int base = b * rowsPerBatch;
        fillers.add(
            () -> {
              for (int i = 0; i < rowsPerBatch; i++) {
                n.setSafe(i, base + i);
              }
              n.setValueCount(rowsPerBatch);
              root.setRowCount(rowsPerBatch);
            });
      }
      byte[] bytes = ArrowStreams.write(root, fillers);
      root.close();
      return bytes;
    }
  }
}
