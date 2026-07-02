package org.datapress.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.datapress.jdbc.internal.arrow.ColumnMeta;
import org.datapress.jdbc.internal.http.ServerVersion;
import org.datapress.jdbc.internal.meta.LikePattern;
import org.datapress.jdbc.internal.meta.SyntheticResultSet;
import org.datapress.jdbc.internal.util.VersionInfo;

/**
 * Read-only {@link DatabaseMetaData} for DataPress. Tables and columns are discovered from the REST
 * dataset endpoints; a single fixed catalog ({@code datapress}) and schema ({@code main}) are
 * exposed. Everything the driver does not support (keys, indexes, procedures, privileges, …)
 * returns a spec-correct <em>empty</em> result set rather than throwing. Capability flags report
 * the conservative truth for a stateless, read-only query engine.
 */
final class DataPressDatabaseMetaData implements DatabaseMetaData {

  private static final String PRODUCT_NAME = "DataPress";
  private static final String DRIVER_NAME = "DataPress JDBC Driver";
  private static final String TABLE_TYPE = "TABLE";

  private final DataPressConnection connection;

  DataPressDatabaseMetaData(DataPressConnection connection) {
    this.connection = connection;
  }

  private ServerVersion server() {
    return connection.serverVersion();
  }

  // --- Identity / versions --------------------------------------------------------------------

  @Override
  public String getDatabaseProductName() {
    return PRODUCT_NAME;
  }

  @Override
  public String getDatabaseProductVersion() {
    return server().version();
  }

  @Override
  public int getDatabaseMajorVersion() {
    return versionComponent(server().version(), 0);
  }

  @Override
  public int getDatabaseMinorVersion() {
    return versionComponent(server().version(), 1);
  }

  @Override
  public String getDriverName() {
    return DRIVER_NAME;
  }

  @Override
  public String getDriverVersion() {
    return VersionInfo.driverVersion();
  }

  @Override
  public int getDriverMajorVersion() {
    return VersionInfo.driverMajorVersion();
  }

  @Override
  public int getDriverMinorVersion() {
    return VersionInfo.driverMinorVersion();
  }

