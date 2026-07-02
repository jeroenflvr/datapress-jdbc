package org.datapress.jdbc.internal.arrow;

import java.sql.ResultSetMetaData;
import java.sql.Types;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Central Arrow {@code ArrowType} &rarr; JDBC mapping (see SKILL.md &rarr; "Arrow → JDBC type
 * mapping"). Produces the {@link ColumnMeta} that backs {@code DataPressResultSetMetaData}; the
 * matching runtime {@link ValueAccessor} is built from the concrete vector by {@link
 * ValueAccessors}.
 */
public final class TypeMapping {

  private TypeMapping() {}

  /** Builds the JDBC column description for an Arrow field. */
  public static ColumnMeta of(Field field) {
    ArrowType type = field.getType();
    int nullable =
        field.isNullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
    return describe(field.getName(), type, nullable);
  }

  /**
   * Dictionary-aware variant. A dictionary-encoded field's {@link Field#getType()} is the index
   * type (e.g. {@code Int32}), not the logical value type; DataFusion emits string columns this
   * way. When the field is dictionary-encoded we describe it using the dictionary value vector's
   * type (resolved from the schema via {@code provider}), so metadata matches what the accessors
   * actually return.
   */
  public static ColumnMeta of(Field field, DictionaryProvider provider) {
    DictionaryEncoding encoding = field.getDictionary();
    if (encoding != null && provider != null) {
      Dictionary dictionary = provider.lookup(encoding.getId());
      if (dictionary != null && dictionary.getVector() != null) {
        int nullable =
            field.isNullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
        return describe(field.getName(), dictionary.getVector().getField().getType(), nullable);
      }
    }
    return of(field);
  }

  private static ColumnMeta describe(String name, ArrowType type, int nullable) {
    switch (type.getTypeID()) {
      case Bool:
        return meta(name, Types.BOOLEAN, "BOOLEAN", "java.lang.Boolean", 1, 0, 5, false, nullable);
      case Int:
        return intMeta(name, (ArrowType.Int) type, nullable);
      case FloatingPoint:
        return floatMeta(name, (ArrowType.FloatingPoint) type, nullable);
      case Decimal:
        {
          ArrowType.Decimal d = (ArrowType.Decimal) type;
          return meta(
              name,
              Types.DECIMAL,
              "DECIMAL",
              "java.math.BigDecimal",
              d.getPrecision(),
              d.getScale(),
              d.getPrecision() + 2,
              true,
              false,
              nullable);
        }
      case Utf8:
        return meta(
            name, Types.VARCHAR, "VARCHAR", "java.lang.String", 0, 0, 255, false, true, nullable);
      case Utf8View:
        return meta(
            name, Types.VARCHAR, "VARCHAR", "java.lang.String", 0, 0, 255, false, true, nullable);
      case LargeUtf8:
        return meta(
            name,
            Types.LONGVARCHAR,
            "LONGVARCHAR",
            "java.lang.String",
            0,
            0,
            65535,
            false,
            true,
            nullable);
      case Binary:
        return meta(name, Types.VARBINARY, "VARBINARY", "[B", 0, 0, 0, false, false, nullable);
      case BinaryView:
        return meta(name, Types.VARBINARY, "VARBINARY", "[B", 0, 0, 0, false, false, nullable);
      case LargeBinary:
        return meta(
            name, Types.LONGVARBINARY, "LONGVARBINARY", "[B", 0, 0, 0, false, false, nullable);
      case FixedSizeBinary:
        {
          int width = ((ArrowType.FixedSizeBinary) type).getByteWidth();
          return meta(name, Types.BINARY, "BINARY", "[B", width, 0, width, false, false, nullable);
        }
      case Date:
        return meta(name, Types.DATE, "DATE", "java.sql.Date", 10, 0, 10, false, false, nullable);
      case Time:
        return meta(name, Types.TIME, "TIME", "java.sql.Time", 8, 0, 18, false, false, nullable);
      case Timestamp:
        {
          ArrowType.Timestamp ts = (ArrowType.Timestamp) type;
          boolean hasTz = ts.getTimezone() != null && !ts.getTimezone().isEmpty();
          if (hasTz) {
            return meta(
                name,
                Types.TIMESTAMP_WITH_TIMEZONE,
                "TIMESTAMP WITH TIME ZONE",
                "java.time.OffsetDateTime",
                35,
                9,
                35,
                false,
                false,
                nullable);
          }
          return meta(
              name,
              Types.TIMESTAMP,
              "TIMESTAMP",
              "java.sql.Timestamp",
              29,
              9,
              29,
              false,
              false,
              nullable);
        }
      case List:
      case LargeList:
      case FixedSizeList:
      case Struct:
      case Map:
        return meta(
            name, Types.VARCHAR, "VARCHAR", "java.lang.String", 0, 0, 65535, false, true, nullable);
      case Null:
        return meta(name, Types.NULL, "NULL", "java.lang.Object", 0, 0, 4, false, false, nullable);
      default:
        // Unknown/unsupported Arrow type: expose as VARCHAR of its JSON/text rendering.
        return meta(
            name, Types.VARCHAR, "VARCHAR", "java.lang.String", 0, 0, 255, false, true, nullable);
    }
  }

  private static ColumnMeta intMeta(String name, ArrowType.Int type, int nullable) {
    int bits = type.getBitWidth();
    boolean signed = type.getIsSigned();
    if (signed) {
      switch (bits) {
        case 8:
          return meta(name, Types.TINYINT, "TINYINT", "java.lang.Byte", 3, 0, 4, true, nullable);
        case 16:
          return meta(name, Types.SMALLINT, "SMALLINT", "java.lang.Short", 5, 0, 6, true, nullable);
        case 32:
          return meta(
              name, Types.INTEGER, "INTEGER", "java.lang.Integer", 10, 0, 11, true, nullable);
        default:
          return meta(name, Types.BIGINT, "BIGINT", "java.lang.Long", 19, 0, 20, true, nullable);
      }
    }
    // Unsigned widths promote to the next-larger signed type; UInt64 becomes DECIMAL(20,0).
    switch (bits) {
      case 8:
        return meta(name, Types.SMALLINT, "SMALLINT", "java.lang.Short", 3, 0, 4, true, nullable);
      case 16:
        return meta(name, Types.INTEGER, "INTEGER", "java.lang.Integer", 5, 0, 6, true, nullable);
      case 32:
        return meta(name, Types.BIGINT, "BIGINT", "java.lang.Long", 10, 0, 11, true, nullable);
      default:
        return meta(
            name,
            Types.DECIMAL,
            "DECIMAL",
            "java.math.BigDecimal",
            20,
            0,
            21,
            true,
            false,
            nullable);
    }
  }

  private static ColumnMeta floatMeta(String name, ArrowType.FloatingPoint type, int nullable) {
    if (type.getPrecision() == FloatingPointPrecision.DOUBLE) {
      return meta(name, Types.DOUBLE, "DOUBLE", "java.lang.Double", 15, 0, 24, true, nullable);
    }
    return meta(name, Types.REAL, "REAL", "java.lang.Float", 7, 0, 15, true, nullable);
  }

  // Numeric convenience: signed=given, caseSensitive=false.
  private static ColumnMeta meta(
      String name,
      int sqlType,
      String typeName,
      String className,
      int precision,
      int scale,
      int displaySize,
      boolean signed,
      int nullable) {
    return meta(
        name, sqlType, typeName, className, precision, scale, displaySize, signed, false, nullable);
  }

  private static ColumnMeta meta(
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
    return new ColumnMeta(
        name,
        sqlType,
        typeName,
        className,
        precision,
        scale,
        displaySize,
        signed,
        caseSensitive,
        nullable);
  }
}
