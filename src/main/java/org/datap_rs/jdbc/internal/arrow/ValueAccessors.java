package org.datap_rs.jdbc.internal.arrow;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.apache.arrow.vector.BaseIntVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.LargeVarBinaryVector;
import org.apache.arrow.vector.LargeVarCharVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeSecVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecTZVector;
import org.apache.arrow.vector.TimeStampSecVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.ViewVarBinaryVector;
import org.apache.arrow.vector.ViewVarCharVector;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;

/**
 * Builds the concrete {@link ValueAccessor} for an Arrow vector. Each accessor decodes its vector's
 * raw storage into the canonical Java object documented in the SKILL type-mapping table; temporal
 * types decode into {@code java.time} values so {@code DataPressResultSet} can re-zone them per the
 * caller's {@code Calendar}.
 */
public final class ValueAccessors {

  private ValueAccessors() {}

  /**
   * Builds the accessor for a vector, decoding dictionary encoding when present. DataFusion returns
   * string columns as dictionary-encoded vectors; the dictionary values arrive with the first
   * batch, so the returned accessor resolves them lazily via the provider.
   */
  public static ValueAccessor forField(FieldVector v, DictionaryProvider provider) {
    DictionaryEncoding encoding = v.getField().getDictionary();
    if (encoding != null) {
      return new DictionaryAccessor(v, provider, encoding.getId());
    }
    return forVector(v);
  }

  public static ValueAccessor forVector(FieldVector v) {
    if (v instanceof VarCharVector) {
      return new SimpleAccessor(v, row -> new String(((VarCharVector) v).get(row), UTF_8));
    }
    if (v instanceof LargeVarCharVector) {
      return new SimpleAccessor(v, row -> new String(((LargeVarCharVector) v).get(row), UTF_8));
    }
    if (v instanceof ViewVarCharVector) {
      return new SimpleAccessor(v, row -> new String(((ViewVarCharVector) v).get(row), UTF_8));
    }
    if (v instanceof BitVector) {
      return new SimpleAccessor(v, row -> ((BitVector) v).get(row) != 0);
    }
    if (v instanceof TinyIntVector) {
      return new SimpleAccessor(v, row -> ((TinyIntVector) v).get(row));
    }
    if (v instanceof SmallIntVector) {
      return new SimpleAccessor(v, row -> ((SmallIntVector) v).get(row));
    }
    if (v instanceof IntVector) {
      return new SimpleAccessor(v, row -> ((IntVector) v).get(row));
    }
    if (v instanceof BigIntVector) {
      return new SimpleAccessor(v, row -> ((BigIntVector) v).get(row));
    }
    if (v instanceof UInt1Vector) {
      return new SimpleAccessor(v, row -> (short) (((UInt1Vector) v).get(row) & 0xFF));
    }
    if (v instanceof UInt2Vector) {
      return new SimpleAccessor(v, row -> (int) ((UInt2Vector) v).get(row));
    }
    if (v instanceof UInt4Vector) {
      return new SimpleAccessor(v, row -> Integer.toUnsignedLong(((UInt4Vector) v).get(row)));
    }
    if (v instanceof UInt8Vector) {
      return new SimpleAccessor(
          v, row -> new BigDecimal(((UInt8Vector) v).getObjectNoOverflow(row)));
    }
    if (v instanceof Float4Vector) {
      return new SimpleAccessor(v, row -> ((Float4Vector) v).get(row));
    }
    if (v instanceof Float8Vector) {
      return new SimpleAccessor(v, row -> ((Float8Vector) v).get(row));
    }
    if (v instanceof DecimalVector) {
      return new SimpleAccessor(v, row -> ((DecimalVector) v).getObject(row));
    }
    if (v instanceof Decimal256Vector) {
      return new SimpleAccessor(v, row -> ((Decimal256Vector) v).getObject(row));
    }
    if (v instanceof DateDayVector) {
      return new SimpleAccessor(v, row -> LocalDate.ofEpochDay(((DateDayVector) v).get(row)));
    }
    if (v instanceof DateMilliVector) {
      return new SimpleAccessor(
          v,
          row -> LocalDate.ofEpochDay(Math.floorDiv(((DateMilliVector) v).get(row), 86_400_000L)));
    }
    if (v instanceof TimeSecVector) {
      return new SimpleAccessor(v, row -> LocalTime.ofSecondOfDay(((TimeSecVector) v).get(row)));
    }
    if (v instanceof TimeMilliVector) {
      return new SimpleAccessor(
          v, row -> LocalTime.ofNanoOfDay(((TimeMilliVector) v).get(row) * 1_000_000L));
    }
    if (v instanceof TimeMicroVector) {
      return new SimpleAccessor(
          v, row -> LocalTime.ofNanoOfDay(((TimeMicroVector) v).get(row) * 1_000L));
    }
    if (v instanceof TimeNanoVector) {
      return new SimpleAccessor(v, row -> LocalTime.ofNanoOfDay(((TimeNanoVector) v).get(row)));
    }
    if (v instanceof TimeStampSecVector) {
      return new SimpleAccessor(v, row -> ldtFromSeconds(((TimeStampSecVector) v).get(row), 0));
    }
    if (v instanceof TimeStampMilliVector) {
      return new SimpleAccessor(
          v, row -> ldtFromScaled(((TimeStampMilliVector) v).get(row), 1_000L));
    }
    if (v instanceof TimeStampMicroVector) {
      return new SimpleAccessor(
          v, row -> ldtFromScaled(((TimeStampMicroVector) v).get(row), 1_000_000L));
    }
    if (v instanceof TimeStampNanoVector) {
      return new SimpleAccessor(
          v, row -> ldtFromScaled(((TimeStampNanoVector) v).get(row), 1_000_000_000L));
    }
    if (v instanceof TimeStampSecTZVector) {
      ZoneId zone = zoneOf(v);
      return new SimpleAccessor(
          v,
          row ->
              Instant.ofEpochSecond(((TimeStampSecTZVector) v).get(row))
                  .atZone(zone)
                  .toOffsetDateTime());
    }
    if (v instanceof TimeStampMilliTZVector) {
      ZoneId zone = zoneOf(v);
      return new SimpleAccessor(
          v,
          row ->
              instantFromScaled(((TimeStampMilliTZVector) v).get(row), 1_000L)
                  .atZone(zone)
                  .toOffsetDateTime());
    }
    if (v instanceof TimeStampMicroTZVector) {
      ZoneId zone = zoneOf(v);
      return new SimpleAccessor(
          v,
          row ->
              instantFromScaled(((TimeStampMicroTZVector) v).get(row), 1_000_000L)
                  .atZone(zone)
                  .toOffsetDateTime());
    }
    if (v instanceof TimeStampNanoTZVector) {
      ZoneId zone = zoneOf(v);
      return new SimpleAccessor(
          v,
          row ->
              instantFromScaled(((TimeStampNanoTZVector) v).get(row), 1_000_000_000L)
                  .atZone(zone)
                  .toOffsetDateTime());
    }
    if (v instanceof VarBinaryVector) {
      return new SimpleAccessor(v, row -> ((VarBinaryVector) v).get(row));
    }
    if (v instanceof LargeVarBinaryVector) {
      return new SimpleAccessor(v, row -> ((LargeVarBinaryVector) v).get(row));
    }
    if (v instanceof FixedSizeBinaryVector) {
      return new SimpleAccessor(v, row -> ((FixedSizeBinaryVector) v).get(row));
    }
    if (v instanceof ViewVarBinaryVector) {
      return new SimpleAccessor(v, row -> ((ViewVarBinaryVector) v).get(row));
    }
    // List / Struct / Map / anything else: render Arrow's own JSON text.
    return new JsonAccessor(v);
  }

