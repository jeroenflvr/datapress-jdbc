package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Round-trips {@code getTables}/{@code getColumns} against the live fixtures. */
@EnabledIfEnvironmentVariable(named = IntegrationTestBase.URL_ENV, matches = ".+")
class MetadataIT extends IntegrationTestBase {

  @Test
  void getTablesListsFixtureDatasets() throws SQLException {
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
      assertThat(names).contains("people", "types", "numbers");
    }
  }

  @Test
  void getTablesAppliesLikePattern() throws SQLException {
    try (Connection conn = connect()) {
      List<String> names = new ArrayList<>();
      try (ResultSet rs = conn.getMetaData().getTables(null, null, "pe%", null)) {
        while (rs.next()) {
          names.add(rs.getString("TABLE_NAME"));
        }
      }
      assertThat(names).containsExactly("people");
    }
  }

  @Test
  void getColumnsMatchesPeopleFixture() throws SQLException {
    try (Connection conn = connect()) {
      Map<String, Integer> types = new LinkedHashMap<>();
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "people", null)) {
        int ordinal = 0;
        while (rs.next()) {
          ordinal++;
          assertThat(rs.getInt("ORDINAL_POSITION")).isEqualTo(ordinal);
          assertThat(rs.getString("TABLE_NAME")).isEqualTo("people");
          types.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
        }
      }
      assertThat(types.keySet()).containsExactly("id", "name", "active", "score", "created");
      assertThat(types.get("id")).isEqualTo(Types.BIGINT);
      assertThat(types.get("name")).isEqualTo(Types.VARCHAR);
      assertThat(types.get("active")).isEqualTo(Types.BOOLEAN);
      assertThat(types.get("score")).isEqualTo(Types.DOUBLE);
      assertThat(types.get("created")).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
    }
  }

  @Test
  void getColumnsMatchesTypesFixture() throws SQLException {
    try (Connection conn = connect()) {
      Map<String, Integer> types = new LinkedHashMap<>();
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "types", null)) {
        while (rs.next()) {
          types.put(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"));
        }
      }
      assertThat(types).containsEntry("c_bool", Types.BOOLEAN);
      assertThat(types).containsEntry("c_int8", Types.TINYINT);
      assertThat(types).containsEntry("c_int16", Types.SMALLINT);
      assertThat(types).containsEntry("c_int32", Types.INTEGER);
      assertThat(types).containsEntry("c_int64", Types.BIGINT);
      assertThat(types).containsEntry("c_float32", Types.REAL);
      assertThat(types).containsEntry("c_float64", Types.DOUBLE);
      assertThat(types).containsEntry("c_decimal", Types.DECIMAL);
      assertThat(types).containsEntry("c_utf8", Types.VARCHAR);
      assertThat(types).containsEntry("c_binary", Types.VARBINARY);
      assertThat(types).containsEntry("c_date", Types.DATE);
      assertThat(types).containsEntry("c_time", Types.TIME);
      assertThat(types).containsEntry("c_ts", Types.TIMESTAMP);
      assertThat(types).containsEntry("c_tstz", Types.TIMESTAMP_WITH_TIMEZONE);
    }
  }

  @Test
  void getColumnsForUnknownTableIsEmpty() throws SQLException {
    try (Connection conn = connect()) {
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "does_not_exist", null)) {
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void emptyMetadataResultSetsNeverThrow() throws SQLException {
    try (Connection conn = connect()) {
      DatabaseMetaData md = conn.getMetaData();
      try (ResultSet rs = md.getPrimaryKeys(null, null, "people")) {
        assertThat(rs.next()).isFalse();
      }
      try (ResultSet rs = md.getIndexInfo(null, null, "people", false, true)) {
        assertThat(rs.next()).isFalse();
      }
    }
  }
}
