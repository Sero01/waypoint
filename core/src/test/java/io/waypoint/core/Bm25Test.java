package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BM25 against values computed independently in this test.
 *
 * <p>The differential test in {@code bench} proves agreement with Lucene, which
 * is the stronger claim -- but it proves it by comparing two implementations,
 * so a shared misunderstanding of BM25 would pass. These tests compute the
 * expected score from the definition, in double precision, and check that the
 * engine's float pipeline lands on it.
 */
class Bm25Test {

  @Test
  void idfMatchesTheDefinition() {
    // log(1 + (N - n + 0.5) / (n + 0.5))
    assertEquals(
        (float) Math.log(1 + (1000 - 10 + 0.5) / (10 + 0.5)), Bm25.idf(10, 1000), 0f);
    assertEquals(
        (float) Math.log(1 + (5 - 5 + 0.5) / (5 + 0.5)), Bm25.idf(5, 5), 0f);
    assertTrue(Bm25.idf(1, 1000) > Bm25.idf(500, 1000), "rarer terms score higher");
  }

  /**
   * Lucene evaluates {@code weight - weight/(1 + freq*normInverse)} instead of
   * the textbook {@code weight * freq/(freq + norm)}. The two are equal in real
   * arithmetic; in float they are not always, and Lucene chose its form to stay
   * monotonic without promoting to double. We replicate the form, so this test
   * checks the value against the textbook expression computed in double -- close
   * enough to prove the algebra, loose enough to allow the float rounding that
   * is the whole reason for the rewrite.
   */
  @Test
  void scoreMatchesTheTextbookFormulaComputedInDouble() {
    float avgdl = 12.5f;
    float[] cache = Bm25.normCache(avgdl);

    for (int encodedLength = 0; encodedLength < 256; encodedLength++) {
      double dl = SmallFloat.byte4ToInt((byte) encodedLength);
      double norm = Bm25.K1 * ((1 - Bm25.B) + Bm25.B * dl / avgdl);
      for (int freq : new int[] {1, 2, 3, 10, 100}) {
        double weight = 2.75;
        double expected = weight * (freq / (freq + norm));
        float actual = Bm25.score((float) weight, freq, cache[encodedLength]);
        assertEquals(
            expected, actual, 1e-5,
            "encodedLength=" + encodedLength + " freq=" + freq);
      }
    }
  }

  @Test
  void scoreIsMonotonicInFrequencyAndInDocumentLength() {
    float[] cache = Bm25.normCache(20f);
    float weight = 1.5f;

    float previous = -1f;
    for (int freq = 1; freq < 500; freq++) {
      float s = Bm25.score(weight, freq, cache[40]);
      assertTrue(s >= previous, "score decreased as frequency rose at freq=" + freq);
      previous = s;
    }
    assertTrue(previous < weight, "score is bounded above by the term weight");

    previous = Float.MAX_VALUE;
    for (int encoded = 0; encoded < 256; encoded++) {
      float s = Bm25.score(weight, 3, cache[encoded]);
      assertTrue(s <= previous + 1e-7f, "score rose with document length at " + encoded);
      previous = s;
    }
  }

  @Test
  void avgdlIsComputedInDoubleLikeLucene() {
    // 7 tokens over 3 documents; computing this in float would round differently.
    assertEquals((float) (7 / 3.0), Bm25.avgFieldLength(7, 3), 0f);
    assertEquals((float) (1_000_000_007L / 3_333.0), Bm25.avgFieldLength(1_000_000_007L, 3_333), 0f);
  }

  /** End to end: a hand-checkable two-document index. */
  @Test
  void endToEndScoreOnASmallIndex(@TempDir Path tmp) throws IOException {
    Path path = tmp.resolve("bm25.wpt");
    try (IndexBuilder b = IndexBuilder.create(path)) {
      b.add("short", List.of("alpha", "beta"));
      b.add("long", List.of("alpha", "gamma", "delta", "epsilon", "zeta", "eta"));
    }

    try (Index index = Index.open(path)) {
      Hits hits = index.search(Query.term("alpha"), 10);
      assertEquals(2, hits.size());
      assertEquals("short", hits.key(0), "the shorter document wins on length normalisation");

      float avgdl = 8 / 2f;
      float idf = Bm25.idf(2, 2);
      for (int i = 0; i < hits.size(); i++) {
        int length = hits.key(i).equals("short") ? 2 : 6;
        double norm = Bm25.K1 * ((1 - Bm25.B) + Bm25.B * length / (double) avgdl);
        double expected = idf * (1 / (1 + norm));
        assertEquals(expected, hits.score(i), 1e-5, hits.key(i));
      }
    }
  }
}
