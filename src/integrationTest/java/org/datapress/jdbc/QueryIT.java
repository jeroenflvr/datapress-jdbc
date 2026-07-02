package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = IntegrationTestBase.URL_ENV, matches = ".+")
class QueryIT extends IntegrationTestBase {

  /** DataFusion returns strings dictionary-encoded; this exercises the dictionary decode path. */
  @Test
  void readsPeopleIncludingDictionaryStrings() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT id, name, active, score, created FROM people ORDER BY id")) {

      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong("id")).isEqualTo(1L);
      assertThat(rs.getString("name")).isEqualTo("alice");
      assertThat(rs.getBoolean("active")).isTrue();
      assertThat(rs.getDouble("score")).isEqualTo(1.5d);
      assertThat(rs.getTimestamp("created")).isNotNull();
      assertThat(rs.wasNull()).isFalse();

      assertThat(rs.next()).isTrue(); // bob
      assertThat(rs.getString("name")).isEqualTo("bob");

      assertThat(rs.next()).isTrue(); // carol, score NULL
      assertThat(rs.getString("name")).isEqualTo("carol");
      assertThat(rs.getDouble("score")).isEqualTo(0.0d);
      assertThat(rs.wasNull()).isTrue();

      assertThat(rs.next()).isTrue(); // name NULL
      assertThat(rs.getString("name")).isNull();
      assertThat(rs.wasNull()).isTrue();

      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void readsEveryFixtureType() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT * FROM types ORDER BY c_int32 ASC NULLS LAST")) {

      assertThat(rs.next()).isTrue(); // populated row
      assertThat(rs.getBoolean("c_bool")).isTrue();
      assertThat(rs.getByte("c_int8")).isEqualTo((byte) 7);
      assertThat(rs.getShort("c_int16")).isEqualTo((short) 300);
      assertThat(rs.getInt("c_int32")).isEqualTo(100_000);
      assertThat(rs.getLong("c_int64")).isEqualTo(10_000_000_000L);
      assertThat(rs.getFloat("c_float32")).isEqualTo(1.5f);
      assertThat(rs.getDouble("c_float64")).isEqualTo(2.5d);
      assertThat(rs.getBigDecimal("c_decimal")).isEqualByComparingTo(new BigDecimal("123.45"));
      assertThat(rs.getString("c_utf8")).isEqualTo("hello");
      assertThat(rs.getBytes("c_binary")).containsExactly(1, 2, 3);
      assertThat(rs.getString("c_date")).isEqualTo("2021-01-15");
      assertThat(rs.getString("c_time")).isEqualTo("12:30:15");
      assertThat(rs.getTimestamp("c_ts")).isNotNull();
      assertThat(rs.getObject("c_tstz")).isNotNull();

      assertThat(rs.next()).isTrue(); // all-NULL row
      assertThat(rs.getInt("c_int32")).isEqualTo(0);
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getString("c_utf8")).isNull();
      assertThat(rs.wasNull()).isTrue();

      assertThat(rs.next()).isFalse();
    }
  }

  @Test
  void resultSetMetadataReflectsServerSchema() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT id, name FROM people")) {
      assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
      assertThat(rs.getMetaData().getColumnType(1)).isEqualTo(Types.BIGINT);
      assertThat(rs.getMetaData().getColumnType(2)).isEqualTo(Types.VARCHAR);
      assertThat(rs.getMetaData().getColumnLabel(2)).isEqualTo("name");
    }
  }

  @Test
  void describeReturnsColumnDescription() throws SQLException {
    try (Connection conn = connect();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("DESCRIBE people")) {
      assertThat(rs.getMetaData().getColumnCount()).isPositive();
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isNotBlank();
    }
  }
}
