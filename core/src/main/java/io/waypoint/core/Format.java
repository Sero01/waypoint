package io.waypoint.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * On-disk layout constants and primitive codecs for a Waypoint index.
 *
 * <p>The whole index is one file, written once, never mutated. Every offset in
 * the header is absolute from the start of the file, so opening an index is a
 * single {@code map()} plus a handful of scalar loads: no object graph is
 * constructed, and no section is touched until a query needs it.
 *
 * <pre>
 * +--------------------------------------------------------------+
 * | header      128 bytes, fixed (see the HDR_* offsets below)    |
 * +--------------------------------------------------------------+
 * | postings    per term: interleaved (docGap, freq) varints,     |
 * |             chunked into skippable blocks once df exceeds     |
 * |             POSTINGS_BLOCK (see the block layout below)       |
 * +--------------------------------------------------------------+
 * | positions   optional; per term, per doc, freq position deltas |
 * +--------------------------------------------------------------+
 * | terms       front-coded term data, in blocks of BLOCK_SIZE    |
 * +--------------------------------------------------------------+
 * | blockIndex  long[2*blockCount]: {termDataOff, postingsOff}    |
 * +--------------------------------------------------------------+
 * | blockHeads  int[blockCount+1] offsets, then the raw bytes of  |
 * |             each block's first term (the binary-search keys)  |
 * +--------------------------------------------------------------+
 * | norms       byte[docCount], SmallFloat.intToByte4(docLength)  |
 * +--------------------------------------------------------------+
 * | docKeys     int[docCount+1] offsets, then UTF-8 key bytes     |
 * +--------------------------------------------------------------+
 * | footer      long, CRC32 of everything above                   |
 * +--------------------------------------------------------------+
 * </pre>
 *
 * <p>Byte order is fixed little-endian rather than native so an index built on
 * one machine reads on another.
 */
final class Format {

  private Format() {}

  /** "WYPT". */
  static final int MAGIC = 0x54505957;

  /**
   * v2 adds skippable postings blocks carrying block-max impacts, and per-term
   * impact bounds in the dictionary. v1 files are rejected rather than read:
   * the postings section is not backwards compatible and an index is cheap to
   * rebuild by construction, since building one is already the only way to
   * change one.
   */
  static final int VERSION = 2;

  /** Postings carry term frequencies. Always set. */
  static final int FLAG_FREQS = 1;

  /**
   * The index carries a positions section, so phrase queries work.
   *
   * <p>Off by default. Positions roughly double the file and the build's peak
   * heap, and an index that will never be asked for a phrase should not pay
   * for one. Nothing on the non-phrase read path touches the section, so an
   * index built with positions still opens in one {@code map} and answers term
   * queries at exactly the same speed.
   */
  static final int FLAG_POSITIONS = 2;

  /** Terms per front-coded block. Binary search lands on a block, then scans <= 64 entries. */
  static final int BLOCK_SIZE = 64;

  /**
   * Postings per skippable block, for terms with {@code docFreq} above it.
   *
   * <p>A term at or below this threshold is written as a flat v1-style run with
   * no block headers at all. Paying an 8-byte header to skip fewer than 128
   * postings loses on both size and time, and the overwhelming majority of the
   * 416k terms in a realistic corpus are down there: headers on all of them
   * would cost megabytes to accelerate lists that were never slow.
   *
   * <p>Each block is:
   *
   * <pre>
   *   lastDocDelta   varint, delta from the previous block's last doc
   *   impactLength   varint, bytes of impact data
   *   bodyLength     varint, bytes of body, so the body can be skipped unread
   *   posDelta       varint, only when FLAG_POSITIONS: where this block's
   *                  first document's positions start, as a delta from the
   *                  previous block's. Skipping a block resynchronises the
   *                  position stream for free, because the next header
   *                  restates it.
   *   impacts        impactCount varint, then that many (freq varint, norm
   *                  byte) pairs, norm ascending
   *   body           the same (docGap, freq) varints a flat run uses, with
   *                  gaps continuing across the block boundary
   * </pre>
   *
   * <p>Both lengths come before both variable parts so a reader can step over
   * either without decoding it. A conjunction leapfrogging on document ids
   * never reads an impact, and a scorer that prunes never decodes a body.
   *
   * <p><b>The impact frontier, and why the obvious cheaper thing fails.</b>
   * The first version of this stored one {@code (maxFreq, minNorm)} corner per
   * block. It is a correct upper bound and it pruned essentially nothing,
   * because the corner pairs a block's busiest document with its shortest one
   * even when no single document is both. For a term like {@code the}, whose
   * idf is 0.14 and whose scores are therefore squeezed into a narrow band
   * just under that ceiling, almost every block of 128 documents contains
   * <em>some</em> document with a high frequency and <em>some</em> short one,
   * so the corner sat above the tenth-best score and no block was ever
   * skipped. Measured: no change at all against the unpruned scan.
   *
   * <p>So each block stores its pareto frontier instead: the {@code (freq,
   * norm)} pairs that no other pair in the block dominates, which is what
   * Lucene's impact lists are. The bound is then the best score any document
   * in the block could actually have rather than the best score a document
   * that does not exist could have.
   *
   * <p>Frontier entries are written norm-ascending, which by construction is
   * also freq-ascending, and there are few of them: an entry survives only by
   * setting a record frequency as norms grow.
   */
  static final int POSTINGS_BLOCK = 128;

