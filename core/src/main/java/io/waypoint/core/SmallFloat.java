package io.waypoint.core;

/**
 * Byte-quantised document lengths, bit-for-bit identical to Lucene's
 * {@code org.apache.lucene.util.SmallFloat}.
 *
 * <p>This is copied deliberately rather than approximated. Lucene stores a
 * document's length as a single byte and scores against the decoded value, so
 * an engine that keeps exact lengths produces different scores and a different
 * top-k on near-ties. "Matches Lucene's ranking" is only true if the
 * quantisation matches, and {@code SmallFloatParityTest} asserts all 256
 * values against Lucene itself.
 *
 * @see <a href="https://github.com/apache/lucene/blob/releases/lucene/10.5.1/lucene/core/src/java/org/apache/lucene/util/SmallFloat.java">SmallFloat.java</a>
 */
final class SmallFloat {

  private SmallFloat() {}

  /** Float-like encoding for positive longs preserving order and 4 significant bits. */
  static int longToInt4(long i) {
    if (i < 0) {
      throw new IllegalArgumentException("Only supports positive values, got " + i);
    }
    int numBits = 64 - Long.numberOfLeadingZeros(i);
    if (numBits < 4) {
      return (int) i;
    }
    int shift = numBits - 4;
    int encoded = (int) (i >>> shift);
    encoded &= 0x07;
    encoded |= (shift + 1) << 3;
    return encoded;
  }

  static long int4ToLong(int i) {
    long bits = i & 0x07;
    int shift = (i >>> 3) - 1;
    return shift == -1 ? bits : (bits | 0x08) << shift;
  }

  private static final int MAX_INT4 = longToInt4(Integer.MAX_VALUE);
  private static final int NUM_FREE_VALUES = 255 - MAX_INT4;

  /** The norm encoding BM25 uses: {@code Similarity.computeNorm} is exactly this. */
  static byte intToByte4(int i) {
    if (i < 0) {
      throw new IllegalArgumentException("Only supports positive values, got " + i);
    }
    if (i < NUM_FREE_VALUES) {
      return (byte) i;
    }
    return (byte) (NUM_FREE_VALUES + longToInt4(i - NUM_FREE_VALUES));
  }

  static int byte4ToInt(byte b) {
    int i = Byte.toUnsignedInt(b);
    if (i < NUM_FREE_VALUES) {
      return i;
    }
    return (int) (NUM_FREE_VALUES + int4ToLong(i - NUM_FREE_VALUES));
  }
}
