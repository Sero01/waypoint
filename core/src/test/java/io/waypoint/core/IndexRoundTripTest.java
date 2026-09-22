package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Build an index, read it back, and check that nothing was lost or misplaced. */
class IndexRoundTripTest {

  @Test
  void findsEveryTermItIndexed(@TempDir Path tmp) throws IOException {
    // Enough distinct terms to span many front-coded blocks, with long shared
    // prefixes so prefix compression is actually exercised rather than
    // degenerating into "store the whole term every time".
    int termCount = 5_000;
    Map<String, Integer> expectedDocFreq = new HashMap<>();
    Path path = tmp.resolve("many.wpt");

    try (IndexBuilder b = IndexBuilder.create(path)) {
      for (int d = 0; d < 500; d++) {
        List<String> tokens = new ArrayList<>();
        for (int t = d; t < termCount; t += 500) {
          String term = "prefixaaaaaaaaaa" + t;
          tokens.add(term);
          expectedDocFreq.merge(term, 1, Integer::sum);
        }
        b.add("doc" + d, tokens);
      }
    }

    try (Index index = Index.open(path)) {
      assertEquals(500, index.docCount());
      assertEquals(termCount, index.termCount());
      for (Map.Entry<String, Integer> e : expectedDocFreq.entrySet()) {
        assertEquals(e.getValue().intValue(), index.docFrequency(e.getKey()), e.getKey());
      }
      assertEquals(0, index.docFrequency("prefixaaaaaaaaaa" + termCount), "absent term");
      assertEquals(0, index.docFrequency("aaaa"), "term sorting before everything");
      assertEquals(0, index.docFrequency("zzzz"), "term sorting after everything");
    }
  }

