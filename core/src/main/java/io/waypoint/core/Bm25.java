package io.waypoint.core;

/**
 * BM25 with Lucene's defaults and, more importantly, Lucene's exact arithmetic.
 *
 * <p>Lucene does not evaluate textbook BM25. It precomputes a 256-entry table of
 * decoded document lengths, folds {@code k1 * ((1-b) + b*dl/avgdl)} into a
 * reciprocal cache, and then evaluates
 * {@code weight - weight / (1 + freq * normInverse)} rather than
 * {@code weight * freq / (freq + norm)} so the result stays monotonic in both
 * freq and norm without promoting to double.
 *
 * <p>We replicate the algebraic form, not just the formula. Float arithmetic is
 * not associative; a mathematically equal rearrangement produces scores that
 * differ in the last ulp, which reorders near-ties and would make the parity
 * claim false. {@code DifferentialTest} holds this to 1e-4 relative against
 * Lucene on random corpora.
 *
 * @see <a href="https://github.com/apache/lucene/blob/releases/lucene/10.5.1/lucene/core/src/java/org/apache/lucene/search/similarities/BM25Similarity.java">BM25Similarity.java</a>
 */
final class Bm25 {

  private Bm25() {}

  static final float K1 = 1.2f;
  static final float B = 0.75f;

  /** LENGTH_TABLE[i] = SmallFloat.byte4ToInt((byte) i). */
  private static final float[] LENGTH_TABLE = new float[256];

  static {
    for (int i = 0; i < 256; i++) {
      LENGTH_TABLE[i] = SmallFloat.byte4ToInt((byte) i);
    }
  }

  /** {@code log(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5))}. */
  static float idf(long docFreq, long docCount) {
    return (float) Math.log(1 + (docCount - docFreq + 0.5D) / (docFreq + 0.5D));
  }

  /** {@code avgdl = sumTotalTermFreq / docCount}, computed in double as Lucene does. */
  static float avgFieldLength(long totalTokens, int docCount) {
    return (float) (totalTokens / (double) docCount);
  }

  /**
   * The 256-entry reciprocal cache. Depends only on avgdl, so an index computes
   * it once on first search and shares it across every term of every query.
   */
  static float[] normCache(float avgdl) {
    float[] cache = new float[256];
    for (int i = 0; i < 256; i++) {
      cache[i] = 1f / (K1 * ((1 - B) + B * LENGTH_TABLE[i] / avgdl));
    }
    return cache;
  }

  /** {@code weight = idf * boost}; boost is always 1 in v1. */
  static float score(float weight, float freq, float normInverse) {
    return weight - weight / (1f + freq * normInverse);
  }

  /** Decoded document length, for explain output only. */
  static float decodeLength(byte norm) {
    return LENGTH_TABLE[norm & 0xFF];
  }
}
