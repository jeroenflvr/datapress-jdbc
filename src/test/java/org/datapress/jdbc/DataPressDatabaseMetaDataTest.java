package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.datapress.jdbc.testutil.StubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataPressDatabaseMetaDataTest {

  private StubServer stub;

  private static final String DATASETS =
      "{\"datasets\":["
          + "{\"name\":\"people\",\"rows\":4,\"columns\":5},"
          + "{\"name\":\"types\",\"rows\":2,\"columns\":3},"
          + "{\"name\":\"numbers\",\"rows\":20000,\"columns\":1}]}";

  private static final String PEOPLE_SCHEMA =
      "{\"name\":\"people\",\"rows\":4,\"columns\":["
          + "{\"name\":\"id\",\"logical\":\"int\",\"sql_type\":\"Int64\",\"nullable\":true},"
          + "{\"name\":\"name\",\"logical\":\"utf8\",\"sql_type\":\"Dictionary(Int32, Utf8)\",\"nullable\":true},"
          + "{\"name\":\"active\",\"logical\":\"bool\",\"sql_type\":\"Boolean\",\"nullable\":true},"
          + "{\"name\":\"score\",\"logical\":\"float\",\"sql_type\":\"Float64\",\"nullable\":true},"
          + "{\"name\":\"created\",\"logical\":\"temporal\",\"sql_type\":\"Timestamp(Microsecond, Some(\\\"UTC\\\"))\",\"nullable\":false}]}";

  private static final String TYPES_SCHEMA =
      "{\"name\":\"types\",\"rows\":2,\"columns\":["
          + "{\"name\":\"c_decimal\",\"logical\":\"other\",\"sql_type\":\"Decimal128(10, 2)\",\"nullable\":true},"
          + "{\"name\":\"c_date\",\"logical\":\"temporal\",\"sql_type\":\"Date32\",\"nullable\":true},"
          + "{\"name\":\"c_binary\",\"logical\":\"other\",\"sql_type\":\"Binary\",\"nullable\":true}]}";

  private static final String NUMBERS_SCHEMA =
      "{\"name\":\"numbers\",\"rows\":20000,\"columns\":["
          + "{\"name\":\"n\",\"logical\":\"int\",\"sql_type\":\"Int64\",\"nullable\":true}]}";

  @BeforeEach
  void setUp() throws Exception {
    stub = StubServer.start();
    stub.setVersionResponse(
        200,
        "{\"name\":\"datapress-core\",\"version\":\"0.5.0\",\"backend\":\"DataFusion\",\"profile\":\"release\"}");
    stub.setDatasets(DATASETS);
    stub.putSchema("people", PEOPLE_SCHEMA);
    stub.putSchema("types", TYPES_SCHEMA);
    stub.putSchema("numbers", NUMBERS_SCHEMA);
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
  void identityAndVersions() throws SQLException {
    try (Connection conn = connect()) {
      DatabaseMetaData md = conn.getMetaData();
      assertThat(md.getDatabaseProductName()).isEqualTo("DataPress");
      assertThat(md.getDatabaseProductVersion()).isEqualTo("0.5.0");
      assertThat(md.getDatabaseMajorVersion()).isEqualTo(0);
      assertThat(md.getDatabaseMinorVersion()).isEqualTo(5);
      assertThat(md.getDriverName()).isEqualTo("DataPress JDBC Driver");
      assertThat(md.isReadOnly()).isTrue();
      assertThat(md.getConnection()).isSameAs(conn);
      assertThat(md.getIdentifierQuoteString()).isEqualTo("\"");
      assertThat(md.getSearchStringEscape()).isEqualTo("\\");
    }
  }

  @Test
  void getTablesReturnsDatasetsSortedWithFixedCatalogAndSchema() throws SQLException {
    try (Connection conn = connect()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = conn.getMetaData().getTables(null, null, null, null)) {
        while (rs.next()) {
          assertThat(rs.getString("TABLE_CAT")).isEqualTo("datapress");
          assertThat(rs.getString("TABLE_SCHEM")).isEqualTo("main");
          assertThat(rs.getString("TABLE_TYPE")).isEqualTo("TABLE");
          names.add(rs.getString("TABLE_NAME"));
        }
      }
      assertThat(names).containsExactly("numbers", "people", "types");
    }
  }

  @Test
  void getTablesAppliesNamePattern() throws SQLException {
    try (Connection conn = connect()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = conn.getMetaData().getTables(null, null, "p%", null)) {
        while (rs.next()) {
          names.add(rs.getString("TABLE_NAME"));
        }
      }
      assertThat(names).containsExactly("people");
    }
  }

  @Test
  void getTablesFiltersByType() throws SQLException {
    try (Connection conn = connect()) {
      try (ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[] {"VIEW"})) {
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = conn.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
        assertThat(rs.next()).isTrue();
      }
    }
  }

  @Test
  void getColumnsMapsTypesFromSchema() throws SQLException {
    try (Connection conn = connect()) {
      List<String> columnNames = new ArrayList<>();
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "people", null)) {
        while (rs.next()) {
          columnNames.add(rs.getString("COLUMN_NAME"));
          switch (rs.getString("COLUMN_NAME")) {
            case "id":
              assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.BIGINT);
              assertThat(rs.getString("TYPE_NAME")).isEqualTo("BIGINT");
              assertThat(rs.getInt("ORDINAL_POSITION")).isEqualTo(1);
              assertThat(rs.getString("IS_NULLABLE")).isEqualTo("YES");
              assertThat(rs.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
              break;
            case "name":
              assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.VARCHAR);
              assertThat(rs.getString("TYPE_NAME")).isEqualTo("VARCHAR");
              break;
            case "active":
              assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.BOOLEAN);
              break;
            case "score":
              assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.DOUBLE);
              break;
            case "created":
              assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
              assertThat(rs.getString("TYPE_NAME")).isEqualTo("TIMESTAMP WITH TIME ZONE");
              assertThat(rs.getString("IS_NULLABLE")).isEqualTo("NO");
              assertThat(rs.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNoNulls);
              break;
            default:
              break;
          }
          assertThat(rs.getString("TABLE_NAME")).isEqualTo("people");
          assertThat(rs.getString("TABLE_CAT")).isEqualTo("datapress");
        }
      }
      assertThat(columnNames).containsExactly("id", "name", "active", "score", "created");
    }
  }

  @Test
  void getColumnsMapsDecimalDateAndBinary() throws SQLException {
    try (Connection conn = connect()) {
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "types", null)) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("COLUMN_NAME")).isEqualTo("c_decimal");
        assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.DECIMAL);
        assertThat(rs.getInt("COLUMN_SIZE")).isEqualTo(10);
        assertThat(rs.getInt("DECIMAL_DIGITS")).isEqualTo(2);
        assertThat(rs.getInt("NUM_PREC_RADIX")).isEqualTo(10);

        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("COLUMN_NAME")).isEqualTo("c_date");
        assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.DATE);

        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("COLUMN_NAME")).isEqualTo("c_binary");
        assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.VARBINARY);

        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void getColumnsAppliesColumnPattern() throws SQLException {
    try (Connection conn = connect()) {
      List<String> cols = new ArrayList<>();
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "people", "%e")) {
        while (rs.next()) {
          cols.add(rs.getString("COLUMN_NAME"));
        }
      }
      assertThat(cols).containsExactly("name", "active", "score");
    }
  }

  @Test
  void catalogsSchemasAndTableTypes() throws SQLException {
    try (Connection conn = connect()) {
      DatabaseMetaData md = conn.getMetaData();
      try (ResultSet rs = md.getCatalogs()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("TABLE_CAT")).isEqualTo("datapress");
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getSchemas()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("TABLE_SCHEM")).isEqualTo("main");
        assertThat(rs.getString("TABLE_CATALOG")).isEqualTo("datapress");
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getTableTypes()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("TABLE_TYPE")).isEqualTo("TABLE");
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void getTypeInfoIsNonEmptyAndContainsVarchar() throws SQLException {
    try (Connection conn = connect()) {
      boolean sawVarchar = false;
      int count = 0;
      try (ResultSet rs = conn.getMetaData().getTypeInfo()) {
        while (rs.next()) {
          count++;
          if ("VARCHAR".equals(rs.getString("TYPE_NAME"))) {
            sawVarchar = true;
            assertThat(rs.getInt("DATA_TYPE")).isEqualTo(Types.VARCHAR);
          }
        }
      }
      assertThat(count).isGreaterThan(5);
      assertThat(sawVarchar).isTrue();
    }
  }

  @Test
  void keyAndIndexResultSetsAreEmptyWithCorrectColumns() throws SQLException {
    try (Connection conn = connect()) {
      DatabaseMetaData md = conn.getMetaData();
      try (ResultSet rs = md.getPrimaryKeys(null, null, "people")) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(6);
        assertThat(rs.getMetaData().getColumnName(4)).isEqualTo("COLUMN_NAME");
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getImportedKeys(null, null, "people")) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(14);
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getIndexInfo(null, null, "people", false, true)) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(13);
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getProcedures(null, null, null)) {
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getFunctions(null, null, null)) {
        assertThat(rs.next()).isFalse();
      }
    }
  }

  /** Every DatabaseMetaData method must be callable without throwing. */
  @Test
  void everyMethodIsCallableWithoutThrowing() throws SQLException {
    try (Connection conn = connect()) {
      DatabaseMetaData md = conn.getMetaData();
      List<String> failures = new ArrayList<>();
      for (Method method : DatabaseMetaData.class.getMethods()) {
        String name = method.getName();
        if (name.equals("unwrap") || name.equals("isWrapperFor")) {
          continue; // require a concrete Class argument; covered separately
        }
        Object[] args = defaultArgs(method);
        try {
          Object result = method.invoke(md, args);
          if (result instanceof ResultSet) {
            ResultSet rs = (ResultSet) result;
            rs.getMetaData().getColumnCount();
            while (rs.next()) {
              // drain to exercise row access paths
              rs.getObject(1);
            }
            rs.close();
          }
        } catch (Exception e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          failures.add(name + " -> " + cause);
        }
      }
      assertThat(failures).isEmpty();
    }
  }

  private static Object[] defaultArgs(Method method) {
    Class<?>[] types = method.getParameterTypes();
    Object[] args = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> t = types[i];
      if (t == int.class) {
        args[i] = 0;
      } else if (t == boolean.class) {
        args[i] = false;
      } else if (t == short.class) {
        args[i] = (short) 0;
      } else if (t == long.class) {
        args[i] = 0L;
      } else {
        args[i] = null; // String, String[], int[], Class, etc.
      }
    }
    return args;
  }
}
