package org.datapress.jdbc.internal.arrow;

/**
 * Immutable JDBC-facing description of one result column, derived from an Arrow {@code Field} by
 * {@link TypeMapping}. Feeds {@code DataPressResultSetMetaData}.
 */
public final class ColumnMeta {

  private final String name;
  private final int sqlType; // java.sql.Types
  private final String typeName; // SQL type name, e.g. "VARCHAR"
  private final String className; // getObject() runtime class name
  private final int precision;
  private final int scale;
  private final int displaySize;
  private final boolean signed;
  private final boolean caseSensitive;
  private final int nullable; // java.sql.ResultSetMetaData.columnNullable / columnNoNulls

  ColumnMeta(
      String name,
      int sqlType,
      String typeName,
      String className,
      int precision,
      int scale,
      int displaySize,
      boolean signed,
      boolean caseSensitive,
      int nullable) {
    this.name = name;
    this.sqlType = sqlType;
    this.typeName = typeName;
    this.className = className;
    this.precision = precision;
    this.scale = scale;
    this.displaySize = displaySize;
    this.signed = signed;
    this.caseSensitive = caseSensitive;
    this.nullable = nullable;
  }

  public String name() {
    return name;
  }

  public int sqlType() {
    return sqlType;
  }

  public String typeName() {
    return typeName;
  }

  public String className() {
    return className;
  }

  public int precision() {
    return precision;
  }

  public int scale() {
    return scale;
  }

  public int displaySize() {
    return displaySize;
  }

  public boolean signed() {
    return signed;
  }

  public boolean caseSensitive() {
    return caseSensitive;
  }

  public int nullable() {
    return nullable;
  }
}
