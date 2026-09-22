package io.waypoint.core;

import java.lang.foreign.MemorySegment;

/**
 * A postings iterator over one term, reading straight off the mapped file.
 *
 * <p>Postings are stored as interleaved {@code (docGap, freq)} varints with the
 * common case folded into the gap: the low bit of the gap word is 1 when the
 * frequency is 1, which it is for the large majority of postings, so most
 * entries cost a single varint. This is the old Lucene trick, kept because it
 * is cheap to decode with no block machinery at all.
 *
 * <p><b>Blocks.</b> Once {@code docFreq} exceeds {@link Format#POSTINGS_BLOCK}
 * the list is chunked, and each chunk is prefixed with its last document, its
 * body length in bytes, and the {@code (maxFreq, minNorm)} pair that bounds
 * every score inside it. That buys two things v1 did not have: {@link #advance}
 * leapfrogs whole blocks instead of decoding every posting up to the target,
 * and a scorer holding a full heap can call {@link #blockMaxScore} and skip a
 * block whose best possible document still loses.
 *
 * <p>Short lists stay flat, with no headers at all. They were never the
 * problem, and the header would cost more than the skip saves.
 *
 * <p>The bound is deliberately weaker than Lucene's impact lists, which store a
 * pareto frontier of {@code (freq, norm)} pairs per block rather than the
 * corner of the bounding box. Waypoint's is looser and therefore prunes less,
 * and it costs two fields per block instead of a list.
 */
final class Cursor {

  static final int NO_MORE_DOCS = Integer.MAX_VALUE;

  private final MemorySegment seg;

  /** Read position: inside the current block body, or at the next block header. */
  private long pos;

  /** Postings left in the whole list, including the unread part of this block. */
  private int remaining;

  /** False for a short flat list, which has one implicit unbounded block. */
  private final boolean blocked;

  /** BM25 {@code idf * boost} for this term; constant for the life of the cursor. */
  final float weight;

  final int docFreq;

  int doc = -1;
  int freq;

  /** Postings left in the current block. */
  private int blockRemaining;

  /** Byte position just past the current block's body. */
  private long blockEnd;

  /** Last document in the current block; also the gap base once it is skipped. */
  private int blockLastDoc;

  /** The current block's impact frontier, reused across blocks. */
  private final int[] impactFreq = new int[Format.MAX_IMPACTS];

  private final int[] impactNorm = new int[Format.MAX_IMPACTS];

  /** Negative until the current block's frontier has actually been decoded. */
  private int impactCount = -1;

  /** Where the current block's impact data starts, if it is still undecoded. */
  private long impactPos;

  /** True when the index stores positions, so block headers carry a position delta. */
  private final boolean indexHasPositions;

  /** True when this cursor is keeping the position stream in step. Phrase queries only. */
  private final boolean trackPositions;

  /** Read pointer into the positions section. */
  private long posPtr;

  /** Positions of the current document not yet read or skipped. */
  private int pendingPositions;

  /** Absolute position of the last one read; positions are stored as deltas. */
  private int lastPosition;

  /** Where the current block's positions start, accumulated from block deltas. */
  private long blockPosStart;

  /** Whole-list impact bound, read from the term dictionary rather than the postings. */
  private final int termMaxFreq;

  private final int termMinNorm;

  Cursor(
      MemorySegment seg,
      long postingsOffset,
      int docFreq,
      float weight,
      int termMaxFreq,
      int termMinNorm,
      boolean indexHasPositions,
      boolean trackPositions,
      long positionsOffset) {
    this.seg = seg;
    this.pos = postingsOffset;
    this.remaining = docFreq;
    this.docFreq = docFreq;
    this.weight = weight;
    this.termMaxFreq = termMaxFreq;
    this.termMinNorm = termMinNorm;
    this.indexHasPositions = indexHasPositions;
    this.trackPositions = trackPositions;
    this.posPtr = positionsOffset;
    this.blockPosStart = positionsOffset;
    this.blocked = docFreq > Format.POSTINGS_BLOCK;
    // A flat list is one block that is already open and cannot be bounded.
    this.blockRemaining = this.blocked ? 0 : docFreq;
  }

  /**
   * An upper bound on any score this term can contribute to any document.
   *
   * <p>Free: the dictionary carries the pair, so a disjunction can rank its
   * clauses before opening a single postings block.
   */
  float termMaxScore(float[] normCache) {
    return Bm25.score(weight, termMaxFreq, normCache[termMinNorm]);
  }

  /** Advances to the next posting, or to {@link #NO_MORE_DOCS}. */
  boolean next() {
    if (blockRemaining == 0 && !nextBlock()) {
      return false;
    }
    return nextInBlock();
  }

  /**
   * Opens the next block, or returns false and parks at {@link #NO_MORE_DOCS}.
   *
   * <p>Call only at a block boundary. After it returns true the block's bound
   * is readable through {@link #blockMaxScore} and the block can either be
   * decoded with {@link #nextInBlock} or discarded with {@link #skipBlock}.
   */
  boolean nextBlock() {
    if (remaining == 0) {
      doc = NO_MORE_DOCS;
      return false;
    }
    if (blocked) {
      readBlockHeader();
    } else {
      blockRemaining = remaining;
    }
    return true;
  }