  /**
   * Block-boundary terms are the ones a front-coded dictionary gets wrong. The
   * first term of a block is stored whole, every other term is a delta against
   * its predecessor, and the binary search picks a block by comparing against
   * those whole terms. An off-by-one there loses exactly the terms sitting at
   * indices 63, 64 and 65 of each block, and nothing else.
   */
  @Test
  void findsTermsExactlyOnBlockBoundaries(@TempDir Path tmp) throws IOException {
    int n = Format.BLOCK_SIZE * 5 + 7;
    Path path = tmp.resolve("boundaries.wpt");
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      tokens.add(numbered(i));
    }
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.add("only", tokens);
    }
    try (Index index = Index.open(path)) {
      for (int i = 0; i < n; i++) {
        assertEquals(1, index.docFrequency(numbered(i)), "term " + i);
      }
      assertEquals(0, index.docFrequency(numbered(n)));
    }
  }

  private static String numbered(int i) {
    String s = Integer.toString(i);
    return "term" + "000000".substring(s.length()) + s;
  }

  @Test
  void handlesNonAsciiTerms(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("unicode.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.addText("a", "café naïve Ünicode ЖУРНАЛ "
          + "日本語 emoji");
      b.addText("b", "café ЖУРНАЛ");
    }
    try (Index index = Index.open(path)) {
      assertEquals(2, index.docFrequency("café"));
      assertEquals(2, index.docFrequency("журнал"),
          "cyrillic is lowercased");
      assertEquals(1, index.docFrequency("日本語"));
      Hits hits = index.search(Query.term("café"), 10);
      assertEquals(2, hits.size());
    }
  }

  @Test
  void preservesDocumentKeysAndStatistics(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("keys.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.addText("first key", "alpha alpha alpha beta");
      b.addText("second/key with spaces", "beta gamma");
      b.addText("ключ", "gamma");
    }
    try (Index index = Index.open(path)) {
      assertEquals("first key", index.docKey(0));
      assertEquals("second/key with spaces", index.docKey(1));
      assertEquals("ключ", index.docKey(2));
      assertEquals(7, index.totalTokens());
      assertEquals(7 / 3f, index.averageDocumentLength());

      Hits hits = index.search(Query.term("alpha"), 10);
      assertEquals(1, hits.size());
      assertEquals("first key", hits.key(0));
      assertTrue(hits.totalHitsExact());
      assertEquals(1, hits.totalHits());
    }
  }

  /**
   * Conjunction reorders its clauses to put the rarest term first, so the
   * result must not depend on the order the caller wrote them in -- including
   * the score, which is a sum of floats and therefore order-sensitive if the
   * reordering were ever allowed to leak out.
   */
  @Test
  void andIsIndependentOfClauseOrder(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("and.wpt");
    Random rnd = new Random(11);
    try (IndexBuilder b = IndexBuilder.create(path)) {
      for (int d = 0; d < 400; d++) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
          tokens.add("w" + rnd.nextInt(20));
        }
        b.add("d" + d, tokens);
      }
    }
    try (Index index = Index.open(path)) {
      Hits a = index.search(Query.and(Query.term("w1"), Query.term("w2"), Query.term("w3")), 10);
      Hits b = index.search(Query.and(Query.term("w3"), Query.term("w1"), Query.term("w2")), 10);
      assertEquals(a.size(), b.size());
      assertEquals(a.totalHits(), b.totalHits());
      assertTrue(a.size() > 0, "the fixture should produce matches");
      for (int i = 0; i < a.size(); i++) {
        assertEquals(a.key(i), b.key(i), "rank " + i);
        assertEquals(a.score(i), b.score(i), "rank " + i);
      }
    }
  }

  /**
   * Waypoint counts every match, including past the point where Lucene stops
   * and starts returning a lower bound. This pins the deliberate divergence:
   * we do not cap the count to imitate Lucene's output shape, because capping
   * would save no work -- with no block-max pruning we visit every posting
   * regardless -- and would only obscure a real asymmetry that favours Lucene.
   */
  @Test
  void countsEveryMatchEvenPastLucenesCountingThreshold(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("threshold.wpt");
    int docs = Format.LUCENE_TOTAL_HITS_THRESHOLD + 500;
    try (IndexBuilder b = IndexBuilder.create(path)) {
      for (int d = 0; d < docs; d++) {
        b.add("d" + d, List.of("common", "d" + d));
      }
    }
    try (Index index = Index.open(path)) {
      Hits few = index.search(Query.term("d0"), 10);
      assertTrue(few.totalHitsExact());
      assertEquals(1, few.totalHits());

      Hits many = index.search(Query.term("common"), 10);
      assertTrue(many.totalHitsExact(), "the count is exact however large it gets");
      assertEquals(docs, many.totalHits());
    }
  }

  @Test
  void rejectsInvalidArguments(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("args.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.addText("a", "alpha");
      assertThrows(IllegalArgumentException.class, () -> b.addText("empty", "   !!!  "));
      assertThrows(IllegalArgumentException.class, () -> b.addText("", "alpha"));
    }
    try (Index index = Index.open(path)) {
      assertThrows(IllegalArgumentException.class, () -> index.search(Query.term("alpha"), 0));
      assertThrows(IllegalArgumentException.class, () -> index.search(Query.term("alpha"), -1));
      assertThrows(IllegalArgumentException.class, () -> Query.term(""));
      assertThrows(IllegalArgumentException.class, () -> Query.and());
      assertThrows(IndexOutOfBoundsException.class, () -> index.docKey(99));
    }
  }

  @Test
  void aMissingTermEmptiesAConjunctionButNotADisjunction(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("missing.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.addText("a", "alpha beta");
      b.addText("b", "beta gamma");
    }
    try (Index index = Index.open(path)) {
      assertEquals(0, index.search(Query.and(Query.term("alpha"), Query.term("nope")), 10).size());
      Hits or = index.search(Query.or(Query.term("alpha"), Query.term("nope")), 10);
      assertEquals(1, or.size());
      assertEquals("a", or.key(0));
      assertNotEquals(0f, or.score(0));
    }
  }

  @Test
  void verifyAcceptsAFileItJustWrote(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("verify.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      for (int i = 0; i < 200; i++) {
        b.addText("d" + i, "token" + i + " shared words here");
      }
    }
    try (Index index = Index.open(path)) {
      index.verify();
    }
  }

  /**
   * Frequencies above 1 take the second varint branch in the postings codec:
   * the common case folds {@code freq == 1} into the document gap's low bit,
   * and anything else writes a following varint. A frequency of 5,000 needs two
   * bytes there, so a truncated or mis-shifted decode shows up as a wrong score
   * rather than as a crash -- which is why this asserts the score, not just the
   * ranking.
   */
  @Test
  void decodesHighTermFrequencies(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("freqs.wpt");
    int repeats = 5_000;
    try (IndexBuilder b = IndexBuilder.create(path)) {
      List<String> many = new ArrayList<>();
      for (int i = 0; i < repeats; i++) {
        many.add("repeated");
      }
      many.add("unique");
      b.add("heavy", many);
      b.add("light", List.of("repeated", "unique"));
    }
    try (Index index = Index.open(path)) {
      assertEquals(2, index.docFrequency("repeated"));
      Hits hits = index.search(Query.term("repeated"), 10);
      assertEquals(2, hits.size());

      // Both documents contain the term, so idf is the same for each and the
      // ranking is decided entirely by freq against length. Recomputing the
      // heavy document's score from the definition pins the decoded frequency.
      float avgdl = index.averageDocumentLength();
      float idf = Bm25.idf(2, 2);
      double heavyLength = SmallFloat.byte4ToInt(SmallFloat.intToByte4(repeats + 1));
      double heavyNorm = Bm25.K1 * ((1 - Bm25.B) + Bm25.B * heavyLength / avgdl);
      double heavyExpected = idf * (repeats / (repeats + heavyNorm));

      assertEquals("heavy", hits.key(0), "5,000 occurrences beat 1 at this document length");
      assertEquals(heavyExpected, hits.score(0), 1e-5);
    }
  }

  /** Adjacency and order are what separate a phrase from a conjunction. */
  @Test
  void phraseMatchesOnlyAdjacentTermsInOrder(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("phrase.wpt");
    try (IndexBuilder b = IndexBuilder.create(path, true)) {
      b.addText("a", "the quick brown fox");
      b.addText("b", "brown the quick fox");
      b.addText("c", "quick brown quick brown");
    }
    try (Index index = Index.open(path)) {
      assertTrue(index.hasPositions());

      Hits adjacent = index.search(Query.phrase("quick", "brown"), 10);
      assertEquals(2, adjacent.totalHits(), "a and c contain 'quick brown'");

      Hits reversed = index.search(Query.phrase("brown", "quick"), 10);
      assertEquals(1, reversed.totalHits(), "only c has 'brown quick'");

      // The same two terms as a conjunction match every document.
      assertEquals(3, index.count(Query.and(Query.term("quick"), Query.term("brown"))));

      // c contains the phrase twice, a once, so c must outrank a.
      assertEquals("c", adjacent.key(0), "two occurrences beat one");

      Hits three = index.search(Query.phrase("the", "quick", "brown"), 10);
      assertEquals(1, three.totalHits(), "only a has all three adjacent");
      assertEquals("a", three.key(0));

      assertEquals(0, index.search(Query.phrase("quick", "absent"), 10).totalHits());
    }
  }

  /** A phrase query against an index built without positions must say so plainly. */
  @Test
  void phraseWithoutPositionsIsRejected(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("nopos.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.addText("a", "the quick brown fox");
    }
    try (Index index = Index.open(path)) {
      assertFalse(index.hasPositions());
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () -> index.search(Query.phrase("quick", "brown"), 10));
      assertTrue(e.getMessage().contains("no positions"), e.getMessage());
    }
  }

  /**
   * Positions have to survive the block machinery. With more than
   * {@link Format#POSTINGS_BLOCK} documents per term the postings are chunked,
   * and the position stream is resynchronised from each block header rather
   * than walked continuously, which is the part that can silently drift.
   */
  @Test
  void phrasesSurviveBlockedPostings(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("blocked.wpt");
    int docs = 5_000;
    try (IndexBuilder b = IndexBuilder.create(path, true)) {
      for (int i = 0; i < docs; i++) {
        // Every document contains both terms, so both lists are far longer
        // than a block, but only every seventh has them adjacent.
        b.addText("d" + i, i % 7 == 0 ? "alpha beta filler" : "alpha filler beta");
      }
    }
    try (Index index = Index.open(path)) {
      int expected = (docs + 6) / 7;
      Hits hits = index.search(Query.phrase("alpha", "beta"), 5);
      assertEquals(expected, hits.totalHits(), "adjacency across many blocks");
      assertEquals(docs, index.count(Query.and(Query.term("alpha"), Query.term("beta"))));
    }
  }
}
