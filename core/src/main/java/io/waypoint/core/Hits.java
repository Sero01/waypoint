package io.waypoint.core;

/**
 * A page of search results, shaped like Lucene's {@code TopDocs} so the two can
 * be compared without translation.
 *
 * <p>{@link #totalHits()} carries the same shape as Lucene's
 * {@code TotalHits} -- a value plus a flag saying whether it is exact -- but
 * for Waypoint the flag is always true. Lucene stops counting at
 * {@code TOTAL_HITS_THRESHOLD = 1000} and returns a lower bound, because
 * block-max pruning lets it stop visiting postings once no unvisited document
 * can enter the top k.
 *
 * <p>Waypoint has no pruning, so it visits every match anyway and the count
 * costs nothing extra. Capping it to imitate Lucene's output would hide that
 * asymmetry rather than remove it. The asymmetry is real, it favours Lucene,
 * and it is reported as a query-latency loss in the README instead.
 */
public final class Hits {

  private final long totalHits;
  private final boolean totalHitsExact;
  private final int[] docs;
  private final float[] scores;
  private final String[] keys;

  Hits(long totalHits, boolean totalHitsExact, int[] docs, float[] scores, String[] keys) {
    this.totalHits = totalHits;
    this.totalHitsExact = totalHitsExact;
    this.docs = docs;
    this.scores = scores;
    this.keys = keys;
  }

  /** The number of matching documents. Always exact; see the class javadoc. */
  public long totalHits() {
    return totalHits;
  }

  /**
   * Whether {@link #totalHits()} is exact rather than a lower bound. Always
   * true for Waypoint; present so results can be compared with Lucene's
   * {@code TotalHits} without translation.
   */
  public boolean totalHitsExact() {
    return totalHitsExact;
  }

  /** Number of results actually returned (at most the requested k). */
  public int size() {
    return docs.length;
  }

  /** Internal document id, dense and stable for the life of the index file. */
  public int doc(int i) {
    return docs[i];
  }

  public float score(int i) {
    return scores[i];
  }

  /** The caller-supplied external identifier for this document. */
  public String key(int i) {
    return keys[i];
  }
}
