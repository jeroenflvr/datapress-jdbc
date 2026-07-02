package org.datap_rs.jdbc.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.util.List;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;

/** Serializes {@link VectorSchemaRoot} contents into Arrow IPC stream bytes for driver tests. */
public final class ArrowStreams {

  private ArrowStreams() {}

  /**
   * Writes one IPC stream containing {@code fillers.size()} record batches. Each filler populates
   * {@code root} (setting values and {@code setRowCount}) for a single batch; it is invoked
   * immediately before that batch is written. An empty list yields a schema-only stream (zero
   * rows).
   */
  public static byte[] write(VectorSchemaRoot root, List<Runnable> fillers) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ArrowStreamWriter writer = new ArrowStreamWriter(root, null, Channels.newChannel(out))) {
      writer.start();
      for (Runnable filler : fillers) {
        filler.run();
        writer.writeBatch();
      }
      writer.end();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
