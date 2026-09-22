package io.waypoint.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A read-only, single-file, memory-mapped inverted index.
 *
 * <p><b>What opening one costs.</b> One {@code FileChannel.map}, one 128-byte
 * header read, and a bounds check. Nothing else. No codec is resolved through
 * {@code ServiceLoader}, no per-format reader stack is constructed, no term
 * index is decoded into objects, and the checksum is not verified (see
 * {@link #verify()}). Every section of the file is read lazily off the mapped
 * segment when a query first needs it. That is the entire reason this class
 * exists, and the reason it beats Lucene at time-to-first-result while losing
 * to it at almost everything else.
 *
 * <p><b>What it cannot do.</b> The index is built once and frozen: no deletes,
 * no updates, no near-real-time, one segment, no positions and therefore no
 * phrase queries. Lucene pays for every one of those on open. This class
 * measures that bill; it is not a replacement for paying it.
 *
 * <p>Instances are immutable after construction and safe for concurrent
 * searching. {@link #close()} unmaps the file and must not race with searches.
 */
public final class Index implements AutoCloseable {

  private final Arena arena;
  private final MemorySegment seg;
  private final long fileSize;

  private final int docCount;
  private final int termCount;
  private final int blockCount;
  private final int blockSize;
  private final int maxTermLen;
  private final long totalTokens;

  private final long blockIndexOff;
  private final long blockHeadsIdxOff;
  private final long blockHeadsDataOff;
  private final long normsOff;
  private final long docKeysIdxOff;
  private final long docKeysDataOff;
  private final long footerOff;
  private final long positionsOff;
  private final boolean hasPositions;

  private final float avgdl;

  /**
   * The 256-entry BM25 reciprocal cache. Built on first search rather than on
   * open, so that opening an index which is never queried costs nothing.
   * Benign race: two threads may build it, and both results are identical.
   */
  private volatile float[] normCache;

  private Index(
      Arena arena,
      MemorySegment seg,
      long fileSize,
      int docCount,
      int termCount,
      int blockCount,
      int blockSize,
      int maxTermLen,
      long totalTokens,
      long blockIndexOff,
      long blockHeadsIdxOff,
      long blockHeadsDataOff,
      long normsOff,
      long docKeysIdxOff,
      long docKeysDataOff,
      long footerOff,
      long positionsOff,
      boolean hasPositions) {
    this.arena = arena;
    this.seg = seg;
    this.fileSize = fileSize;
    this.docCount = docCount;
    this.termCount = termCount;
    this.blockCount = blockCount;
    this.blockSize = blockSize;
    this.maxTermLen = maxTermLen;
    this.totalTokens = totalTokens;
    this.blockIndexOff = blockIndexOff;
    this.blockHeadsIdxOff = blockHeadsIdxOff;
    this.blockHeadsDataOff = blockHeadsDataOff;
    this.normsOff = normsOff;
    this.docKeysIdxOff = docKeysIdxOff;
    this.docKeysDataOff = docKeysDataOff;
    this.footerOff = footerOff;
    this.positionsOff = positionsOff;
    this.hasPositions = hasPositions;
    this.avgdl = Bm25.avgFieldLength(totalTokens, docCount == 0 ? 1 : docCount);
  }

  // ------------------------------------------------------------------- open

  /**
   * Maps {@code path} and validates its header.
   *
   * <p>Magic and format version are checked; the CRC32 footer is <b>not</b>.
   * Lucene validates a header and retrieves a checksum for every file it opens.
   * We do less work here on purpose, and say so: some of the open-time win is
   * bought by skipping this check. {@link #verify()} performs it on demand.
   */
  public static Index open(Path path) throws IOException {
    Arena arena = Arena.ofShared();
    try {
      long size;
      MemorySegment seg;
      try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
        size = ch.size();
        if (size < Format.HEADER_BYTES + Format.FOOTER_BYTES) {
          throw new IndexFormatException(
              "file is too short to be a Waypoint index: " + size + " bytes");
        }
        seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
      }

      int magic = seg.get(Format.I32, Format.HDR_MAGIC);
      if (magic != Format.MAGIC) {
        throw new IndexFormatException(
            "bad magic: expected 0x"
                + Integer.toHexString(Format.MAGIC)
                + " found 0x"
                + Integer.toHexString(magic));
      }
      int version = seg.get(Format.I32, Format.HDR_VERSION);
      if (version != Format.VERSION) {
        throw new IndexFormatException(
            "unsupported format version: expected " + Format.VERSION + " found " + version);
      }

      int flags = seg.get(Format.I32, Format.HDR_FLAGS);
      int docCount = seg.get(Format.I32, Format.HDR_DOC_COUNT);
      int termCount = seg.get(Format.I32, Format.HDR_TERM_COUNT);
      int blockCount = seg.get(Format.I32, Format.HDR_BLOCK_COUNT);
      int blockSize = seg.get(Format.I32, Format.HDR_BLOCK_SIZE);
      int maxTermLen = seg.get(Format.I32, Format.HDR_MAX_TERM_LEN);
      long totalTokens = seg.get(Format.I64, Format.HDR_TOTAL_TOKENS);

      long blockIndexOff = seg.get(Format.I64, Format.HDR_BLOCK_INDEX_OFF);
      long blockHeadsIdxOff = seg.get(Format.I64, Format.HDR_BLOCK_HEADS_IDX_OFF);
      long blockHeadsDataOff = seg.get(Format.I64, Format.HDR_BLOCK_HEADS_DATA_OFF);
      long termsOff = seg.get(Format.I64, Format.HDR_TERMS_OFF);
      long postingsOff = seg.get(Format.I64, Format.HDR_POSTINGS_OFF);
      long normsOff = seg.get(Format.I64, Format.HDR_NORMS_OFF);
      long docKeysIdxOff = seg.get(Format.I64, Format.HDR_DOCKEYS_IDX_OFF);
      long docKeysDataOff = seg.get(Format.I64, Format.HDR_DOCKEYS_DATA_OFF);
      long footerOff = seg.get(Format.I64, Format.HDR_FOOTER_OFF);
      long positionsOff = seg.get(Format.I64, Format.HDR_POSITIONS_OFF);

      if (docCount < 0 || termCount < 0 || blockCount < 0 || blockSize <= 0 || maxTermLen < 0) {
        throw new IndexFormatException("header contains negative or zero counts");
      }
      checkRange(blockIndexOff, size, "blockIndex");
      checkRange(blockHeadsIdxOff, size, "blockHeadsIdx");
      checkRange(blockHeadsDataOff, size, "blockHeadsData");
      checkRange(termsOff, size, "terms");
      checkRange(postingsOff, size, "postings");
      checkRange(normsOff, size, "norms");
      checkRange(docKeysIdxOff, size, "docKeysIdx");
      checkRange(docKeysDataOff, size, "docKeysData");
      if (footerOff != size - Format.FOOTER_BYTES) {
        throw new IndexFormatException(
            "truncated index: footer offset " + footerOff + " but file is " + size + " bytes");
      }
      if (normsOff + (long) docCount > size) {
        throw new IndexFormatException("truncated index: norms run past end of file");
      }

      return new Index(
          arena,
          seg,
          size,
          docCount,
          termCount,
          blockCount,
          blockSize,
          maxTermLen,
          totalTokens,
          blockIndexOff,
          blockHeadsIdxOff,
          blockHeadsDataOff,
          normsOff,
          docKeysIdxOff,
          docKeysDataOff,
          footerOff,
          positionsOff,
          (flags & Format.FLAG_POSITIONS) != 0);
    } catch (Throwable t) {
      arena.close();
      throw t;
    }
  }

  private static void checkRange(long off, long size, String what) throws IndexFormatException {
    if (off < Format.HEADER_BYTES || off > size) {
      throw new IndexFormatException(
          "truncated index: " + what + " offset " + off + " outside a " + size + " byte file");
    }
  }

  // ------------------------------------------------------------- statistics

  public int docCount() {
    return docCount;
  }

  public int termCount() {
    return termCount;
  }

  /** Total tokens indexed; Lucene's {@code sumTotalTermFreq}. */
  public long totalTokens() {
    return totalTokens;
  }

  /** Average document length in tokens, the {@code avgdl} of BM25. */
  public float averageDocumentLength() {
    return avgdl;
  }

  public long sizeInBytes() {
    return fileSize;
  }

  /** Number of documents containing {@code term}; 0 if the term is absent. */
  public int docFrequency(String term) {
    long[] found = lookup(term);
    return found == null ? 0 : (int) found[0];
  }

  /** The caller-supplied external identifier of internal document {@code doc}. */
  public String docKey(int doc) {
    if (doc < 0 || doc >= docCount) {
      throw new IndexOutOfBoundsException("doc " + doc + " of " + docCount);
    }
    int lo = seg.get(Format.I32, docKeysIdxOff + (long) doc * 4);
    int hi = seg.get(Format.I32, docKeysIdxOff + (long) doc * 4 + 4);
    int len = hi - lo;
    byte[] b = new byte[len];
    long base = docKeysDataOff + lo;
    for (int i = 0; i < len; i++) {
      b[i] = seg.get(Format.I8, base + i);
    }
    return new String(b, StandardCharsets.UTF_8);
  }

  // ----------------------------------------------------------------- search

  /**
   * Returns the top {@code k} documents for {@code query}, ranked by BM25.
   *
   * <p>Single segment, so document ids are already global: there is no
   * per-leaf iteration, no {@code LeafReaderContext}, and no live-docs check
   * per document. There is also no block-max pruning, so this method visits
   * every match. That is a real cost relative to Lucene's
   * {@code MaxScoreBulkScorer}, and it is reported as a loss rather than
   * hidden.
   */
  public Hits search(Query query, int k) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be > 0, got " + k);
    }
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }
    switch (query.kind()) {
      case Query.KIND_TERM:
        return searchTerm((Query.Term) query, k);
      case Query.KIND_AND:
        {
          Query[] clauses = ((Query.And) query).clauses;
          // A one-clause boolean is a term query, and the term path prunes
          // blocks where the general merge loops cannot.
          return clauses.length == 1
              ? searchTerm((Query.Term) clauses[0], k)
              : searchAnd(clauses, k);
        }
      case Query.KIND_OR:
        {
          Query[] clauses = ((Query.Or) query).clauses;
          return clauses.length == 1
              ? searchTerm((Query.Term) clauses[0], k)
              : searchOr(clauses, k);
        }
      case Query.KIND_PHRASE:
        return searchPhrase(((Query.Phrase) query).terms, k);
      default:
        throw new IllegalArgumentException("unknown query kind " + query.kind());
    }
  }

  /** Whether this index stores positions, and therefore answers phrase queries. */
  public boolean hasPositions() {
    return hasPositions;
  }

  /**
   * Exact phrase, scored the way Lucene's {@code PhraseQuery} scores one.
   *
   * <p>Two stages. Leapfrog the terms to find documents containing all of
   * them, which reuses the conjunction machinery and its block skipping, then
   * walk the positions of the survivors to count how often the terms actually
   * appear adjacent and in order.
   *
   * <p>Scoring matches Lucene exactly: the weight is the <em>sum</em> of the
   * clause idfs, as {@code BM25Similarity.idfExplain} computes it over several
   * term statistics, and the frequency fed into BM25 is the number of phrase
   * occurrences rather than any individual term's frequency.
   *
   * <p>Position lists are read into per-term buffers that grow once and are
   * then reused for every candidate, so a phrase over a long corpus does not
   * allocate per document.
   */
  private Hits searchPhrase(String[] terms, int k) {
    if (!hasPositions) {
      throw new IllegalStateException(
          "this index has no positions, so it cannot answer phrase queries; "
              + "rebuild with IndexBuilder.create(path, true)");
    }
    if (terms.length == 1) {
      return searchTerm(Query.term(terms[0]), k);
    }

    int n = terms.length;
    Cursor[] cursors = new Cursor[n];
    float weight = 0f;
    for (int i = 0; i < n; i++) {
      Cursor c = openPositionCursor(terms[i]);
      if (c == null) {
        return empty(); // a missing term makes the phrase impossible
      }
      cursors[i] = c;
      weight += Bm25.idf(c.docFreq, docCount);
    }

    // Leapfrog on the rarest term, but keep query order for the position test,
    // so lead is an index into the unpermuted array rather than a reordering.
    int lead = 0;
    for (int i = 1; i < n; i++) {
      if (cursors[i].docFreq < cursors[lead].docFreq) {
        lead = i;
      }
    }

    int[][] positions = new int[n][16];
    int[] counts = new int[n];
    int[] walk = new int[n];

    float[] cache = normCache();
    TopK heap = new TopK(k);
    long total = 0;
    Cursor driver = cursors[lead];

    while (driver.next()) {
      int candidate = driver.doc;
      boolean all = true;
      boolean exhausted = false;
      for (int i = 0; i < n; i++) {
        if (i == lead) {
          continue;
        }
        Cursor c = cursors[i];
        if (!c.advance(candidate)) {
          exhausted = true; // a clause ran out; nothing further can match
          all = false;
          break;
        }
        if (c.doc != candidate) {
          all = false;
          break;
        }
      }
      if (exhausted) {
        break;
      }
      if (!all) {
        continue;
      }

      for (int i = 0; i < n; i++) {
        Cursor c = cursors[i];
        int f = c.freq;
        if (positions[i].length < f) {
          positions[i] = new int[Math.max(f, positions[i].length * 2)];
        }
        for (int j = 0; j < f; j++) {
          positions[i][j] = c.nextPosition();
        }
        counts[i] = f;
        walk[i] = 0;
      }

      int phraseFreq = phraseFrequency(positions, counts, walk, n);
      if (phraseFreq > 0) {
        total++;
        byte norm = seg.get(Format.I8, normsOff + candidate);
        heap.offer(candidate, Bm25.score(weight, phraseFreq, cache[norm & 0xFF]));
      }
    }
    return collect(heap, total, true);
  }

  /**
   * Counts occurrences of the phrase, given each term's positions in one document.
   *
   * <p>Anchored on the first term: for each of its positions the other terms
   * must sit at exactly one, two, ... further along. The per-term walk pointers
   * never move backwards, because anchors are visited in increasing order, so
   * the whole count is linear in the number of positions rather than quadratic.
   */
  private static int phraseFrequency(int[][] positions, int[] counts, int[] walk, int n) {
    int matches = 0;
    for (int a = 0; a < counts[0]; a++) {
      int base = positions[0][a];
      boolean ok = true;
      for (int t = 1; t < n; t++) {
        int target = base + t;
        int w = walk[t];
        int[] pt = positions[t];
        int len = counts[t];
        while (w < len && pt[w] < target) {
          w++;
        }
        walk[t] = w;
        if (w == len) {
          return matches; // this term has nothing left; no later anchor can match
        }
        if (pt[w] != target) {
          ok = false;
          break;
        }
      }
      if (ok) {
        matches++;
      }
    }
    return matches;
  }

  /**
   * Single term, with block-max pruning.
   *
   * <p>Once the heap is full, a block whose bound cannot beat the worst hit
   * held is skipped without decoding a single posting. The skip is exact
   * rather than approximate: {@link TopK} admits only on a strictly greater
   * score, so no document in a block bounded at or below the current worst
   * could have entered the heap.
   *
   * <p>The match count survives it. The block header says how many postings
   * were skipped, so they are counted without being read, and this method
   * still reports the true total rather than Lucene's lower bound.
   */
  private Hits searchTerm(Query.Term q, int k) {
    Cursor c = openCursor(q.text);
    if (c == null) {
      return empty();
    }
    float[] cache = normCache();
    TopK heap = new TopK(k);
    long total = 0;
    while (c.nextBlock()) {
      if (heap.full() && c.blockMaxScore(cache) <= heap.worstScore()) {
        total += c.blockCount();
        c.skipBlock();
        continue;
      }
      while (c.nextInBlock()) {
        total++;
        heap.offer(c.doc, score(c, cache));
      }
    }
    return collect(heap, total, true);
  }

  private Hits searchAnd(Query[] clauses, int k) {
    Cursor[] cursors = new Cursor[clauses.length];
    for (int i = 0; i < clauses.length; i++) {
      Cursor c = openCursor(((Query.Term) clauses[i]).text);
      if (c == null) {
        return empty(); // a missing term makes the conjunction empty
      }
      cursors[i] = c;
    }
    // Leapfrog is driven by the rarest term, so the cheapest postings list
    // dictates how far the others have to skip.
    sortByDocFreq(cursors);

    float[] cache = normCache();
    TopK heap = new TopK(k);
    long total = 0;

    Cursor lead = cursors[0];
    while (lead.next()) {
      int candidate = lead.doc;
      boolean match = true;
      for (int i = 1; i < cursors.length; i++) {
        Cursor c = cursors[i];
        if (!c.advance(candidate)) {
          return collect(heap, total, true); // a clause is exhausted: no more matches
        }
        if (c.doc != candidate) {
          match = false;
          break;
        }
      }
      if (match) {
        total++;
        float s = score(lead, cache);
        for (int i = 1; i < cursors.length; i++) {
          s += score(cursors[i], cache);
        }
        heap.offer(candidate, s);
      }
    }
    // Exact: advance only skips documents that cannot match, never matches.
    return collect(heap, total, true);
  }

  /**
   * Disjunction, evaluated with MaxScore.
   *
   * <p>Clauses are ordered by the upper bound each can contribute, which the
   * term dictionary carries so the ordering costs no postings reads. Prefix
   * sums of those bounds split the clauses in two: a prefix whose combined
   * best case cannot reach the worst hit currently held is <em>non-essential</em>
   * and stops driving the merge, so a query like {@code the who} stops
   * enumerating all 866k documents containing {@code the} and only checks them
   * where {@code who} already produced a candidate.
   *
   * <p>Document-at-a-time over the essential clauses, with a linear scan for
   * the minimum rather than a priority queue: clause counts are tiny, and the
   * scan loads one fewer class on the first query of a fresh JVM.
   *
   * <p><b>This is the one place the exact match count is given up.</b> A
   * document matching only non-essential clauses is never made a candidate, so
   * once pruning engages the total becomes a lower bound and the returned
   * {@link Hits#totalHitsExact()} says so. That is the same trade Lucene makes
   * with {@code TOTAL_HITS_THRESHOLD}, and it is reported rather than hidden.
   * When no clause is ever demoted the count is still exact.
   */
  private Hits searchOr(Query[] clauses, int k) {
    Cursor[] cursors = new Cursor[clauses.length];
    int n = 0;
    for (int i = 0; i < clauses.length; i++) {
      Cursor c = openCursor(((Query.Term) clauses[i]).text);
      if (c != null && c.next()) {
        cursors[n++] = c;
      }
    }
    if (n == 0) {
      return empty();
    }

    float[] cache = normCache();
    TopK heap = new TopK(k);
    long total = 0;

    float[] maxScores = new float[n];
    for (int i = 0; i < n; i++) {
      maxScores[i] = cursors[i].termMaxScore(cache);
    }
    sortByMaxScore(cursors, maxScores, n);
    // prefix[i] bounds the combined contribution of clauses [0, i).
    float[] prefix = new float[n + 1];
    for (int i = 0; i < n; i++) {
      prefix[i + 1] = prefix[i] + maxScores[i];
    }

    int firstEssential = 0;
    boolean pruned = false;

    while (true) {
      int min = Cursor.NO_MORE_DOCS;
      for (int i = firstEssential; i < n; i++) {
        int d = cursors[i].doc;
        if (d < min) {
          min = d;
        }
      }
      if (min == Cursor.NO_MORE_DOCS) {
        break;
      }

      float s = 0f;
      for (int i = firstEssential; i < n; i++) {
        Cursor c = cursors[i];
        if (c.doc == min) {
          s += score(c, cache);
        }
      }

      // Non-essential clauses, richest bound first. Stop as soon as everything
      // still unread could not lift this document into the heap; the partial
      // score is then provably not admissible, so offering it changes nothing.
      boolean full = heap.full();
      float worst = full ? heap.worstScore() : 0f;
      for (int i = firstEssential - 1; i >= 0; i--) {
        if (full && s + prefix[i + 1] <= worst) {
          break;
        }
        Cursor c = cursors[i];
        c.advance(min);
        if (c.doc == min) {
          s += score(c, cache);
        }
      }

      total++;
      heap.offer(min, s);

      for (int i = firstEssential; i < n; i++) {
        Cursor c = cursors[i];
        if (c.doc == min) {
          c.next();
        }
      }

      if (heap.full()) {
        float w = heap.worstScore();
        int m = firstEssential;
        // At least one clause must stay essential or nothing drives the merge.
        while (m < n - 1 && prefix[m + 1] <= w) {
          m++;
        }
        if (m != firstEssential) {
          firstEssential = m;
          pruned = true;
        }
      }
    }
    return collect(heap, total, !pruned);
  }

  /** Insertion sort by ascending term max score; clause counts are tiny. */
  private static void sortByMaxScore(Cursor[] cursors, float[] maxScores, int n) {
    for (int i = 1; i < n; i++) {
      Cursor c = cursors[i];
      float m = maxScores[i];
      int j = i - 1;
      while (j >= 0 && maxScores[j] > m) {
        cursors[j + 1] = cursors[j];
        maxScores[j + 1] = maxScores[j];
        j--;
      }
      cursors[j + 1] = c;
      maxScores[j + 1] = m;
    }
  }

  /**
   * The exact number of documents matching {@code query}, always.
   *
   * <p>{@link #search} may return a lower bound for a disjunction, because
   * MaxScore stops enumerating clauses that can no longer place a document in
   * the top k. This method exists so that guarantee is recoverable rather than
   * gone: it merges the postings with no heap, no ranking and no BM25
   * arithmetic at all, which makes it cheaper than the search it replaces.
   *
   * <p>A single term costs nothing beyond the dictionary lookup. Its document
   * frequency is the answer.
   */
  public long count(Query query) {
    if (query == null) {
      throw new IllegalArgumentException("query must not be null");
    }
    switch (query.kind()) {
      case Query.KIND_TERM:
        return docFrequency(((Query.Term) query).text);
      case Query.KIND_AND:
        return countAnd(((Query.And) query).clauses);
      case Query.KIND_OR:
        return countOr(((Query.Or) query).clauses);
      case Query.KIND_PHRASE:
        // Phrase matching has to walk positions to know whether a document
        // matches at all, so counting is the search minus the ranking.
        return searchPhrase(((Query.Phrase) query).terms, 1).totalHits();
      default:
        throw new IllegalArgumentException("unknown query kind " + query.kind());
    }
  }

  private long countAnd(Query[] clauses) {
    Cursor[] cursors = new Cursor[clauses.length];
    for (int i = 0; i < clauses.length; i++) {
      Cursor c = openCursor(((Query.Term) clauses[i]).text);
      if (c == null) {
        return 0;
      }
      cursors[i] = c;
    }
    sortByDocFreq(cursors);

    long total = 0;
    Cursor lead = cursors[0];
    while (lead.next()) {
      int candidate = lead.doc;
      boolean match = true;
      for (int i = 1; i < cursors.length; i++) {
        Cursor c = cursors[i];
        if (!c.advance(candidate)) {
          return total;
        }
        if (c.doc != candidate) {
          match = false;
          break;
        }
      }
      if (match) {
        total++;
      }
    }
    return total;
  }

  private long countOr(Query[] clauses) {
    Cursor[] cursors = new Cursor[clauses.length];
    int n = 0;
    for (int i = 0; i < clauses.length; i++) {
      Cursor c = openCursor(((Query.Term) clauses[i]).text);
      if (c != null && c.next()) {
        cursors[n++] = c;
      }
    }
    if (n == 1) {
      return cursors[0].docFreq; // already positioned, but df is the whole answer
    }
    long total = 0;
    while (true) {
      int min = Cursor.NO_MORE_DOCS;
      for (int i = 0; i < n; i++) {
        int d = cursors[i].doc;
        if (d < min) {
          min = d;
        }
      }
      if (min == Cursor.NO_MORE_DOCS) {
        return total;
      }
      total++;
      for (int i = 0; i < n; i++) {
        Cursor c = cursors[i];
        if (c.doc == min) {
          c.next();
        }
      }
    }
  }

  private float score(Cursor c, float[] cache) {
    byte norm = seg.get(Format.I8, normsOff + c.doc);
    return Bm25.score(c.weight, c.freq, cache[norm & 0xFF]);
  }

  /** Insertion sort by ascending docFreq; clause counts are tiny. */
  private static void sortByDocFreq(Cursor[] cursors) {
    for (int i = 1; i < cursors.length; i++) {
      Cursor c = cursors[i];
      int j = i - 1;
      while (j >= 0 && cursors[j].docFreq > c.docFreq) {
        cursors[j + 1] = cursors[j];
        j--;
      }
      cursors[j + 1] = c;
    }
  }

  private float[] normCache() {
    float[] c = normCache;
    if (c == null) {
      c = Bm25.normCache(avgdl);
      normCache = c;
    }
    return c;
  }

  private Cursor openCursor(String term) {
    return openCursor(term, false);
  }

  /** A cursor that also keeps the position stream in step. Phrase queries only. */
  private Cursor openPositionCursor(String term) {
    return openCursor(term, true);
  }

  private Cursor openCursor(String term, boolean trackPositions) {
    long[] found = lookup(term);
    if (found == null) {
      return null;
    }
    int df = (int) found[0];
    return new Cursor(
        seg,
        found[1],
        df,
        Bm25.idf(df, docCount),
        (int) found[2],
        (int) found[3],
        hasPositions,
        trackPositions,
        hasPositions ? found[4] : 0L);
  }

  private Hits collect(TopK heap, long total, boolean exact) {
    int n = heap.size();
    int[] docs = new int[n];
    float[] scores = new float[n];
    heap.drain(docs, scores);
    String[] keys = new String[n];
    for (int i = 0; i < n; i++) {
      keys[i] = docKey(docs[i]);
    }
    return new Hits(total, exact, docs, scores, keys);
  }

  private static Hits empty() {
    return new Hits(0, true, new int[0], new float[0], new String[0]);
  }

  // -------------------------------------------------------- term dictionary

  /**
   * Finds {@code term}, returning {@code {docFreq, postingsOffset}} or null.
   *
   * <p>Binary search over the per-block head terms lands on one block, then a
   * linear scan walks at most {@code blockSize} front-coded entries. There is
   * no FST and no term index held in memory: the search reads mapped bytes
   * directly, which is why an index that is never queried costs nothing to
   * open, and why a lookup for an absent term touches only a few cache lines.
   *
   * <p>The varint decoders are inlined rather than factored into a shared
   * cursor object, so the whole lookup allocates exactly two small arrays and
   * keeps no mutable state on the instance -- which is what makes a single
   * {@code Index} safe to search from many threads.
   */
  private long[] lookup(String term) {
    if (termCount == 0 || term == null || term.isEmpty()) {
      return null;
    }
    byte[] target = term.getBytes(StandardCharsets.UTF_8);
    int targetLen = target.length;

    int lo = 0;
    int hi = blockCount - 1;
    int block = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (compareHead(mid, target, targetLen) <= 0) {
        block = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (block < 0) {
      return null; // target sorts before every block head
    }

    long p = seg.get(Format.I64, blockIndexOff + (long) block * 24);
    long postPos = seg.get(Format.I64, blockIndexOff + (long) block * 24 + 8);
    long posPos = seg.get(Format.I64, blockIndexOff + (long) block * 24 + 16);
    int inBlock = Math.min(blockSize, termCount - block * blockSize);

    byte[] scratch = new byte[maxTermLen];
    for (int i = 0; i < inBlock; i++) {
      long v;
      int shift;
      int b;

      // prefixLen
      v = 0;
      shift = 0;
      while (true) {
        b = seg.get(Format.I8, p++) & 0xFF;
        v |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int prefix = (int) v;

      // suffixLen
      v = 0;
      shift = 0;
      while (true) {
        b = seg.get(Format.I8, p++) & 0xFF;
        v |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int suffixLen = (int) v;

      for (int j = 0; j < suffixLen; j++) {
        scratch[prefix + j] = seg.get(Format.I8, p + j);
      }
      p += suffixLen;
      int len = prefix + suffixLen;

      // docFreq
      v = 0;
      shift = 0;
      while (true) {
        b = seg.get(Format.I8, p++) & 0xFF;
        v |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int df = (int) v;

      // maxFreq, the first half of this term's impact bound
      v = 0;
      shift = 0;
      while (true) {
        b = seg.get(Format.I8, p++) & 0xFF;
        v |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      int maxFreq = (int) v;

      // minNorm, the other half; one raw byte, never varint-encoded
      int minNorm = seg.get(Format.I8, p++) & 0xFF;

      // postings offset delta from the previous term (0 for the block head)
      v = 0;
      shift = 0;
      while (true) {
        b = seg.get(Format.I8, p++) & 0xFF;
        v |= ((long) (b & 0x7F)) << shift;
        if ((b & 0x80) == 0) {
          break;
        }
        shift += 7;
      }
      postPos += v;

      if (hasPositions) {
        v = 0;
        shift = 0;
        while (true) {
          b = seg.get(Format.I8, p++) & 0xFF;
          v |= ((long) (b & 0x7F)) << shift;
          if ((b & 0x80) == 0) {
            break;
          }
          shift += 7;
        }
        posPos += v;
      }

      int cmp = Format.compareBytes(scratch, len, target, targetLen);
      if (cmp == 0) {
        return new long[] {df, postPos, maxFreq, minNorm, posPos};
      }
      if (cmp > 0) {
        return null; // terms are sorted; we have passed the target
      }
    }
    return null;
  }

  /** Compares block {@code b}'s head term against {@code target}. */
  private int compareHead(int b, byte[] target, int targetLen) {
    int lo = seg.get(Format.I32, blockHeadsIdxOff + (long) b * 4);
    int hi = seg.get(Format.I32, blockHeadsIdxOff + (long) b * 4 + 4);
    int len = hi - lo;
    long base = blockHeadsDataOff + lo;
    int n = Math.min(len, targetLen);
    for (int i = 0; i < n; i++) {
      int d = (seg.get(Format.I8, base + i) & 0xFF) - (target[i] & 0xFF);
      if (d != 0) {
        return d;
      }
    }
    return len - targetLen;
  }

  // ----------------------------------------------------------------- verify

  /**
   * Recomputes the CRC32 footer over the whole file and throws if it differs.
   *
   * <p>This is the check {@link #open} deliberately skips. It is a separate
   * call so the cost of integrity checking is visible as a line item instead of
   * being silently folded into every open -- which is exactly the bill Lucene
   * pays and this engine does not.
   */
  public void verify() throws IOException {
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    byte[] buf = new byte[64 * 1024];
    long pos = 0;
    while (pos < footerOff) {
      int n = (int) Math.min(buf.length, footerOff - pos);
      MemorySegment.copy(seg, Format.I8, pos, buf, 0, n);
      crc.update(buf, 0, n);
      pos += n;
    }
    long expected = seg.get(Format.I64, footerOff);
    long actual = crc.getValue();
    if (expected != actual) {
      throw new IndexFormatException(
          "CRC32 mismatch: footer says " + expected + ", file computes " + actual);
    }
  }

  @Override
  public void close() {
    arena.close();
  }
}