  private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

  private static ZoneId zoneOf(FieldVector v) {
    String tz = ((ArrowType.Timestamp) v.getField().getType()).getTimezone();
    return tz == null || tz.isEmpty() ? ZoneOffset.UTC : ZoneId.of(tz);
  }

  private static LocalDateTime ldtFromSeconds(long epochSecond, int nano) {
    return LocalDateTime.ofEpochSecond(epochSecond, nano, ZoneOffset.UTC);
  }

  private static LocalDateTime ldtFromScaled(long value, long unitsPerSecond) {
    long second = Math.floorDiv(value, unitsPerSecond);
    long fraction = Math.floorMod(value, unitsPerSecond);
    int nano = (int) (fraction * (1_000_000_000L / unitsPerSecond));
    return LocalDateTime.ofEpochSecond(second, nano, ZoneOffset.UTC);
  }

  private static Instant instantFromScaled(long value, long unitsPerSecond) {
    long second = Math.floorDiv(value, unitsPerSecond);
    long fraction = Math.floorMod(value, unitsPerSecond);
    long nano = fraction * (1_000_000_000L / unitsPerSecond);
    return Instant.ofEpochSecond(second, nano);
  }

  @FunctionalInterface
  private interface RowReader {
    Object read(int row);
  }

  /** Generic accessor: null-guards, then delegates to a per-type row reader. */
  private static final class SimpleAccessor extends ValueAccessor {
    private final RowReader reader;

    SimpleAccessor(FieldVector vector, RowReader reader) {
      super(vector);
      this.reader = reader;
    }

    @Override
    public Object getObject(int row) {
      return vector.isNull(row) ? null : reader.read(row);
    }
  }

  /** Nested/complex types rendered as Arrow's JSON text. */
  private static final class JsonAccessor extends ValueAccessor {
    JsonAccessor(FieldVector vector) {
      super(vector);
    }

    @Override
    public Object getObject(int row) {
      if (vector.isNull(row)) {
        return null;
      }
      Object o = vector.getObject(row);
      return o == null ? null : o.toString();
    }
  }

  /**
   * Decodes a dictionary-encoded column: the vector holds integer indices into a dictionary vector
   * supplied by the reader's provider. The dictionary is resolved lazily (it is delivered with the
   * first record batch) and its value accessor is cached until the underlying vector changes.
   */
  private static final class DictionaryAccessor extends ValueAccessor {
    private final DictionaryProvider provider;
    private final long id;
    private FieldVector cachedDictVector;
    private ValueAccessor cachedValues;

    DictionaryAccessor(FieldVector indices, DictionaryProvider provider, long id) {
      super(indices);
      this.provider = provider;
      this.id = id;
    }

    @Override
    public Object getObject(int row) {
      if (vector.isNull(row)) {
        return null;
      }
      Dictionary dictionary = provider == null ? null : provider.lookup(id);
      if (dictionary == null) {
        return null;
      }
      FieldVector dictVector = dictionary.getVector();
      if (dictVector != cachedDictVector) {
        cachedDictVector = dictVector;
        cachedValues = forVector(dictVector);
      }
      long index = ((BaseIntVector) vector).getValueAsLong(row);
      return cachedValues.getObject((int) index);
    }
  }
}
