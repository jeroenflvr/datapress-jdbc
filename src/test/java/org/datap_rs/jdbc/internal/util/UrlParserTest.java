package org.datap_rs.jdbc.internal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UrlParserTest {

  @Nested
  class AcceptsUrl {

    @Test
    void acceptsDataPressUrls() {
      assertThat(UrlParser.acceptsUrl("jdbc:datapress://localhost:8000/")).isTrue();
      assertThat(UrlParser.acceptsUrl("jdbc:datapress:https://h/")).isTrue();
    }

    @Test
    void rejectsForeignAndNullUrls() {
      assertThat(UrlParser.acceptsUrl(null)).isFalse();
      assertThat(UrlParser.acceptsUrl("jdbc:postgresql://localhost/db")).isFalse();
      assertThat(UrlParser.acceptsUrl("jdbc:mysql://localhost/db")).isFalse();
      assertThat(UrlParser.acceptsUrl("datapress://localhost")).isFalse();
      assertThat(UrlParser.acceptsUrl("")).isFalse();
    }
  }

  @Nested
  class HostPortTls {

    @Test
    void parsesHostAndExplicitPort() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://example.com:9000/", null);
      assertThat(cfg.host()).isEqualTo("example.com");
      assertThat(cfg.port()).isEqualTo(9000);
      assertThat(cfg.tls()).isFalse();
      assertThat(cfg.baseUri()).hasToString("http://example.com:9000");
    }

    @Test
    void appliesDefaultPortWhenOmitted() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://example.com/", null);
      assertThat(cfg.port()).isEqualTo(ConnectionConfig.DEFAULT_PORT);
    }

    @Test
    void httpsSchemeImpliesTls() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress:https://example.com:8443/", null);
      assertThat(cfg.tls()).isTrue();
      assertThat(cfg.baseUri()).hasToString("https://example.com:8443");
    }

    @Test
    void ignoresPathSegment() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/ignored/path?tls=true", null);
      assertThat(cfg.host()).isEqualTo("h");
      assertThat(cfg.port()).isEqualTo(8000);
      assertThat(cfg.tls()).isTrue();
    }

    @Test
    void tlsQueryParamOverridesHttpScheme() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?tls=true", null);
      assertThat(cfg.tls()).isTrue();
    }

    @Test
    void propertiesTlsOverridesQuery() throws SQLException {
      Properties props = new Properties();
      props.setProperty("tls", "false");
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?tls=true", props);
      assertThat(cfg.tls()).isFalse();
    }
  }

  @Nested
  class TokenResolution {

    @Test
    void tokenFromUrlQuery() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?token=abc", null);
      assertThat(cfg.token()).isEqualTo("abc");
    }

    @Test
    void tokenFromProperties() throws SQLException {
      Properties props = new Properties();
      props.setProperty("token", "fromProps");
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/", props);
      assertThat(cfg.token()).isEqualTo("fromProps");
    }

    @Test
    void passwordIsAcceptedAsTokenAlias() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?password=secret", null);
      assertThat(cfg.token()).isEqualTo("secret");
    }

    @Test
    void propertiesWinOverUrl() throws SQLException {
      Properties props = new Properties();
      props.setProperty("token", "winner");
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?token=loser", props);
      assertThat(cfg.token()).isEqualTo("winner");
    }

    @Test
    void tokenPreferredOverPasswordInSameSource() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?password=pw&token=tk", null);
      assertThat(cfg.token()).isEqualTo("tk");
    }

    @Test
    void userIsIgnoredNotAnError() throws SQLException {
      Properties props = new Properties();
      props.setProperty("user", "alice");
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/", props);
      assertThat(cfg.token()).isNull();
    }

    @Test
    void urlEncodedTokenIsDecoded() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h:8000/?token=a%20b%2Bc", null);
      assertThat(cfg.token()).isEqualTo("a b+c");
    }
  }

  @Nested
  class TimeoutsAndMaxRows {

    @Test
    void defaultsApply() throws SQLException {
      ConnectionConfig cfg = UrlParser.parse("jdbc:datapress://h/", null);
      assertThat(cfg.connectTimeoutMs()).isEqualTo(ConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MS);
      assertThat(cfg.socketTimeoutMs()).isEqualTo(ConnectionConfig.DEFAULT_SOCKET_TIMEOUT_MS);
      assertThat(cfg.maxRows()).isZero();
      assertThat(cfg.applicationName()).isNull();
    }

    @Test
    void parsesTimeoutsMaxRowsAndAppName() throws SQLException {
      ConnectionConfig cfg =
          UrlParser.parse(
              "jdbc:datapress://h/?connectTimeout=1234&socketTimeout=5678&maxRows=42&applicationName=dbeaver",
              null);
      assertThat(cfg.connectTimeoutMs()).isEqualTo(1234);
      assertThat(cfg.socketTimeoutMs()).isEqualTo(5678);
      assertThat(cfg.maxRows()).isEqualTo(42);
      assertThat(cfg.applicationName()).isEqualTo("dbeaver");
      assertThat(cfg.userAgent()).startsWith("datapress-jdbc/").contains("(dbeaver)");
    }
  }

  @Nested
  class Malformed {

    @Test
    void rejectsForeignUrl() {
      assertThatThrownBy(() -> UrlParser.parse("jdbc:postgresql://h/", null))
          .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsMissingHost() {
      assertThatThrownBy(() -> UrlParser.parse("jdbc:datapress://:8000/", null))
          .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsInvalidPort() {
      assertThatThrownBy(() -> UrlParser.parse("jdbc:datapress://h:notaport/", null))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> UrlParser.parse("jdbc:datapress://h:99999/", null))
          .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsInvalidBoolean() {
      assertThatThrownBy(() -> UrlParser.parse("jdbc:datapress://h/?tls=maybe", null))
          .isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsNegativeTimeout() {
      assertThatThrownBy(() -> UrlParser.parse("jdbc:datapress://h/?connectTimeout=-1", null))
          .isInstanceOf(SQLException.class);
    }
  }
}