  /**
   * Hard cap on frontier entries per block, enforced by merging neighbours
   * into a pair that dominates both. Merging can only raise the bound, never
   * lower it, so a capped frontier stays a valid upper bound.
   */
  static final int MAX_IMPACTS = 24;

  static final int HEADER_BYTES = 128;
  static final int FOOTER_BYTES = 8;

  static final int HDR_MAGIC = 0;
  static final int HDR_VERSION = 4;
  static final int HDR_FLAGS = 8;
  static final int HDR_DOC_COUNT = 12;
  static final int HDR_TERM_COUNT = 16;
  static final int HDR_BLOCK_COUNT = 20;
  static final int HDR_BLOCK_SIZE = 24;
  static final int HDR_MAX_TERM_LEN = 28;
  static final int HDR_TOTAL_TOKENS = 32;
  static final int HDR_BLOCK_INDEX_OFF = 40;
  static final int HDR_BLOCK_HEADS_IDX_OFF = 48;
  static final int HDR_BLOCK_HEADS_DATA_OFF = 56;
  static final int HDR_TERMS_OFF = 64;
  static final int HDR_POSTINGS_OFF = 72;
  static final int HDR_NORMS_OFF = 80;
  static final int HDR_DOCKEYS_IDX_OFF = 88;
  static final int HDR_DOCKEYS_DATA_OFF = 96;
  static final int HDR_FOOTER_OFF = 104;

  /** Start of the positions section; 0 when {@link #FLAG_POSITIONS} is clear. */
  static final int HDR_POSITIONS_OFF = 112;

  /**
   * Lucene stops counting matches at this many and reports a lower bound
   * ({@code IndexSearcher.TOTAL_HITS_THRESHOLD = 1000}).
   *
   * <p>Waypoint does not, and the constant is kept only to name the asymmetry.
   * Capping our own count would be cosmetics rather than a saving: Lucene's
   * lower bound is a by-product of block-max pruning, which lets it stop
   * visiting postings entirely, whereas Waypoint has no pruning and must scan
   * every posting to find the top k regardless. The count is therefore free
   * once the scan is happening, and the real cost -- the missing pruning -- is
   * reported as a query-latency loss instead of being hidden behind a matching
   * output format.
   */
  static final int LUCENE_TOTAL_HITS_THRESHOLD = 1000;

  static final ValueLayout.OfInt I32 =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final ValueLayout.OfLong I64 =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
  static final ValueLayout.OfByte I8 = ValueLayout.JAVA_BYTE;

  // ---------------------------------------------------------------- varints

  /** Number of bytes {@link #writeVLong} would emit for {@code v} (v >= 0). */
  static int vLongLength(long v) {
    int n = 1;
    while ((v & ~0x7FL) != 0) {
      v >>>= 7;
      n++;
    }
    return n;
  }

  /** Writes {@code v >= 0} LEB128-style into {@code out} at {@code pos}, returning the new pos. */
  static int writeVLong(byte[] out, int pos, long v) {
    while ((v & ~0x7FL) != 0) {
      out[pos++] = (byte) ((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out[pos] = (byte) v;
    return pos + 1;
  }

  static int writeVInt(byte[] out, int pos, int v) {
    return writeVLong(out, pos, v & 0xFFFFFFFFL);
  }

  static long readVLong(MemorySegment seg, long[] pos) {
    long p = pos[0];
    long result = 0;
    int shift = 0;
    while (true) {
      int b = seg.get(I8, p++) & 0xFF;
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    pos[0] = p;
    return result;
  }

  static int readVInt(MemorySegment seg, long[] pos) {
    return (int) readVLong(seg, pos);
  }

  /** Unsigned lexicographic comparison, the order Lucene sorts terms in. */
  static int compareBytes(byte[] a, int aLen, byte[] b, int bLen) {
    int n = Math.min(aLen, bLen);
    for (int i = 0; i < n; i++) {
      int d = (a[i] & 0xFF) - (b[i] & 0xFF);
      if (d != 0) {
        return d;
      }
    }
    return aLen - bLen;
  }
}
