package org.datap_rs.jdbc.internal.arrow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.datap_rs.jdbc.internal.http.SqlErrors;

/**
 * Type coercions shared by every {@code DataPressResultSet} getter. Inputs are the canonical Java
 * objects produced by {@link ValueAccessor} (String, Boolean, boxed integers/floats, BigDecimal,
 * byte[], and {@code java.time} temporals). Widening/narrowing follows the JDBC spec; out-of-range
 * integer conversions raise {@code 22003}, unparseable values raise {@code 22018}.
 */
public final class Convert {

  private Convert() {}

  public static boolean toBoolean(Object v) throws SQLException {
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    if (v instanceof Number) {
      return ((Number) v).doubleValue() != 0.0;
    }
    if (v instanceof String) {
      String s = ((String) v).trim();
      if (s.equalsIgnoreCase("true")
          || s.equals("1")
          || s.equalsIgnoreCase("t")
          || s.equalsIgnoreCase("yes")) {
        return true;
      }
      if (s.equalsIgnoreCase("false")
          || s.equals("0")
          || s.equalsIgnoreCase("f")
          || s.equalsIgnoreCase("no")) {
        return false;
      }
    }
    throw cannotConvert(v, "boolean");
  }

  public static byte toByte(Object v) throws SQLException {
    return (byte) toIntegral(v, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
  }

  public static short toShort(Object v) throws SQLException {
    return (short) toIntegral(v, Short.MIN_VALUE, Short.MAX_VALUE, "short");
  }

  public static int toInt(Object v) throws SQLException {
    return (int) toIntegral(v, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
  }

  public static long toLong(Object v) throws SQLException {
    return toIntegral(v, Long.MIN_VALUE, Long.MAX_VALUE, "long");
  }

  private static long toIntegral(Object v, long min, long max, String target) throws SQLException {
    BigDecimal bd = toBigDecimal(v);
    BigDecimal truncated = bd.setScale(0, RoundingMode.DOWN);
    if (truncated.compareTo(BigDecimal.valueOf(min)) < 0
        || truncated.compareTo(BigDecimal.valueOf(max)) > 0) {
      throw SqlErrors.numericOverflow(
          "Value " + bd.toPlainString() + " out of range for " + target);
    }
    return truncated.longValueExact();
  }

  public static float toFloat(Object v) throws SQLException {
    return (float) toDouble(v);
  }

  public static double toDouble(Object v) throws SQLException {
    if (v instanceof Number) {
      return ((Number) v).doubleValue();
    }
    if (v instanceof Boolean) {
      return ((Boolean) v) ? 1.0 : 0.0;
    }
    if (v instanceof String) {
      try {
        return Double.parseDouble(((String) v).trim());
      } catch (NumberFormatException e) {
        throw cannotConvert(v, "double");
      }
    }
    throw cannotConvert(v, "double");
  }

  public static BigDecimal toBigDecimal(Object v) throws SQLException {
    if (v instanceof BigDecimal) {
      return (BigDecimal) v;
    }
    if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
      return BigDecimal.valueOf(((Number) v).longValue());
    }
    if (v instanceof Float || v instanceof Double) {
      return BigDecimal.valueOf(((Number) v).doubleValue());
    }
    if (v instanceof Boolean) {
      return ((Boolean) v) ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    if (v instanceof String) {
      try {
        return new BigDecimal(((String) v).trim());
      } catch (NumberFormatException e) {
        throw cannotConvert(v, "BigDecimal");
      }
    }
    throw cannotConvert(v, "BigDecimal");
  }

  public static byte[] toBytes(Object v) throws SQLException {
    if (v instanceof byte[]) {
      return ((byte[]) v).clone();
    }
    if (v instanceof String) {
      return ((String) v).getBytes(StandardCharsets.UTF_8);
    }
    throw cannotConvert(v, "byte[]");
  }

  /** ISO-8601 / plain rendering used by {@code getString()} for every type. */
  public static String toDisplayString(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof String) {
      return (String) v;
    }
    if (v instanceof byte[]) {
      return toHex((byte[]) v);
    }
    if (v instanceof BigDecimal) {
      return ((BigDecimal) v).toPlainString();
    }
    if (v instanceof LocalDate) {
      return ((LocalDate) v).toString();
    }
    if (v instanceof LocalTime) {
      return ((LocalTime) v).toString();
    }
    if (v instanceof LocalDateTime) {
      return ((LocalDateTime) v).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    if (v instanceof OffsetDateTime) {
      return ((OffsetDateTime) v).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
    return String.valueOf(v);
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  private static SQLException cannotConvert(Object v, String target) {
    String from = v == null ? "null" : v.getClass().getSimpleName();
    return SqlErrors.dataConversion("Cannot convert value of type " + from + " to " + target);
  }
}
