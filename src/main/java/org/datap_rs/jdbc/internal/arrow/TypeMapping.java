package org.datap_rs.jdbc.internal.arrow;

import java.sql.ResultSetMetaData;
import java.sql.Types;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
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

  /**
   * Maps a server schema {@code sql_type} string (as returned by {@code GET
   * /api/v1/datasets/{name}/schema}) to a {@link ColumnMeta}. The string is the engine's Arrow type
   * rendering, e.g. {@code Int64}, {@code Float64}, {@code Boolean}, {@code Decimal128(10, 2)},
   * {@code Dictionary(Int32, Utf8)}, {@code Timestamp(Microsecond, Some("UTC"))}. Dictionary types
   * resolve to their value type. Unknown/composite types fall back to {@code VARCHAR}.
   */
  public static ColumnMeta ofServerType(String name, String sqlType, boolean nullable) {
    int n = nullable ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls;
    return describe(name, parseArrowType(sqlType), n);
  }

  /** Parses a server {@code sql_type} rendering into an {@link ArrowType}. */
  static ArrowType parseArrowType(String raw) {
    String s = raw == null ? "" : raw.trim();
    String head = s;
    String args = null;
    int paren = s.indexOf('(');
    if (paren >= 0 && s.endsWith(")")) {
      head = s.substring(0, paren).trim();
      args = s.substring(paren + 1, s.length() - 1).trim();
    }
    switch (head) {
      case "Boolean":
        return ArrowType.Bool.INSTANCE;
      case "Int8":
        return new ArrowType.Int(8, true);
      case "Int16":
        return new ArrowType.Int(16, true);
      case "Int32":
        return new ArrowType.Int(32, true);
      case "Int64":
        return new ArrowType.Int(64, true);
      case "UInt8":
        return new ArrowType.Int(8, false);
      case "UInt16":
        return new ArrowType.Int(16, false);
      case "UInt32":
        return new ArrowType.Int(32, false);
      case "UInt64":
        return new ArrowType.Int(64, false);
      case "Float16":
        return new ArrowType.FloatingPoint(FloatingPointPrecision.HALF);
      case "Float32":
        return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
      case "Float64":
        return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
      case "Utf8":
      case "LargeUtf8":
        return new ArrowType.Utf8();
      case "Binary":
        return new ArrowType.Binary();
      case "LargeBinary":
        return new ArrowType.LargeBinary();
      case "FixedSizeBinary":
        return new ArrowType.FixedSizeBinary(parseIntArg(args, 0));
      case "Date32":
        return new ArrowType.Date(DateUnit.DAY);
      case "Date64":
        return new ArrowType.Date(DateUnit.MILLISECOND);
      case "Time32":
        return new ArrowType.Time(parseTimeUnit(args), 32);
      case "Time64":
        return new ArrowType.Time(parseTimeUnit(args), 64);
      case "Timestamp":
        return parseTimestamp(args);
      case "Decimal128":
        return parseDecimal(args, 128);
      case "Decimal256":
        return parseDecimal(args, 256);
      case "Dictionary":
        return parseArrowType(dictionaryValueType(args));
      case "Null":
        return new ArrowType.Null();
      default:
        // List/LargeList/Struct/Map and anything unrecognised: expose as text.
        return new ArrowType.Utf8();
    }
  }

  private static ArrowType parseTimestamp(String args) {
    String unit = "Microsecond";
    String tz = null;
    if (args != null) {
      java.util.List<String> parts = splitTopLevel(args);
      if (!parts.isEmpty()) {
        unit = parts.get(0).trim();
      }
      if (parts.size() > 1) {
        String tzPart = parts.get(1).trim();
        if (tzPart.startsWith("Some(")) {
          String inner = tzPart.substring("Some(".length(), tzPart.lastIndexOf(')')).trim();
          if (inner.length() >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) {
            inner = inner.substring(1, inner.length() - 1);
          }
          tz = inner;
        }
      }
    }
    return new ArrowType.Timestamp(timeUnitOf(unit), tz);
  }

  private static ArrowType parseDecimal(String args, int bitWidth) {
    java.util.List<String> parts = splitTopLevel(args == null ? "" : args);
    int precision = parts.size() > 0 ? parseIntArg(parts.get(0).trim(), 10) : 10;
    int scale = parts.size() > 1 ? parseIntArg(parts.get(1).trim(), 0) : 0;
    return new ArrowType.Decimal(precision, scale, bitWidth);
  }

  private static String dictionaryValueType(String args) {
    java.util.List<String> parts = splitTopLevel(args == null ? "" : args);
    // Dictionary(IndexType, ValueType): the value type is what JDBC callers care about.
    return parts.size() > 1
        ? parts.get(1).trim()
        : (parts.isEmpty() ? "Utf8" : parts.get(0).trim());
  }

  private static TimeUnit parseTimeUnit(String args) {
    return timeUnitOf(args == null ? "Microsecond" : args.trim());
  }

  private static TimeUnit timeUnitOf(String unit) {
    switch (unit) {
      case "Second":
        return TimeUnit.SECOND;
      case "Millisecond":
        return TimeUnit.MILLISECOND;
      case "Nanosecond":
        return TimeUnit.NANOSECOND;
      case "Microsecond":
      default:
        return TimeUnit.MICROSECOND;
    }
  }

  private static int parseIntArg(String s, int fallback) {
    if (s == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** Splits {@code args} on top-level commas, respecting nested parentheses. */
  private static java.util.List<String> splitTopLevel(String args) {
    java.util.List<String> out = new java.util.ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        out.add(args.substring(start, i));
        start = i + 1;
      }
    }
    if (start <= args.length()) {
      out.add(args.substring(start));
    }
    return out;
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
