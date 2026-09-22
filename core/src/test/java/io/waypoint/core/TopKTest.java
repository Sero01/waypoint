package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The bounded heap, held to Lucene's ordering.
 *
 * <p>The tie-break is the part worth testing hardest. Lucene ranks a lower
 * document id first when scores are equal, and near-ties are the normal case in
 * BM25 over a real corpus -- a reversed tie-break would pass every "does it
 * return the right documents" test and fail the differential test against
 * Lucene in a way that looks like a scoring bug.
 */
class TopKTest {

  @Test
  void returnsBestFirst() {
    TopK heap = new TopK(3);
    heap.offer(1, 0.5f);
    heap.offer(2, 0.9f);
    heap.offer(3, 0.1f);
    heap.offer(4, 0.7f);

    int[] docs = new int[heap.size()];
    float[] scores = new float[heap.size()];
    heap.drain(docs, scores);

    assertArrayEquals(new int[] {2, 4, 1}, docs);
    assertEquals(0.9f, scores[0]);
    assertEquals(0.7f, scores[1]);
    assertEquals(0.5f, scores[2]);
  }

  @Test
  void breaksTiesTowardsTheLowerDocumentId() {
    TopK heap = new TopK(2);
    // offered in increasing document order, as a real scan would
    heap.offer(5, 1.0f);
    heap.offer(6, 1.0f);
    heap.offer(7, 1.0f);

    int[] docs = new int[heap.size()];
    float[] scores = new float[heap.size()];
    heap.drain(docs, scores);
    assertArrayEquals(new int[] {5, 6}, docs, "earlier documents win an exact tie");
  }

  @Test
  void handlesFewerHitsThanK() {
    TopK heap = new TopK(10);
    heap.offer(1, 0.3f);
    heap.offer(2, 0.8f);
    assertEquals(2, heap.size());

    int[] docs = new int[2];
    float[] scores = new float[2];
    heap.drain(docs, scores);
    assertArrayEquals(new int[] {2, 1}, docs);
  }

  @Test
  void agreesWithAFullSortOnRandomInput() {
    Random rnd = new Random(31337);
    for (int trial = 0; trial < 200; trial++) {
      int n = 1 + rnd.nextInt(200);
      int k = 1 + rnd.nextInt(20);
      // Scores are drawn from a small set so exact ties are frequent.
      float[] scores = new float[n];
      for (int i = 0; i < n; i++) {
        scores[i] = rnd.nextInt(5) / 4f;
      }

      TopK heap = new TopK(k);
      for (int doc = 0; doc < n; doc++) {
        heap.offer(doc, scores[doc]);
      }
      int size = heap.size();
      int[] gotDocs = new int[size];
      float[] gotScores = new float[size];
      heap.drain(gotDocs, gotScores);

      List<Integer> expected = new ArrayList<>();
      for (int doc = 0; doc < n; doc++) {
        expected.add(doc);
      }
      expected.sort((a, b) -> {
        int c = Float.compare(scores[b], scores[a]);
        return c != 0 ? c : Integer.compare(a, b);
      });

      assertEquals(Math.min(k, n), size);
      for (int i = 0; i < size; i++) {
        assertEquals(
            expected.get(i).intValue(), gotDocs[i],
            "rank " + i + " of trial " + trial + " (n=" + n + ", k=" + k + ")");
      }
    }
  }
}