  @Override
  public int getJDBCMajorVersion() {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() {
    return 2;
  }

  @Override
  public String getURL() {
    return "jdbc:datapress://"
        + connection.config().host()
        + ":"
        + connection.config().port()
        + "/";
  }

  @Override
  public String getUserName() {
    return "";
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private static int versionComponent(String version, int index) {
    if (version == null) {
      return 0;
    }
    String[] parts = version.split("[.+-]");
    if (index >= parts.length) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // --- getTables ------------------------------------------------------------------------------

  @Override
  public ResultSet getTables(
      String catalog, String schemaPattern, String tableNamePattern, String[] types)
      throws SQLException {
    SyntheticResultSet.Builder rs =
        SyntheticResultSet.builder()
            .column("TABLE_CAT", Types.VARCHAR)
            .column("TABLE_SCHEM", Types.VARCHAR)
            .column("TABLE_NAME", Types.VARCHAR)
            .column("TABLE_TYPE", Types.VARCHAR)
            .column("REMARKS", Types.VARCHAR)
            .column("TYPE_CAT", Types.VARCHAR)
            .column("TYPE_SCHEM", Types.VARCHAR)
            .column("TYPE_NAME", Types.VARCHAR)
            .column("SELF_REFERENCING_COL_NAME", Types.VARCHAR)
            .column("REF_GENERATION", Types.VARCHAR);

    if (!catalogMatches(catalog) || !schemaMatches(schemaPattern) || !typesIncludeTable(types)) {
      return rs.build();
    }

    LikePattern namePattern = LikePattern.compile(tableNamePattern);
    List<String> names = datasetNames();
    names.sort(String::compareTo);
    for (String name : names) {
      if (namePattern.matches(name)) {
        rs.row(
            DataPressConnection.CATALOG,
            DataPressConnection.SCHEMA,
            name,
            TABLE_TYPE,
            null,
            null,
            null,
            null,
            null,
            null);
      }
    }
    return rs.build();
  }

  // --- getColumns -----------------------------------------------------------------------------

  @Override
  public ResultSet getColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    SyntheticResultSet.Builder rs =
        SyntheticResultSet.builder()
            .column("TABLE_CAT", Types.VARCHAR)
            .column("TABLE_SCHEM", Types.VARCHAR)
            .column("TABLE_NAME", Types.VARCHAR)
            .column("COLUMN_NAME", Types.VARCHAR)
            .column("DATA_TYPE", Types.INTEGER)
            .column("TYPE_NAME", Types.VARCHAR)
            .column("COLUMN_SIZE", Types.INTEGER)
            .column("BUFFER_LENGTH", Types.INTEGER)
            .column("DECIMAL_DIGITS", Types.INTEGER)
            .column("NUM_PREC_RADIX", Types.INTEGER)
            .column("NULLABLE", Types.INTEGER)
            .column("REMARKS", Types.VARCHAR)
            .column("COLUMN_DEF", Types.VARCHAR)
            .column("SQL_DATA_TYPE", Types.INTEGER)
            .column("SQL_DATETIME_SUB", Types.INTEGER)
            .column("CHAR_OCTET_LENGTH", Types.INTEGER)
            .column("ORDINAL_POSITION", Types.INTEGER)
            .column("IS_NULLABLE", Types.VARCHAR)
            .column("SCOPE_CATALOG", Types.VARCHAR)
            .column("SCOPE_SCHEMA", Types.VARCHAR)
            .column("SCOPE_TABLE", Types.VARCHAR)
            .column("SOURCE_DATA_TYPE", Types.SMALLINT)
            .column("IS_AUTOINCREMENT", Types.VARCHAR)
            .column("IS_GENERATEDCOLUMN", Types.VARCHAR);

    if (!catalogMatches(catalog) || !schemaMatches(schemaPattern)) {
      return rs.build();
    }

    LikePattern tablePattern = LikePattern.compile(tableNamePattern);
    LikePattern columnPattern = LikePattern.compile(columnNamePattern);

    List<String> names = datasetNames();
    names.sort(String::compareTo);
    for (String table : names) {
      if (!tablePattern.matches(table)) {
        continue;
      }
      JsonNode schema = connection.http().getDatasetSchema(table);
      JsonNode columns = schema.get("columns");
      if (columns == null || !columns.isArray()) {
        continue;
      }
      int ordinal = 0;
      for (JsonNode column : columns) {
        ordinal++;
        String columnName = column.path("name").asText();
        if (!columnPattern.matches(columnName)) {
          continue;
        }
        String sqlType = column.path("sql_type").asText();
        boolean nullable = column.path("nullable").asBoolean(true);
        ColumnMeta meta =
            org.datapress.jdbc.internal.arrow.TypeMapping.ofServerType(
                columnName, sqlType, nullable);
        int jdbcType = meta.sqlType();
        rs.row(
            DataPressConnection.CATALOG,
            DataPressConnection.SCHEMA,
            table,
            columnName,
            jdbcType,
            meta.typeName(),
            columnSize(meta),
            null, // BUFFER_LENGTH (unused)
            decimalDigits(meta),
            numPrecRadix(jdbcType),
            meta.nullable(),
            null, // REMARKS
            null, // COLUMN_DEF
            null, // SQL_DATA_TYPE
            null, // SQL_DATETIME_SUB
            charOctetLength(meta),
            ordinal,
            meta.nullable() == java.sql.ResultSetMetaData.columnNoNulls ? "NO" : "YES",
            null, // SCOPE_CATALOG
            null, // SCOPE_SCHEMA
            null, // SCOPE_TABLE
            null, // SOURCE_DATA_TYPE
            "NO", // IS_AUTOINCREMENT
            "NO"); // IS_GENERATEDCOLUMN
      }
    }
    return rs.build();
  }

  private static Integer columnSize(ColumnMeta meta) {
    int p = meta.precision();
    if (p > 0) {
      return p;
    }
    // Character/binary columns without a declared precision: report the display size.
    return meta.displaySize() > 0 ? meta.displaySize() : null;
  }

  private static Integer decimalDigits(ColumnMeta meta) {
    switch (meta.sqlType()) {
      case Types.DECIMAL:
      case Types.NUMERIC:
      case Types.TIME:
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return meta.scale();
      default:
        return null;
    }
  }

  private static Integer numPrecRadix(int jdbcType) {
    switch (jdbcType) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.REAL:
      case Types.DOUBLE:
      case Types.FLOAT:
      case Types.DECIMAL:
      case Types.NUMERIC:
        return 10;
      default:
        return null;
    }
  }

  private static Integer charOctetLength(ColumnMeta meta) {
    switch (meta.sqlType()) {
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
      case Types.CHAR:
        return meta.displaySize() > 0 ? meta.displaySize() : null;
      default:
        return null;
    }
  }

  private List<String> datasetNames() throws SQLException {
    JsonNode body = connection.http().getDatasets();
    JsonNode datasets = body.get("datasets");
    List<String> names = new ArrayList<>();
    if (datasets != null && datasets.isArray()) {
      for (JsonNode dataset : datasets) {
        JsonNode name = dataset.get("name");
        if (name != null && !name.isNull()) {
          names.add(name.asText());
        }
      }
    }
    return names;
  }

  private static boolean catalogMatches(String catalog) {
    // null or "" (metadata "any") match; otherwise must equal the fixed catalog.
    return catalog == null || catalog.isEmpty() || catalog.equals(DataPressConnection.CATALOG);
  }

  private static boolean schemaMatches(String schemaPattern) {
    if (schemaPattern == null || schemaPattern.isEmpty()) {
      return true;
    }
    return LikePattern.compile(schemaPattern).matches(DataPressConnection.SCHEMA);
  }

  private static boolean typesIncludeTable(String[] types) {
    if (types == null) {
      return true;
    }
    for (String type : types) {
      if (TABLE_TYPE.equals(type)) {
        return true;
      }
    }
    return false;
  }

  // --- Catalogs / schemas / table types -------------------------------------------------------

  @Override
  public ResultSet getCatalogs() {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .row(DataPressConnection.CATALOG)
        .build();
  }

  @Override
  public ResultSet getSchemas() {
    return SyntheticResultSet.builder()
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_CATALOG", Types.VARCHAR)
        .row(DataPressConnection.SCHEMA, DataPressConnection.CATALOG)
        .build();
  }

  @Override
  public ResultSet getSchemas(String catalog, String schemaPattern) {
    SyntheticResultSet.Builder rs =
        SyntheticResultSet.builder()
            .column("TABLE_SCHEM", Types.VARCHAR)
            .column("TABLE_CATALOG", Types.VARCHAR);
    if (catalogMatches(catalog) && schemaMatches(schemaPattern)) {
      rs.row(DataPressConnection.SCHEMA, DataPressConnection.CATALOG);
    }
    return rs.build();
  }

  @Override
  public ResultSet getTableTypes() {
    return SyntheticResultSet.builder().column("TABLE_TYPE", Types.VARCHAR).row(TABLE_TYPE).build();
  }

  // --- getTypeInfo ----------------------------------------------------------------------------

  @Override
  public ResultSet getTypeInfo() {
    SyntheticResultSet.Builder rs =
        SyntheticResultSet.builder()
            .column("TYPE_NAME", Types.VARCHAR)
            .column("DATA_TYPE", Types.INTEGER)
            .column("PRECISION", Types.INTEGER)
            .column("LITERAL_PREFIX", Types.VARCHAR)
            .column("LITERAL_SUFFIX", Types.VARCHAR)
            .column("CREATE_PARAMS", Types.VARCHAR)
            .column("NULLABLE", Types.SMALLINT)
            .column("CASE_SENSITIVE", Types.BOOLEAN)
            .column("SEARCHABLE", Types.SMALLINT)
            .column("UNSIGNED_ATTRIBUTE", Types.BOOLEAN)
            .column("FIXED_PREC_SCALE", Types.BOOLEAN)
            .column("AUTO_INCREMENT", Types.BOOLEAN)
            .column("LOCAL_TYPE_NAME", Types.VARCHAR)
            .column("MINIMUM_SCALE", Types.SMALLINT)
            .column("MAXIMUM_SCALE", Types.SMALLINT)
            .column("SQL_DATA_TYPE", Types.INTEGER)
            .column("SQL_DATETIME_SUB", Types.INTEGER)
            .column("NUM_PREC_RADIX", Types.INTEGER);

    addType(rs, "BOOLEAN", Types.BOOLEAN, 1, null, null, null, false, false, (short) 0, (short) 0);
    addType(rs, "TINYINT", Types.TINYINT, 3, null, null, null, false, true, (short) 0, (short) 0);
    addType(rs, "SMALLINT", Types.SMALLINT, 5, null, null, null, false, true, (short) 0, (short) 0);
    addType(rs, "INTEGER", Types.INTEGER, 10, null, null, null, false, true, (short) 0, (short) 0);
    addType(rs, "BIGINT", Types.BIGINT, 19, null, null, null, false, true, (short) 0, (short) 0);
    addType(rs, "REAL", Types.REAL, 7, null, null, null, false, true, (short) 0, (short) 0);
    addType(rs, "DOUBLE", Types.DOUBLE, 15, null, null, null, false, true, (short) 0, (short) 0);
    addType(
        rs,
        "DECIMAL",
        Types.DECIMAL,
        38,
        null,
        null,
        "precision,scale",
        false,
        true,
        (short) 0,
        (short) 38);
    addType(rs, "VARCHAR", Types.VARCHAR, 65535, "'", "'", null, true, false, (short) 0, (short) 0);
    addType(
        rs,
        "LONGVARCHAR",
        Types.LONGVARCHAR,
        Integer.MAX_VALUE,
        "'",
        "'",
        null,
        true,
        false,
        (short) 0,
        (short) 0);
    addType(
        rs, "BINARY", Types.BINARY, 65535, null, null, null, false, false, (short) 0, (short) 0);
    addType(
        rs,
        "VARBINARY",
        Types.VARBINARY,
        65535,
        null,
        null,
        null,
        false,
        false,
        (short) 0,
        (short) 0);
    addType(
        rs,
        "LONGVARBINARY",
        Types.LONGVARBINARY,
        Integer.MAX_VALUE,
        null,
        null,
        null,
        false,
        false,
        (short) 0,
        (short) 0);
    addType(rs, "DATE", Types.DATE, 10, "DATE '", "'", null, false, false, (short) 0, (short) 0);
    addType(rs, "TIME", Types.TIME, 18, "TIME '", "'", null, false, false, (short) 0, (short) 9);
    addType(
        rs,
        "TIMESTAMP",
        Types.TIMESTAMP,
        29,
        "TIMESTAMP '",
        "'",
        null,
        false,
        false,
        (short) 0,
        (short) 9);
    addType(
        rs,
        "TIMESTAMP WITH TIME ZONE",
        Types.TIMESTAMP_WITH_TIMEZONE,
        35,
        "TIMESTAMP '",
        "'",
        null,
        false,
        false,
        (short) 0,
        (short) 9);
    return rs.build();
  }

  private static void addType(
      SyntheticResultSet.Builder rs,
      String name,
      int dataType,
      int precision,
      String literalPrefix,
      String literalSuffix,
      String createParams,
      boolean caseSensitive,
      boolean signed,
      short minScale,
      short maxScale) {
    rs.row(
        name,
        dataType,
        precision,
        literalPrefix,
        literalSuffix,
        createParams,
        (short) typeNullable,
        caseSensitive,
        (short) typeSearchable,
        !signed, // UNSIGNED_ATTRIBUTE
        false, // FIXED_PREC_SCALE
        false, // AUTO_INCREMENT
        null, // LOCAL_TYPE_NAME
        minScale,
        maxScale,
        null, // SQL_DATA_TYPE
        null, // SQL_DATETIME_SUB
        10); // NUM_PREC_RADIX
  }

  // --- Empty result sets (spec-correct columns; never throw) ----------------------------------

  @Override
  public ResultSet getProcedures(
      String catalog, String schemaPattern, String procedureNamePattern) {
    return SyntheticResultSet.builder()
        .column("PROCEDURE_CAT", Types.VARCHAR)
        .column("PROCEDURE_SCHEM", Types.VARCHAR)
        .column("PROCEDURE_NAME", Types.VARCHAR)
        .column("RESERVED1", Types.VARCHAR)
        .column("RESERVED2", Types.VARCHAR)
        .column("RESERVED3", Types.VARCHAR)
        .column("REMARKS", Types.VARCHAR)
        .column("PROCEDURE_TYPE", Types.SMALLINT)
        .column("SPECIFIC_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getProcedureColumns(
      String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) {
    return SyntheticResultSet.builder()
        .column("PROCEDURE_CAT", Types.VARCHAR)
        .column("PROCEDURE_SCHEM", Types.VARCHAR)
        .column("PROCEDURE_NAME", Types.VARCHAR)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("COLUMN_TYPE", Types.SMALLINT)
        .column("DATA_TYPE", Types.INTEGER)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("PRECISION", Types.INTEGER)
        .column("LENGTH", Types.INTEGER)
        .column("SCALE", Types.SMALLINT)
        .column("RADIX", Types.SMALLINT)
        .column("NULLABLE", Types.SMALLINT)
        .column("REMARKS", Types.VARCHAR)
        .column("COLUMN_DEF", Types.VARCHAR)
        .column("SQL_DATA_TYPE", Types.INTEGER)
        .column("SQL_DATETIME_SUB", Types.INTEGER)
        .column("CHAR_OCTET_LENGTH", Types.INTEGER)
        .column("ORDINAL_POSITION", Types.INTEGER)
        .column("IS_NULLABLE", Types.VARCHAR)
        .column("SPECIFIC_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getColumnPrivileges(
      String catalog, String schema, String table, String columnNamePattern) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("GRANTOR", Types.VARCHAR)
        .column("GRANTEE", Types.VARCHAR)
        .column("PRIVILEGE", Types.VARCHAR)
        .column("IS_GRANTABLE", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getTablePrivileges(
      String catalog, String schemaPattern, String tableNamePattern) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("GRANTOR", Types.VARCHAR)
        .column("GRANTEE", Types.VARCHAR)
        .column("PRIVILEGE", Types.VARCHAR)
        .column("IS_GRANTABLE", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getBestRowIdentifier(
      String catalog, String schema, String table, int scope, boolean nullable) {
    return rowIdentifierResultSet();
  }

  @Override
  public ResultSet getVersionColumns(String catalog, String schema, String table) {
    return rowIdentifierResultSet();
  }

  private static ResultSet rowIdentifierResultSet() {
    return SyntheticResultSet.builder()
        .column("SCOPE", Types.SMALLINT)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("DATA_TYPE", Types.INTEGER)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("COLUMN_SIZE", Types.INTEGER)
        .column("BUFFER_LENGTH", Types.INTEGER)
        .column("DECIMAL_DIGITS", Types.SMALLINT)
        .column("PSEUDO_COLUMN", Types.SMALLINT)
        .build();
  }

  @Override
  public ResultSet getPrimaryKeys(String catalog, String schema, String table) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("KEY_SEQ", Types.SMALLINT)
        .column("PK_NAME", Types.VARCHAR)
        .build();
  }

  private static ResultSet keyResultSet() {
    return SyntheticResultSet.builder()
        .column("PKTABLE_CAT", Types.VARCHAR)
        .column("PKTABLE_SCHEM", Types.VARCHAR)
        .column("PKTABLE_NAME", Types.VARCHAR)
        .column("PKCOLUMN_NAME", Types.VARCHAR)
        .column("FKTABLE_CAT", Types.VARCHAR)
        .column("FKTABLE_SCHEM", Types.VARCHAR)
        .column("FKTABLE_NAME", Types.VARCHAR)
        .column("FKCOLUMN_NAME", Types.VARCHAR)
        .column("KEY_SEQ", Types.SMALLINT)
        .column("UPDATE_RULE", Types.SMALLINT)
        .column("DELETE_RULE", Types.SMALLINT)
        .column("FK_NAME", Types.VARCHAR)
        .column("PK_NAME", Types.VARCHAR)
        .column("DEFERRABILITY", Types.SMALLINT)
        .build();
  }

  @Override
  public ResultSet getImportedKeys(String catalog, String schema, String table) {
    return keyResultSet();
  }

  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table) {
    return keyResultSet();
  }

  @Override
  public ResultSet getCrossReference(
      String parentCatalog,
      String parentSchema,
      String parentTable,
      String foreignCatalog,
      String foreignSchema,
      String foreignTable) {
    return keyResultSet();
  }

  @Override
  public ResultSet getIndexInfo(
      String catalog, String schema, String table, boolean unique, boolean approximate) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("NON_UNIQUE", Types.BOOLEAN)
        .column("INDEX_QUALIFIER", Types.VARCHAR)
        .column("INDEX_NAME", Types.VARCHAR)
        .column("TYPE", Types.SMALLINT)
        .column("ORDINAL_POSITION", Types.SMALLINT)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("ASC_OR_DESC", Types.VARCHAR)
        .column("CARDINALITY", Types.BIGINT)
        .column("PAGES", Types.BIGINT)
        .column("FILTER_CONDITION", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getUDTs(
      String catalog, String schemaPattern, String typeNamePattern, int[] types) {
    return SyntheticResultSet.builder()
        .column("TYPE_CAT", Types.VARCHAR)
        .column("TYPE_SCHEM", Types.VARCHAR)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("CLASS_NAME", Types.VARCHAR)
        .column("DATA_TYPE", Types.INTEGER)
        .column("REMARKS", Types.VARCHAR)
        .column("BASE_TYPE", Types.SMALLINT)
        .build();
  }

  @Override
  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) {
    return SyntheticResultSet.builder()
        .column("TYPE_CAT", Types.VARCHAR)
        .column("TYPE_SCHEM", Types.VARCHAR)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("SUPERTYPE_CAT", Types.VARCHAR)
        .column("SUPERTYPE_SCHEM", Types.VARCHAR)
        .column("SUPERTYPE_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("SUPERTABLE_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getAttributes(
      String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) {
    return SyntheticResultSet.builder()
        .column("TYPE_CAT", Types.VARCHAR)
        .column("TYPE_SCHEM", Types.VARCHAR)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("ATTR_NAME", Types.VARCHAR)
        .column("DATA_TYPE", Types.INTEGER)
        .column("ATTR_TYPE_NAME", Types.VARCHAR)
        .column("ATTR_SIZE", Types.INTEGER)
        .column("DECIMAL_DIGITS", Types.INTEGER)
        .column("NUM_PREC_RADIX", Types.INTEGER)
        .column("NULLABLE", Types.INTEGER)
        .column("REMARKS", Types.VARCHAR)
        .column("ATTR_DEF", Types.VARCHAR)
        .column("SQL_DATA_TYPE", Types.INTEGER)
        .column("SQL_DATETIME_SUB", Types.INTEGER)
        .column("CHAR_OCTET_LENGTH", Types.INTEGER)
        .column("ORDINAL_POSITION", Types.INTEGER)
        .column("IS_NULLABLE", Types.VARCHAR)
        .column("SCOPE_CATALOG", Types.VARCHAR)
        .column("SCOPE_SCHEMA", Types.VARCHAR)
        .column("SCOPE_TABLE", Types.VARCHAR)
        .column("SOURCE_DATA_TYPE", Types.SMALLINT)
        .build();
  }

  @Override
  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
    return SyntheticResultSet.builder()
        .column("FUNCTION_CAT", Types.VARCHAR)
        .column("FUNCTION_SCHEM", Types.VARCHAR)
        .column("FUNCTION_NAME", Types.VARCHAR)
        .column("REMARKS", Types.VARCHAR)
        .column("FUNCTION_TYPE", Types.SMALLINT)
        .column("SPECIFIC_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getFunctionColumns(
      String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) {
    return SyntheticResultSet.builder()
        .column("FUNCTION_CAT", Types.VARCHAR)
        .column("FUNCTION_SCHEM", Types.VARCHAR)
        .column("FUNCTION_NAME", Types.VARCHAR)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("COLUMN_TYPE", Types.SMALLINT)
        .column("DATA_TYPE", Types.INTEGER)
        .column("TYPE_NAME", Types.VARCHAR)
        .column("PRECISION", Types.INTEGER)
        .column("LENGTH", Types.INTEGER)
        .column("SCALE", Types.SMALLINT)
        .column("RADIX", Types.SMALLINT)
        .column("NULLABLE", Types.SMALLINT)
        .column("REMARKS", Types.VARCHAR)
        .column("CHAR_OCTET_LENGTH", Types.INTEGER)
        .column("ORDINAL_POSITION", Types.INTEGER)
        .column("IS_NULLABLE", Types.VARCHAR)
        .column("SPECIFIC_NAME", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getPseudoColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) {
    return SyntheticResultSet.builder()
        .column("TABLE_CAT", Types.VARCHAR)
        .column("TABLE_SCHEM", Types.VARCHAR)
        .column("TABLE_NAME", Types.VARCHAR)
        .column("COLUMN_NAME", Types.VARCHAR)
        .column("DATA_TYPE", Types.INTEGER)
        .column("COLUMN_SIZE", Types.INTEGER)
        .column("DECIMAL_DIGITS", Types.INTEGER)
        .column("NUM_PREC_RADIX", Types.INTEGER)
        .column("COLUMN_USAGE", Types.VARCHAR)
        .column("REMARKS", Types.VARCHAR)
        .column("CHAR_OCTET_LENGTH", Types.INTEGER)
        .column("IS_NULLABLE", Types.VARCHAR)
        .build();
  }

  @Override
  public ResultSet getClientInfoProperties() {
    return SyntheticResultSet.builder()
        .column("NAME", Types.VARCHAR)
        .column("MAX_LEN", Types.INTEGER)
        .column("DEFAULT_VALUE", Types.VARCHAR)
        .column("DESCRIPTION", Types.VARCHAR)
        .build();
  }

  // --- Terms / quoting ------------------------------------------------------------------------

  @Override
  public String getIdentifierQuoteString() {
    return "\"";
  }

  @Override
  public String getSearchStringEscape() {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() {
    return "";
  }

  @Override
  public String getCatalogTerm() {
    return "catalog";
  }

  @Override
  public String getSchemaTerm() {
    return "schema";
  }

  @Override
  public String getProcedureTerm() {
    return "procedure";
  }

  @Override
  public String getCatalogSeparator() {
    return ".";
  }

  @Override
  public boolean isCatalogAtStart() {
    return true;
  }

  @Override
  public String getSQLKeywords() {
    return "";
  }

  @Override
  public String getNumericFunctions() {
    return "ABS,CEIL,FLOOR,ROUND,SQRT,EXP,LN,LOG,POWER,MOD";
  }

  @Override
  public String getStringFunctions() {
    return "CONCAT,LENGTH,LOWER,UPPER,SUBSTRING,TRIM,REPLACE";
  }

  @Override
  public String getSystemFunctions() {
    return "";
  }

  @Override
  public String getTimeDateFunctions() {
    return "NOW,CURRENT_DATE,CURRENT_TIME,CURRENT_TIMESTAMP,DATE_PART,DATE_TRUNC";
  }

  @Override
  public int getSQLStateType() {
    return DatabaseMetaData.sqlStateSQL;
  }

  // --- Capability flags: conservative truth ---------------------------------------------------

  @Override
  public boolean allProceduresAreCallable() {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() {
    return true;
  }

  @Override
  public boolean nullsAreSortedHigh() {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtStart() {
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() {
    return false;
  }

  @Override
  public boolean usesLocalFiles() {
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() {
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() {
    return false;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() {
    return false;
  }

  @Override
  public boolean supportsColumnAliasing() {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() {
    return true;
  }

  @Override
  public boolean supportsConvert() {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() {
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupBy() {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() {
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() {
    return true;
  }

  @Override
  public boolean supportsMultipleResultSets() {
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() {
    return false;
  }

  @Override
  public boolean supportsNonNullableColumns() {
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() {
    return true;
  }

  @Override
  public boolean supportsCoreSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() {
    return true;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() {
    return true;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() {
    return true;
  }

  @Override
  public boolean supportsUnion() {
    return true;
  }

  @Override
  public boolean supportsUnionAll() {
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() {
    return false;
  }

  @Override
  public int getMaxBinaryLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() {
    return 0;
  }

  @Override
  public int getMaxColumnsInGroupBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() {
    return 0;
  }

  @Override
  public int getMaxConnections() {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() {
    return 0;
  }

  @Override
  public int getMaxIndexLength() {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() {
    return 0;
  }

  @Override
  public int getMaxRowSize() {
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() {
    return false;
  }

  @Override
  public int getMaxStatementLength() {
    return 0;
  }

  @Override
  public int getMaxStatements() {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() {
    return 0;
  }

  @Override
  public int getMaxTablesInSelect() {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() {
    return 0;
  }

  @Override
  public int getDefaultTransactionIsolation() {
    return Connection.TRANSACTION_NONE;
  }

  @Override
  public boolean supportsTransactions() {
    return false;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) {
    return level == Connection.TRANSACTION_NONE;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() {
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() {
    return false;
  }

  @Override
  public boolean supportsResultSetType(int type) {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) {
    return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) {
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() {
    return false;
  }

  @Override
  public boolean supportsSavepoints() {
    return false;
  }

  @Override
  public boolean supportsNamedParameters() {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() {
    return false;
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) {
    return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public boolean locatorsUpdateCopy() {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() {
    return false;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() {
    return false;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() {
    return false;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() {
    return false;
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
}
