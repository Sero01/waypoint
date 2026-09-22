package io.waypoint.core;

/**
 * A bounded min-heap of the best k hits, with Lucene's exact ordering.
 *
 * <p>Lucene's {@code HitQueue} treats hit A as worse than hit B when
 * {@code A.score < B.score}, or when the scores are equal and
 * {@code A.doc > B.doc} -- lower document id wins a tie. Both engines visit
 * documents in increasing id order, so an equal-scoring hit arriving later
 * always loses, and the admission test collapses to a plain
 * {@code score > worst}. Getting this wrong would show up as a reordered top-k
 * on near-ties, which is precisely what the differential test against Lucene
 * is built to catch.
 *
 * <p>Parallel primitive arrays rather than an object per hit: k is small, but
 * allocating k {@code ScoreDoc}s on the first query of a fresh JVM is exactly
 * the sort of cost this engine is measuring.
 */
final class TopK {

  private final int k;
  private final float[] scores;
  private final int[] docs;
  private int size;

  TopK(int k) {
    this.k = k;
    // 1-based heap: index 0 is unused so parent/child arithmetic stays branch-free.
    this.scores = new float[k + 1];
    this.docs = new int[k + 1];
  }

  /** True when the heap is full; scores/docs then only improve. */
  boolean full() {
    return size == k;
  }

  /** The worst score currently retained. Only meaningful once {@link #full()}. */
  float worstScore() {
    return scores[1];
  }

  void offer(int doc, float score) {
    if (size < k) {
      size++;
      scores[size] = score;
      docs[size] = doc;
      siftUp(size);
    } else if (score > scores[1]) {
      scores[1] = score;
      docs[1] = doc;
      siftDown();
    }
  }

  int size() {
    return size;
  }

  /**
   * Drains the heap into {@code outDocs}/{@code outScores}, best first.
   * Popping a min-heap yields worst-to-best, so we fill backwards.
   */
  void drain(int[] outDocs, float[] outScores) {
    int n = size;
    for (int i = n - 1; i >= 0; i--) {
      outDocs[i] = docs[1];
      outScores[i] = scores[1];
      scores[1] = scores[size];
      docs[1] = docs[size];
      size--;
      if (size > 1) {
        siftDown();
      }
    }
  }

  /** True when the hit at {@code i} is worse than the hit at {@code j}. */
  private boolean lessThan(int i, int j) {
    float si = scores[i];
    float sj = scores[j];
    if (si == sj) {
      return docs[i] > docs[j];
    }
    return si < sj;
  }

  private void siftUp(int i) {
    while (i > 1) {
      int parent = i >>> 1;
      // a min-heap keeps the worst hit at the root: stop once we are no worse
      // than our parent
      if (!lessThan(i, parent)) {
        break;
      }
      swap(i, parent);
      i = parent;
    }
  }

  private void swap(int i, int j) {
    float s = scores[i];
    int d = docs[i];
    scores[i] = scores[j];
    docs[i] = docs[j];
    scores[j] = s;
    docs[j] = d;
  }

  private void siftDown() {
    int i = 1;
    while (true) {
      int child = i << 1;
      if (child > size) {
        break;
      }
      int right = child + 1;
      if (right <= size && lessThan(right, child)) {
        child = right;
      }
      if (!lessThan(child, i)) {
        break;
      }
      swap(i, child);
      i = child;
    }
  }
}