  /** Postings in the block opened by the last {@link #nextBlock}. */
  int blockCount() {
    return blockRemaining;
  }

  /**
   * An upper bound on every score in the current block, or {@link Float#MAX_VALUE}
   * for a flat list, which carries no frontier and is never worth pruning.
   *
   * <p>The bound is the best score over the block's impact frontier, so it is
   * attained by a document that exists rather than by an imaginary one holding
   * the block's highest frequency in its shortest document.
   */
  float blockMaxScore(float[] normCache) {
    if (!blocked) {
      return Float.MAX_VALUE;
    }
    if (impactCount < 0) {
      readImpacts();
    }
    float best = 0f;
    for (int i = 0; i < impactCount; i++) {
      float s = Bm25.score(weight, impactFreq[i], normCache[impactNorm[i]]);
      if (s > best) {
        best = s;
      }
    }
    return best;
  }

  /**
   * Discards the current block unread, leaving {@link #doc} on its last
   * document so the gap chain continues correctly into the next one.
   */
  void skipBlock() {
    remaining -= blockRemaining;
    blockRemaining = 0;
    doc = blockLastDoc;
    pos = blockEnd;
  }

  /** Decodes one posting from the open block; false once the block is spent. */
  boolean nextInBlock() {
    if (blockRemaining == 0) {
      return false;
    }
    blockRemaining--;
    remaining--;
    if (trackPositions) {
      skipPendingPositions();
    }

    long p = pos;
    long code = 0;
    int shift = 0;
    while (true) {
      int b = seg.get(Format.I8, p++) & 0xFF;
      code |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    int gap = (int) (code >>> 1);
    doc = (doc < 0 ? 0 : doc) + gap;

    if ((code & 1L) != 0) {
      freq = 1;
    } else {
      int f = 0;
      shift = 0;
      while (true) {
        int b = seg.get(Format.I8, p++) & 0xFF;
        f |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      freq = f;
    }
    pos = p;
    if (trackPositions) {
      pendingPositions = freq;
    }
    return true;
  }

  /**
   * Advances to the first document {@code >= target}.
   *
   * <p>Blocks whose last document is below the target are skipped without
   * decoding, so a conjunction driven by a rare term no longer pays for every
   * posting of its common one. Inside the one block that can contain the
   * target the scan is still linear, which is what {@link Format#POSTINGS_BLOCK}
   * bounds.
   */
  boolean advance(int target) {
    while (doc < target) {
      if (blockRemaining == 0 && !nextBlock()) {
        return false;
      }
      // The test belongs here rather than only after opening a block. A cursor
      // parked halfway through a block whose last document is still below the
      // target used to decode the rest of it one posting at a time, which on a
      // long list is up to 127 wasted decodes per advance and was most of what
      // a conjunction against a common term actually cost.
      if (blocked && blockLastDoc < target) {
        skipBlock();
        continue;
      }
      nextInBlock();
    }
    return true;
  }

  private void readBlockHeader() {
    blockLastDoc += (int) readVLong();
    int impactLength = (int) readVLong();
    int bodyLength = (int) readVLong();
    if (indexHasPositions) {
      // Restated every block, so a skipped block resynchronises for free.
      blockPosStart += readVLong();
      posPtr = blockPosStart;
      pendingPositions = 0;
    }
    impactPos = pos;
    impactCount = -1; // decoded only if someone asks for the bound
    pos += impactLength;
    blockEnd = pos + bodyLength;
    blockRemaining = Math.min(Format.POSTINGS_BLOCK, remaining);
  }

  // -------------------------------------------------------------- positions

  /**
   * Reads the next position of the current document.
   *
   * <p>Valid only on a cursor opened to track positions, and only for as many
   * calls as the current document's frequency. The stream is forward-only, so
   * a phrase scorer reads each document's positions once.
   */
  int nextPosition() {
    pendingPositions--;
    long p = posPtr;
    long v = 0;
    int shift = 0;
    while (true) {
      int b = seg.get(Format.I8, p++) & 0xFF;
      v |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    posPtr = p;
    lastPosition += (int) v;
    return lastPosition;
  }

  /**
   * Steps over whatever positions of the current document were never read, so
   * the stream lines up with the next posting.
   */
  private void skipPendingPositions() {
    while (pendingPositions > 0) {
      pendingPositions--;
      while ((seg.get(Format.I8, posPtr++) & 0x80) != 0) {
        // varint continuation
      }
    }
    lastPosition = 0;
  }

  /** Decodes the frontier the current block header pointed at. */
  private void readImpacts() {
    long save = pos;
    pos = impactPos;
    int n = (int) readVLong();
    for (int i = 0; i < n; i++) {
      impactFreq[i] = (int) readVLong();
      impactNorm[i] = seg.get(Format.I8, pos++) & 0xFF;
    }
    impactCount = n;
    pos = save;
  }

  /**
   * Reads a varint at {@link #pos} and steps over it. Used only by the block
   * header, which runs once per {@link Format#POSTINGS_BLOCK} postings; the
   * per-posting decode in {@link #nextInBlock} stays inlined by hand.
   */
  private long readVLong() {
    long p = pos;
    long v = 0;
    int shift = 0;
    while (true) {
      int b = seg.get(Format.I8, p++) & 0xFF;
      v |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    pos = p;
    return v;
  }
}
