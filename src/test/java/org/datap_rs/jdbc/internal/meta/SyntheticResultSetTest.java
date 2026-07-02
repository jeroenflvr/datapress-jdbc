package org.datap_rs.jdbc.internal.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;

class SyntheticResultSetTest {

  @Test
  void emptyResultSetExposesColumnMetadata() throws SQLException {
    SyntheticResultSet rs =
        SyntheticResultSet.builder()
            .column("TABLE_NAME", Types.VARCHAR)
            .column("KEY_SEQ", Types.SMALLINT)
            .build();

    ResultSetMetaData meta = rs.getMetaData();
    assertThat(meta.getColumnCount()).isEqualTo(2);
    assertThat(meta.getColumnName(1)).isEqualTo("TABLE_NAME");
    assertThat(meta.getColumnType(1)).isEqualTo(Types.VARCHAR);
    assertThat(meta.getColumnName(2)).isEqualTo("KEY_SEQ");
    assertThat(meta.getColumnType(2)).isEqualTo(Types.SMALLINT);
    assertThat(rs.next()).isFalse();
  }

  @Test
  void iteratesRowsAndReadsValuesByIndexAndLabel() throws SQLException {
    SyntheticResultSet rs =
        SyntheticResultSet.builder()
            .column("NAME", Types.VARCHAR)
            .column("SIZE", Types.INTEGER)
            .column("FLAG", Types.BOOLEAN)
            .row("people", 4, true)
            .row("numbers", 20000, false)
            .build();

    assertThat(rs.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
    assertThat(rs.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);

    assertThat(rs.next()).isTrue();
    assertThat(rs.getString(1)).isEqualTo("people");
    assertThat(rs.getInt("SIZE")).isEqualTo(4);
    assertThat(rs.getBoolean("FLAG")).isTrue();

    assertThat(rs.next()).isTrue();
    assertThat(rs.getString("NAME")).isEqualTo("numbers");
    assertThat(rs.getLong(2)).isEqualTo(20000L);
    assertThat(rs.getBoolean(3)).isFalse();

    assertThat(rs.next()).isFalse();
  }

  @Test
  void nullValuesReportWasNull() throws SQLException {
    SyntheticResultSet rs =
        SyntheticResultSet.builder()
            .column("A", Types.VARCHAR)
            .column("B", Types.INTEGER)
            .row(null, null)
            .build();

    assertThat(rs.next()).isTrue();
    assertThat(rs.getString(1)).isNull();
    assertThat(rs.wasNull()).isTrue();
    assertThat(rs.getInt(2)).isZero();
    assertThat(rs.wasNull()).isTrue();
  }

  @Test
  void findColumnIsCaseInsensitiveAndFailsForUnknown() throws SQLException {
    SyntheticResultSet rs =
        SyntheticResultSet.builder().column("TABLE_NAME", Types.VARCHAR).build();
    assertThat(rs.findColumn("table_name")).isEqualTo(1);
    assertThatThrownBy(() -> rs.findColumn("nope")).isInstanceOf(SQLException.class);
  }

  @Test
  void readingBeforeNextThrows() {
    SyntheticResultSet rs =
        SyntheticResultSet.builder().column("A", Types.VARCHAR).row("x").build();
    assertThatThrownBy(() -> rs.getString(1)).isInstanceOf(SQLException.class);
  }

  @Test
  void closeMakesOperationsFail() throws SQLException {
    SyntheticResultSet rs =
        SyntheticResultSet.builder().column("A", Types.VARCHAR).row("x").build();
    rs.close();
    assertThat(rs.isClosed()).isTrue();
    assertThatThrownBy(rs::next).isInstanceOf(SQLException.class);
  }

  @Test
  void rowLengthMustMatchColumnCount() {
    assertThatThrownBy(() -> SyntheticResultSet.builder().column("A", Types.VARCHAR).row("x", "y"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
