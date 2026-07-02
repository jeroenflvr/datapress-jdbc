package org.datap_rs.jdbc.internal.arrow;

import org.apache.arrow.vector.FieldVector;

/**
 * Reads a single Arrow {@link FieldVector} into the canonical Java object for one row (see {@link
 * Convert} for the object model). One accessor is built per result column and reused across
 * streamed batches, because {@code ArrowStreamReader} reloads data into the same vector instances.
 */
public abstract class ValueAccessor {

  protected final FieldVector vector;

  protected ValueAccessor(FieldVector vector) {
    this.vector = vector;
  }

  /** Whether the value at {@code row} is SQL NULL. */
  public boolean isNull(int row) {
    return vector.isNull(row);
  }

  /**
   * The canonical Java object for {@code row}, or {@code null} when the value is SQL NULL. Concrete
   * subclasses must return exactly the class documented in the SKILL type-mapping table.
   */
  public abstract Object getObject(int row);
}
