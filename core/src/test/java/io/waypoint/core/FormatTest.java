package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The primitive codecs everything else is built on. */
class FormatTest {

  @Test
  void varintsRoundTripAcrossEveryBoundary() {
    long[] interesting = {
      0, 1, 2, 126, 127, 128, 129, 16_383, 16_384, 16_385,
      2_097_151, 2_097_152, 268_435_455, 268_435_456,
      Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1,
      Long.MAX_VALUE >>> 8, Long.MAX_VALUE >>> 1,
    };
    for (long v : interesting) {
      assertRoundTrip(v);
    }
  }

  @Test
  void varintsRoundTripOnRandomValues() {
    Random rnd = new Random(20260906L);
    for (int i = 0; i < 20_000; i++) {
      long v = rnd.nextLong() >>> rnd.nextInt(64);
      assertRoundTrip(v);
    }
  }

  @Test
  void lengthMatchesWhatIsActuallyWritten() {
    Random rnd = new Random(7L);
    byte[] buf = new byte[16];
    for (int i = 0; i < 10_000; i++) {
      long v = rnd.nextLong() >>> rnd.nextInt(64);
      assertEquals(Format.vLongLength(v), Format.writeVLong(buf, 0, v), "length for " + v);
    }
  }

  /**
   * Terms are compared as unsigned bytes, because that is the order Lucene
   * sorts UTF-8 terms in. Signed comparison would put every byte above 0x7F
   * before every ASCII byte, which silently breaks the term dictionary's
   * binary search for any non-ASCII corpus -- and passes every ASCII test.
   */
  @Test
  void comparesBytesAsUnsigned() {
    byte[] ascii = {0x7F};
    byte[] high = {(byte) 0x80};
    assertTrue(Format.compareBytes(ascii, 1, high, 1) < 0, "0x7F must sort before 0x80");

    byte[] prefix = {'a', 'b'};
    byte[] longer = {'a', 'b', 'c'};
    assertTrue(Format.compareBytes(prefix, 2, longer, 3) < 0, "a prefix sorts first");
    assertEquals(0, Format.compareBytes(prefix, 2, prefix, 2));
  }

  @Test
  void unsignedComparisonMatchesUtf8SortOrderForNonAsciiTerms() {
    // U+00E9 (e-acute) encodes to 0xC3 0xA9; "z" is 0x7A. In UTF-8 byte order
    // "z" sorts first, and Lucene's term dictionary relies on exactly that.
    byte[] z = "z".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] eAcute = "é".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(Format.compareBytes(z, z.length, eAcute, eAcute.length) < 0);
  }

  private static void assertRoundTrip(long v) {
    byte[] buf = new byte[16];
    int len = Format.writeVLong(buf, 0, v);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment seg = arena.allocate(buf.length);
      MemorySegment.copy(buf, 0, seg, Format.I8, 0, buf.length);
      long[] pos = {0};
      assertEquals(v, Format.readVLong(seg, pos), "value " + v);
      assertEquals(len, pos[0], "bytes consumed for " + v);
    }
  }

  @Test
  void headerIsBigEnoughForEveryFieldItDeclares() {
    int lastOffset = Format.HDR_FOOTER_OFF + Long.BYTES;
    assertTrue(
        lastOffset <= Format.HEADER_BYTES,
        "header fields run to " + lastOffset + " but HEADER_BYTES is " + Format.HEADER_BYTES);
  }

  @Test
  void magicSpellsWypt() {
    byte[] bytes = {
      (byte) (Format.MAGIC), (byte) (Format.MAGIC >> 8),
      (byte) (Format.MAGIC >> 16), (byte) (Format.MAGIC >> 24),
    };
    assertArrayEquals("WYPT".getBytes(java.nio.charset.StandardCharsets.US_ASCII), bytes);
  }
}
