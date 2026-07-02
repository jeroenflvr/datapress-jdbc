package org.datap_rs.jdbc.internal.arrow;

import java.io.IOException;
import java.io.InputStream;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Streams Arrow IPC record batches off an HTTP response body one at a time. The whole result is
 * never buffered: {@link #loadNextBatch()} reads exactly one batch into the reader's reused {@link
 * VectorSchemaRoot}, which is the driver's memory-safety guarantee (see SKILL.md rule 2).
 *
 * <p>The schema is always available (even for an empty result, which is a valid stream carrying the
 * schema message and zero batches). Closing releases the reader, the underlying stream, and every
 * vector buffer allocated from the supplied allocator.
 */
public final class ArrowResultIterator implements AutoCloseable {

  private final ArrowStreamReader reader;
  private boolean schemaLoaded;

  public ArrowResultIterator(InputStream in, BufferAllocator allocator) {
    this.reader = new ArrowStreamReader(in, allocator);
  }

  /** The result schema; forces reading the leading schema message if not yet read. */
  public Schema getSchema() throws IOException {
    ensureSchema();
    return reader.getVectorSchemaRoot().getSchema();
  }

  /** The reused root holding the current batch's vectors. Valid after {@link #getSchema()}. */
  public VectorSchemaRoot getRoot() throws IOException {
    ensureSchema();
    return reader.getVectorSchemaRoot();
  }

  /**
   * The dictionary provider backing dictionary-encoded columns (DataFusion emits these for string
   * columns). Dictionaries are populated as batches load; accessors must look up lazily.
   */
  public DictionaryProvider provider() throws IOException {
    ensureSchema();
    return reader;
  }

  /**
   * Loads the next record batch into the root.
   *
   * @return {@code true} if a batch was loaded, {@code false} at end of stream
   */
  public boolean loadNextBatch() throws IOException {
    ensureSchema();
    return reader.loadNextBatch();
  }

  private void ensureSchema() throws IOException {
    if (!schemaLoaded) {
      // getVectorSchemaRoot() reads the schema message on first access.
      reader.getVectorSchemaRoot();
      schemaLoaded = true;
    }
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
