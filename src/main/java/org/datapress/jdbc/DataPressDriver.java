package org.datapress.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import org.datapress.jdbc.internal.http.HttpApi;
import org.datapress.jdbc.internal.http.ServerVersion;
import org.datapress.jdbc.internal.util.ConnectionConfig;
import org.datapress.jdbc.internal.util.UrlParser;
import org.datapress.jdbc.internal.util.VersionInfo;

/**
 * JDBC {@link Driver} for DataPress. Accepts URLs of the form {@code
 * jdbc:datapress://host[:port][/][?prop=value&...]}.
 *
 * <p>Registered automatically via {@code META-INF/services/java.sql.Driver} (ServiceLoader) and via
 * the static initialiser below for older {@link DriverManager} code paths.
 */
public final class DataPressDriver implements Driver {

  static {
    try {
      DriverManager.registerDriver(new DataPressDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    // Per the JDBC contract, return null (not throw) for URLs this driver does not handle,
    // so DriverManager can try the next registered driver.
    if (!acceptsURL(url)) {
      return null;
    }
    ConnectionConfig config = UrlParser.parse(url, info);
    HttpApi http = new HttpApi(config);
    try {
      ServerVersion version = http.getVersion(); // fail fast on unreachable/unauthorized
      return new DataPressConnection(config, http, version);
    } catch (SQLException e) {
      http.close();
      throw e;
    }
  }

  @Override
  public boolean acceptsURL(String url) {
    return UrlParser.acceptsUrl(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return UrlParser.propertyInfo(url, info);
  }

  @Override
  public int getMajorVersion() {
    return VersionInfo.driverMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    return VersionInfo.driverMinorVersion();
  }

  @Override
  public boolean jdbcCompliant() {
    // A read-only, non-transactional analytics driver is not a fully JDBC-compliant driver.
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException(
        "java.util.logging is not used by the DataPress driver", "0A000");
  }
}
