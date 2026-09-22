package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens when the file on disk is not what we expect.
 *
 * <p>The CRC32 case is the interesting one, and it is the trade this project is
 * making out loud. Lucene validates a header and retrieves a checksum for every
 * file it opens; Waypoint checks magic and format version at open and defers
 * the checksum to {@code verify}. Some of the open-time win is bought exactly
 * there. These tests pin that behaviour down so it is a documented decision
 * rather than an omission: a corrupt body opens cleanly and is caught by
 * {@code verify}, while a corrupt header or a truncated file is rejected
 * immediately.
 */
class IndexFormatTest {

  private static Path buildFixture(Path dir) throws IOException {
    Path path = dir.resolve("fixture.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      for (int i = 0; i < 300; i++) {
        b.addText("d" + i, "alpha beta gamma token" + i + " delta epsilon");
      }
    }
    return path;
  }

  @Test
  void rejectsBadMagic(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    patch(path, Format.HDR_MAGIC, new byte[] {'N', 'O', 'P', 'E'});
    IndexFormatException e =
        assertThrows(IndexFormatException.class, () -> Index.open(path));
    assertTrue(e.getMessage().contains("bad magic"), e.getMessage());
    assertTrue(e.getMessage().contains("expected"), "the message names what was expected");
    assertTrue(e.getMessage().contains("found"), "the message names what was found");
  }

  @Test
  void rejectsUnknownFormatVersion(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    patch(path, Format.HDR_VERSION, intLe(Format.VERSION + 7));
    IndexFormatException e =
        assertThrows(IndexFormatException.class, () -> Index.open(path));
    assertTrue(e.getMessage().contains("unsupported format version"), e.getMessage());
    assertTrue(
        e.getMessage().contains(Integer.toString(Format.VERSION + 7)),
        "the message names the version found: " + e.getMessage());
  }

  @Test
  void rejectsAFileTooShortToHoldAHeader(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("stub.wpt");
    Files.write(path, new byte[17]);
    IndexFormatException e =
        assertThrows(IndexFormatException.class, () -> Index.open(path));
    assertTrue(e.getMessage().contains("too short"), e.getMessage());
  }

  @Test
  void rejectsATruncatedFile(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    long full = Files.size(path);
    try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
      raf.setLength(full - 64);
    }
    IndexFormatException e =
        assertThrows(IndexFormatException.class, () -> Index.open(path));
    assertTrue(e.getMessage().contains("truncated"), e.getMessage());
  }

  @Test
  void rejectsAHeaderPointingOutsideTheFile(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    patch(path, Format.HDR_NORMS_OFF, longLe(Long.MAX_VALUE / 2));
    assertThrows(IndexFormatException.class, () -> Index.open(path));
  }

  /**
   * The documented consequence of not checksumming on open: a body-level
   * corruption is invisible until {@code verify} is called. This is asserted
   * rather than merely described, so the trade cannot quietly stop being true.
   */
  @Test
  void bodyCorruptionOpensCleanlyAndIsCaughtOnlyByVerify(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    byte[] all = Files.readAllBytes(path);
    int offset = Format.HEADER_BYTES + 40;
    all[offset] = (byte) (all[offset] ^ 0xFF);
    Files.write(path, all);

    try (Index index = Index.open(path)) {
      // Opening succeeds: this is the bill Lucene pays on every open and we do not.
      IndexFormatException e = assertThrows(IndexFormatException.class, index::verify);
      assertTrue(e.getMessage().contains("CRC32 mismatch"), e.getMessage());
    }
  }

  @Test
  void verifyAcceptsAnUntouchedFile(@TempDir Path tmp) throws IOException {
    Path path = buildFixture(tmp);
    try (Index index = Index.open(path)) {
      index.verify();
      assertEquals(300, index.docCount());
    }
  }

  // ------------------------------------------------------------------------

  private static void patch(Path path, int offset, byte[] bytes) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
      raf.seek(offset);
      raf.write(bytes);
    }
  }

  private static byte[] intLe(int v) {
    return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
  }

  private static byte[] longLe(long v) {
    byte[] b = new byte[8];
    for (int i = 0; i < 8; i++) {
      b[i] = (byte) (v >>> (8 * i));
    }
    return b;
  }
}
