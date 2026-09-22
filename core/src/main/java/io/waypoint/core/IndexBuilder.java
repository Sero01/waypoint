package io.waypoint.core;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Builds a Waypoint index. Once. There is no reopening, appending or merging.
 *
 * <p>Postings are accumulated in memory and written in a single pass on
 * {@link #close()}. That is a real constraint -- peak heap scales with the
 * corpus -- and it is accepted rather than engineered around, because build
 * throughput is explicitly not an axis this project competes on. The Lucene
 * teardown measured analysis at 74% of a naive build, which caps any
 * index-build win at roughly 1.3x end to end; a benchmark of that number is
 * mostly a benchmark of tokenizers.
 *
 * <p>Documents with no tokens are rejected rather than silently indexed. An
 * empty document would still occupy a document id and inflate {@code docCount},
 * which feeds {@code idf} -- so accepting one would quietly break score parity
 * with Lucene, which does not index a field that produces no terms. Callers
 * filter; the contract stays exact.
 *
 * <pre>{@code
 * try (IndexBuilder b = IndexBuilder.create(path)) {
 *   b.addText("doc-42", "the quick brown fox");
 * }
 * }</pre>
 *
 * Not thread-safe.
 */
public final class IndexBuilder implements AutoCloseable {

  /** Growable postings for one term, in document order. */
  private static final class Postings {
    int[] docs = new int[4];
    int[] freqs = new int[4];
    int size;
    int lastDoc = -1;

    /** Every position of this term, concatenated in document order; null when off. */
    int[] positions;

    int positionCount;

    void add(int doc, int position, boolean withPositions) {
      if (withPositions) {
        if (positions == null) {
          positions = new int[4];
        } else if (positionCount == positions.length) {
          positions = Arrays.copyOf(positions, positionCount + (positionCount >> 1) + 1);
        }
        positions[positionCount++] = position;
      }
      if (doc == lastDoc) {
        freqs[size - 1]++;
        return;
      }
      if (size == docs.length) {
        int n = size + (size >> 1) + 1;
        docs = Arrays.copyOf(docs, n);
        freqs = Arrays.copyOf(freqs, n);
      }
      docs[size] = doc;
      freqs[size] = 1;
      size++;
      lastDoc = doc;
    }
  }

  /** An output stream that knows how far it has written. */
  private static final class CountingOut extends OutputStream {
    private final OutputStream out;
    long written;

    CountingOut(OutputStream out) {
      this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      written += len;
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }

  private final Path path;
  private final boolean withPositions;
  private final HashMap<String, Postings> terms = new HashMap<>();
  private final ArrayList<String> docKeys = new ArrayList<>();
  private byte[] norms = new byte[1024];
  private int docCount;
  private long totalTokens;
  private boolean closed;

  private IndexBuilder(Path path, boolean withPositions) {
    this.path = path;
    this.withPositions = withPositions;
  }

  /** A frequencies-and-norms index. Phrase queries will be rejected at search time. */
  public static IndexBuilder create(Path path) {
    return create(path, false);
  }

  /**
   * Creates a builder, optionally recording token positions.
   *
   * <p>Positions are what make {@link Query#phrase} work, and they are off by
   * default because they roughly double both the file and the build's peak
   * heap. Nothing on the term, conjunction or disjunction path reads the
   * section, so turning them on costs those queries nothing at all: the file
   * still opens in one {@code map}, and the extra bytes are simply never
   * touched.
   */
  public static IndexBuilder create(Path path, boolean withPositions) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
    return new IndexBuilder(path, withPositions);
  }

  /** Indexes {@code text} using the bundled {@link Tokenizer}. */
  public void addText(String key, String text) {
    add(key, Tokenizer.tokenize(text));
  }

  /** Indexes a caller-supplied token stream, in order. */
  public void add(String key, List<String> tokens) {
    if (closed) {
      throw new IllegalStateException("builder is closed");
    }
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("document key must be non-empty");
    }
    if (tokens == null || tokens.isEmpty()) {
      throw new IllegalArgumentException(
          "document '" + key + "' has no tokens; filter empty documents before indexing");
    }
    int doc = docCount++;
    docKeys.add(key);
    for (int i = 0; i < tokens.size(); i++) {
      terms.computeIfAbsent(tokens.get(i), unused -> new Postings()).add(doc, i, withPositions);
    }
    totalTokens += tokens.size();

    // Lucene: Similarity.computeNorm -> SmallFloat.intToByte4(length - overlaps).
    // Our analyzer never emits an overlap, so length is the token count.
    if (doc == norms.length) {
      norms = Arrays.copyOf(norms, norms.length + (norms.length >> 1) + 1);
    }
    norms[doc] = SmallFloat.intToByte4(tokens.size());
  }

  public int docCount() {
    return docCount;
  }

  /** Writes the index file. */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    // ---- sort terms into UTF-8 byte order, the order Lucene uses ----------
    int termCount = terms.size();
    byte[][] termBytes = new byte[termCount][];
    Postings[] termPostings = new Postings[termCount];
    {
      Integer[] order = new Integer[termCount];
      String[] keys = terms.keySet().toArray(new String[0]);
      byte[][] raw = new byte[termCount][];
      for (int i = 0; i < termCount; i++) {
        raw[i] = keys[i].getBytes(StandardCharsets.UTF_8);
        order[i] = i;
      }
      Arrays.sort(
          order,
          (a, b) -> Format.compareBytes(raw[a], raw[a].length, raw[b], raw[b].length));
      for (int i = 0; i < termCount; i++) {
        int src = order[i];
        termBytes[i] = raw[src];
        termPostings[i] = terms.get(keys[src]);
      }
    }
    int maxTermLen = 0;
    for (int i = 0; i < termCount; i++) {
      maxTermLen = Math.max(maxTermLen, termBytes[i].length);
    }

    int blockSize = Format.BLOCK_SIZE;
    int blockCount = (termCount + blockSize - 1) / blockSize;

    Files.createDirectories(path.toAbsolutePath().getParent());

    long positionsOff;
    long postingsOff;
    long termsOff;
    long blockIndexOff;
    long blockHeadsIdxOff;
    long blockHeadsDataOff;
    long normsOff;
    long docKeysIdxOff;
    long docKeysDataOff;
    long footerOff;

    long[] postOffsets = new long[termCount];
    long[] blockTermDataOff = new long[blockCount];

    // Per-term impact bounds, written into the dictionary so a query can bound
    // a whole clause without touching its postings. Computed while the
    // postings are written, which is the only pass that sees every (freq,norm).
    int[] termMaxFreq = new int[termCount];
    int[] termMinNorm = new int[termCount];

    byte[] scratch = new byte[16];
    // Worst case per posting: a 5-byte gap code plus a 5-byte freq.
    byte[] blockBuf = new byte[Format.POSTINGS_BLOCK * 12];

    // Scratch for the per-block impact frontier. maxFreqByNorm is indexed by
    // norm byte and kept zeroed between blocks by resetting only the entries
    // touched, so a block costs work proportional to its own postings rather
    // than to the 256-wide table.
    int[] maxFreqByNorm = new int[256];
    int[] touched = new int[Format.POSTINGS_BLOCK];
    int touchedCount = 0;
    int[] impactFreq = new int[Format.MAX_IMPACTS];
    int[] impactNorm = new int[Format.MAX_IMPACTS];
    byte[] impactBuf = new byte[5 + Format.MAX_IMPACTS * 6];

    // Where each term's positions start, and where each chunked term's
    // individual blocks start inside them. Both are filled by the positions
    // pass and read back by the postings pass.
    long[] termPosOff = new long[termCount];
    int[] termBlockStart = new int[termCount];
    int totalBlocks = 0;
    if (withPositions) {
      for (int t = 0; t < termCount; t++) {
        int size = termPostings[t].size;
        if (size > Format.POSTINGS_BLOCK) {
          totalBlocks += (size + Format.POSTINGS_BLOCK - 1) / Format.POSTINGS_BLOCK;
        }
      }
    }
    long[] blockPosOff = new long[totalBlocks];

    try (CountingOut out =
        new CountingOut(new BufferedOutputStream(new FileOutputStream(path.toFile()), 1 << 16))) {

      out.write(new byte[Format.HEADER_BYTES]); // placeholder, patched below

      // ---- positions -----------------------------------------------------
      // Written before the postings because each postings block header has to
      // name the point in this section where its first document's positions
      // begin. Section order is free: every offset in the header is absolute.
      positionsOff = out.written;
      if (withPositions) {
        int blockIdx = 0;
        for (int t = 0; t < termCount; t++) {
          Postings p = termPostings[t];
          termPosOff[t] = out.written;
          termBlockStart[t] = blockIdx;
          boolean chunked = p.size > Format.POSTINGS_BLOCK;
          int posIdx = 0;
          for (int i = 0; i < p.size; i++) {
            if (chunked && (i % Format.POSTINGS_BLOCK) == 0) {
              blockPosOff[blockIdx++] = out.written;
            }
            int f = p.freqs[i];
            int last = 0;
            for (int j = 0; j < f; j++) {
              int position = p.positions[posIdx++];
              writeVLong(out, scratch, position - last);
              last = position;
            }
          }
        }
      }

      // ---- postings ------------------------------------------------------
      postingsOff = out.written;
      for (int t = 0; t < termCount; t++) {
        postOffsets[t] = out.written;
        Postings p = termPostings[t];
        int termMax = 0;
        int termMin = 255;
        int prev = 0;

        if (p.size <= Format.POSTINGS_BLOCK) {
          // Flat run, byte-identical to the v1 encoding. Short lists were never
          // the problem and a header here would cost more than it saves.
          for (int i = 0; i < p.size; i++) {
            int gap = p.docs[i] - prev;
            prev = p.docs[i];
            int freq = p.freqs[i];
            if (freq > termMax) {
              termMax = freq;
            }
            int nb = norms[p.docs[i]] & 0xFF;
            if (nb < termMin) {
              termMin = nb;
            }
            // Fold the overwhelmingly common freq==1 case into the gap's low
            // bit, so most postings cost a single varint and no freq at all.
            long code = ((long) gap << 1) | (freq == 1 ? 1L : 0L);
            writeVLong(out, scratch, code);
            if (freq != 1) {
              writeVLong(out, scratch, freq);
            }
          }
        } else {
          int prevBlockLast = 0;
          long prevBlockPos = termPosOff[t];
          int blockNum = 0;
          for (int start = 0; start < p.size; start += Format.POSTINGS_BLOCK) {
            int end = Math.min(start + Format.POSTINGS_BLOCK, p.size);
            int blockMax = 0;
            int blockMin = 255;
            int bp = 0;
            for (int i = start; i < end; i++) {
              int gap = p.docs[i] - prev;
              prev = p.docs[i];
              int freq = p.freqs[i];
              if (freq > blockMax) {
                blockMax = freq;
              }
              int nb = norms[p.docs[i]] & 0xFF;
              if (nb < blockMin) {
                blockMin = nb;
              }
              if (freq > maxFreqByNorm[nb]) {
                if (maxFreqByNorm[nb] == 0) {
                  touched[touchedCount++] = nb;
                }
                maxFreqByNorm[nb] = freq;
              }
              long code = ((long) gap << 1) | (freq == 1 ? 1L : 0L);
              bp = Format.writeVLong(blockBuf, bp, code);
              if (freq != 1) {
                bp = Format.writeVLong(blockBuf, bp, freq);
              }
            }

            int impacts = frontier(maxFreqByNorm, impactFreq, impactNorm);
            for (int i = 0; i < touchedCount; i++) {
              maxFreqByNorm[touched[i]] = 0;
            }
            touchedCount = 0;

            int ip = Format.writeVLong(impactBuf, 0, impacts);
            for (int i = 0; i < impacts; i++) {
              ip = Format.writeVLong(impactBuf, ip, impactFreq[i]);
              impactBuf[ip++] = (byte) impactNorm[i];
            }

            // Both variable parts are buffered only so their lengths can
            // precede them, which is what lets a reader step over either one
            // without decoding it.
            int lastDoc = p.docs[end - 1];
            writeVLong(out, scratch, lastDoc - prevBlockLast);
            prevBlockLast = lastDoc;
            writeVLong(out, scratch, ip);
            writeVLong(out, scratch, bp);
            if (withPositions) {
              long blockPos = blockPosOff[termBlockStart[t] + blockNum];
              writeVLong(out, scratch, blockPos - prevBlockPos);
              prevBlockPos = blockPos;
            }
            blockNum++;
            out.write(impactBuf, 0, ip);
            out.write(blockBuf, 0, bp);

            if (blockMax > termMax) {
              termMax = blockMax;
            }
            if (blockMin < termMin) {
              termMin = blockMin;
            }
          }
        }

        termMaxFreq[t] = termMax;
        termMinNorm[t] = termMin;
      }

      // ---- front-coded term dictionary -----------------------------------
      termsOff = out.written;
      for (int b = 0; b < blockCount; b++) {
        blockTermDataOff[b] = out.written;
        int start = b * blockSize;
        int end = Math.min(start + blockSize, termCount);
        long prevPostOff = postOffsets[start];
        long prevPosOff = withPositions ? termPosOff[start] : 0;
        byte[] prevTerm = null;
        for (int t = start; t < end; t++) {
          byte[] cur = termBytes[t];
          int prefix = 0;
          if (prevTerm != null) {
            int max = Math.min(prevTerm.length, cur.length);
            while (prefix < max && prevTerm[prefix] == cur[prefix]) {
              prefix++;
            }
          }
          writeVLong(out, scratch, prefix);
          writeVLong(out, scratch, cur.length - prefix);
          out.write(cur, prefix, cur.length - prefix);
          writeVLong(out, scratch, termPostings[t].size);
          writeVLong(out, scratch, termMaxFreq[t]);
          out.write(termMinNorm[t]);
          writeVLong(out, scratch, postOffsets[t] - prevPostOff);
          prevPostOff = postOffsets[t];
          if (withPositions) {
            writeVLong(out, scratch, termPosOff[t] - prevPosOff);
            prevPosOff = termPosOff[t];
          }
          prevTerm = cur;
        }
      }

      // ---- block index: {termDataOffset, postingsOffset} per block --------
      blockIndexOff = out.written;
      {
        ByteBuffer bb = ByteBuffer.allocate(blockCount * 24).order(ByteOrder.LITTLE_ENDIAN);
        for (int b = 0; b < blockCount; b++) {
          bb.putLong(blockTermDataOff[b]);
          bb.putLong(postOffsets[b * blockSize]);
          // Base for the block's positions delta chain; 0 when positions are off.
          bb.putLong(withPositions ? termPosOff[b * blockSize] : 0L);
        }
        out.write(bb.array());
      }

      // ---- block head terms: the binary-search keys -----------------------
      blockHeadsIdxOff = out.written;
      {
        ByteBuffer bb = ByteBuffer.allocate((blockCount + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        int acc = 0;
        for (int b = 0; b < blockCount; b++) {
          bb.putInt(acc);
          acc += termBytes[b * blockSize].length;
        }
        bb.putInt(acc);
        out.write(bb.array());
      }
      blockHeadsDataOff = out.written;
      for (int b = 0; b < blockCount; b++) {
        out.write(termBytes[b * blockSize]);
      }

      // ---- norms ----------------------------------------------------------
      normsOff = out.written;
      out.write(norms, 0, docCount);

      // ---- external document keys ------------------------------------------
      docKeysIdxOff = out.written;
      byte[][] keyBytes = new byte[docCount][];
      {
        ByteBuffer bb = ByteBuffer.allocate((docCount + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
        int acc = 0;
        for (int d = 0; d < docCount; d++) {
          keyBytes[d] = docKeys.get(d).getBytes(StandardCharsets.UTF_8);
          bb.putInt(acc);
          acc += keyBytes[d].length;
        }
        bb.putInt(acc);
        out.write(bb.array());
      }
      docKeysDataOff = out.written;
      for (int d = 0; d < docCount; d++) {
        out.write(keyBytes[d]);
      }

      footerOff = out.written;
    }

    // ---- patch the header, then checksum the whole thing -------------------
    ByteBuffer hdr = ByteBuffer.allocate(Format.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    hdr.putInt(Format.HDR_MAGIC, Format.MAGIC);
    hdr.putInt(Format.HDR_VERSION, Format.VERSION);
    hdr.putInt(Format.HDR_FLAGS, Format.FLAG_FREQS | (withPositions ? Format.FLAG_POSITIONS : 0));
    hdr.putInt(Format.HDR_DOC_COUNT, docCount);
    hdr.putInt(Format.HDR_TERM_COUNT, termCount);
    hdr.putInt(Format.HDR_BLOCK_COUNT, blockCount);
    hdr.putInt(Format.HDR_BLOCK_SIZE, blockSize);
    hdr.putInt(Format.HDR_MAX_TERM_LEN, maxTermLen);
    hdr.putLong(Format.HDR_TOTAL_TOKENS, totalTokens);
    hdr.putLong(Format.HDR_BLOCK_INDEX_OFF, blockIndexOff);
    hdr.putLong(Format.HDR_BLOCK_HEADS_IDX_OFF, blockHeadsIdxOff);
    hdr.putLong(Format.HDR_BLOCK_HEADS_DATA_OFF, blockHeadsDataOff);
    hdr.putLong(Format.HDR_TERMS_OFF, termsOff);
    hdr.putLong(Format.HDR_POSTINGS_OFF, postingsOff);
    hdr.putLong(Format.HDR_NORMS_OFF, normsOff);
    hdr.putLong(Format.HDR_DOCKEYS_IDX_OFF, docKeysIdxOff);
    hdr.putLong(Format.HDR_DOCKEYS_DATA_OFF, docKeysDataOff);
    hdr.putLong(Format.HDR_FOOTER_OFF, footerOff);
    hdr.putLong(Format.HDR_POSITIONS_OFF, positionsOff);

    try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
      raf.seek(0);
      raf.write(hdr.array());

      CRC32 crc = new CRC32();
      raf.seek(0);
      byte[] buf = new byte[64 * 1024];
      long remaining = footerOff;
      while (remaining > 0) {
        int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
        if (n <= 0) {
          throw new IOException("unexpected end of file while checksumming " + path);
        }
        crc.update(buf, 0, n);
        remaining -= n;
      }
      ByteBuffer foot = ByteBuffer.allocate(Format.FOOTER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      foot.putLong(0, crc.getValue());
      raf.seek(footerOff);
      raf.write(foot.array());
    }
  }

  /**
   * Reduces a block's {@code (freq, norm)} pairs to its pareto frontier.
   *
   * <p>A pair dominates another when it has at least the frequency and at most
   * the norm, since BM25 rises with frequency and falls with document length.
   * Walking norms in ascending order, from shortest document to longest, a
   * pair therefore survives only by setting a record frequency: anything that
   * does not beat every shorter document's frequency is already covered.
   *
   * <p>Norms are bytes, so the walk is over 256 slots rather than a sort of
   * the block's postings. That is both simpler and faster than sorting 128
   * entries per block across the hundreds of thousands of blocks a real corpus
   * produces.
   *
   * @return the number of frontier entries written to {@code outFreq}/{@code outNorm}
   */
  private static int frontier(int[] maxFreqByNorm, int[] outFreq, int[] outNorm) {
    int n = 0;
    int best = 0;
    for (int norm = 0; norm < 256; norm++) {
      int f = maxFreqByNorm[norm];
      if (f <= best) {
        continue; // dominated by some shorter document with at least this freq
      }
      best = f;
      if (n == outFreq.length) {
        // At the cap: fold into the last entry, which then has the larger freq
        // and the smaller norm and so dominates both. The bound can only rise.
        outFreq[n - 1] = f;
      } else {
        outFreq[n] = f;
        outNorm[n] = norm;
        n++;
      }
    }
    return n;
  }

  private static void writeVLong(OutputStream out, byte[] scratch, long v) throws IOException {
    int n = Format.writeVLong(scratch, 0, v);
    out.write(scratch, 0, n);
  }
}
