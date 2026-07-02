package org.datapress.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DataPressDriverTest {

  private final DataPressDriver driver = new DataPressDriver();

  @Test
  void acceptsOnlyDataPressUrls() {
    assertThat(driver.acceptsURL("jdbc:datapress://localhost:8000/")).isTrue();
    assertThat(driver.acceptsURL("jdbc:postgresql://localhost/db")).isFalse();
    assertThat(driver.acceptsURL(null)).isFalse();
  }

  @Test
  void connectReturnsNullForForeignUrl() throws SQLException {
    assertThat(driver.connect("jdbc:postgresql://localhost/db", new Properties())).isNull();
  }

  @Test
  void registeredWithDriverManager() throws SQLException {
    Driver found = DriverManager.getDriver("jdbc:datapress://localhost:8000/");
    assertThat(found).isInstanceOf(DataPressDriver.class);
  }

  @Test
  void isNotJdbcCompliant() {
    assertThat(driver.jdbcCompliant()).isFalse();
  }

  @Test
  void getParentLoggerThrows() {
    assertThatThrownBy(driver::getParentLogger).isInstanceOf(SQLFeatureNotSupportedException.class);
  }

  @Test
  void propertyInfoListsRecognisedProperties() {
    DriverPropertyInfo[] info =
        driver.getPropertyInfo("jdbc:datapress://h:8000/?token=abc", new Properties());
    assertThat(Arrays.stream(info).map(p -> p.name))
        .contains(
            "token",
            "password",
            "user",
            "tls",
            "connectTimeout",
            "socketTimeout",
            "maxRows",
            "applicationName");
    DriverPropertyInfo token =
        Arrays.stream(info).filter(p -> p.name.equals("token")).findFirst().orElseThrow();
    assertThat(token.value).isEqualTo("abc");
    DriverPropertyInfo tls =
        Arrays.stream(info).filter(p -> p.name.equals("tls")).findFirst().orElseThrow();
    assertThat(tls.choices).containsExactly("true", "false");
  }
}
