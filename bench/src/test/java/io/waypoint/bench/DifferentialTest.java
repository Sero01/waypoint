package io.waypoint.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waypoint.core.Hits;
import io.waypoint.core.Index;
import io.waypoint.core.IndexBuilder;
import io.waypoint.core.Query;
import io.waypoint.core.Tokenizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lucene as an oracle.
 *
 * <p>This is the strongest correctness tool available to a project like this
 * one: the thing being built has a mature, widely-trusted reference
 * implementation, so instead of asserting hand-computed expectations we can
 * assert <em>agreement</em> over randomly generated corpora and queries.
 *
 * <p>Both engines are driven from the same token stream (see
 * {@link WaypointAnalyzer}), so a divergence can only be the index format, the
 * term dictionary, the postings codec, the norm quantisation, the BM25
 * arithmetic or the tie-break -- never the analyzer.
 *
 * <p>What is asserted, per query: identical result count, identical document
 * keys in identical order, and scores agreeing to 1e-6 relative. Total hit
 * counts are only compared when Lucene reports an exact count, because Lucene
 * stops counting at {@code TOTAL_HITS_THRESHOLD = 1000} and returns a lower
 * bound; Waypoint has no block-max pruning and so always knows the true count.
 * That asymmetry is a Waypoint <em>disadvantage</em> in work done, and is
 * reported as such rather than being quietly normalised away.
 */
class DifferentialTest {

  /**
   * Scores are asserted to 1e-6 relative rather than the 1e-4 originally
   * planned, because the engines turn out to agree far more closely than that:
   * see {@link #reportObservedAgreement()}.
   *
   * <p>Bit equality is not achievable and asking for it would be a bug in the
   * test, not a bug in the engine. Float addition is not associative, and for a
   * multi-clause query Lucene sums clause contributions in its scorer's own
   * order while Waypoint sums them in rarest-first order. Same inputs, same
   * arithmetic, different association -- and therefore occasionally a different
   * final ulp.
   */
  private static final float SCORE_TOLERANCE = 1e-6f;

  private static double maxRelativeScoreError;
  private static long comparedScores;
  private static long identicalScores;

  @AfterAll
  static void reportObservedAgreement() {
    System.out.println(
        "differential: compared "
            + comparedScores
            + " scores against Lucene, "
            + identicalScores
            + " bit-identical ("
            + Math.round(1000.0 * identicalScores / Math.max(1, comparedScores)) / 10.0
            + "%), max relative difference "
            + maxRelativeScoreError);
  }

  @Test
  void agreesWithLuceneOnRandomCorpora(@TempDir Path tmp) throws Exception {
    for (int seed = 1; seed <= 5; seed++) {
      Path root = tmp.resolve("seed" + seed);
      List<LuceneBuild.Doc> docs = Corpus.zipfian(seed, 2_000, 400, 4, 60);
      runOneCorpus(root, docs, seed, 300);
    }
  }

  /** A corpus small enough that many queries match fewer than k documents. */
  @Test
  void agreesOnTinyCorpus(@TempDir Path tmp) throws Exception {
    List<LuceneBuild.Doc> docs = Corpus.zipfian(99, 25, 12, 1, 8);
    runOneCorpus(tmp, docs, 99, 200);
  }

  /** A corpus large enough to cross Lucene's 1000-hit counting threshold. */
  @Test
  void agreesAcrossTheTotalHitsThreshold(@TempDir Path tmp) throws Exception {
    List<LuceneBuild.Doc> docs = Corpus.zipfian(7, 20_000, 300, 10, 40);
    runOneCorpus(tmp, docs, 7, 120);
  }

  /**
   * Documents whose lengths sweep the whole range of the one-byte norm
   * encoding. If {@code SmallFloat.intToByte4} were even slightly off, long
   * documents would land in the wrong length bucket and their scores would
   * diverge -- and short ones, where every length is stored exactly, would not.
   * This test would catch that; a corpus of uniformly medium documents would
   * not.
   */
  @Test
  void agreesAcrossTheWholeNormQuantisationRange(@TempDir Path tmp) throws Exception {
    Random rnd = new Random(4242);
    List<LuceneBuild.Doc> docs = new ArrayList<>();
    int[] lengths = {1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 33, 63, 65, 127, 130, 255, 260, 511, 520,
      1023, 1100, 2047, 2500, 4095, 5000, 9999};
    for (int len : lengths) {
      for (int rep = 0; rep < 4; rep++) {
        StringBuilder sb = new StringBuilder(len * 4);
        for (int i = 0; i < len; i++) {
          if (i > 0) {
            sb.append(' ');
          }
          sb.append("w").append(rnd.nextInt(40));
        }
        docs.add(new LuceneBuild.Doc("len" + len + "-" + rep, sb.toString()));
      }
    }
    runOneCorpus(tmp, docs, 4242, 150);
  }

  // ------------------------------------------------------------------------

  /**
   * Phrase queries, against Lucene's {@code PhraseQuery} on an index that also
   * stores positions.
   *
   * <p>Phrases are the one query shape whose scoring is not a sum of per-term
   * scores: Lucene weights the whole phrase with the <em>sum</em> of the clause
   * idfs and feeds BM25 the number of phrase occurrences as the frequency. That
   * is easy to get subtly wrong and impossible to notice without an oracle, so
   * the queries here are drawn from bigrams and trigrams that actually occur in
   * the corpus rather than from random term pairs that would almost never
   * match.
   */
  @Test
  void agreesWithLuceneOnPhrases(@TempDir Path tmp) throws Exception {
    for (int seed = 11; seed <= 13; seed++) {
      Path root = tmp.resolve("phrase" + seed);
      List<LuceneBuild.Doc> docs = Corpus.zipfian(seed, 3_000, 120, 6, 50);
      runPhraseCorpus(root, docs, seed, 250);
    }
  }

  private void runPhraseCorpus(Path root, List<LuceneBuild.Doc> docs, long seed, int queryCount)
      throws Exception {
    Path wpPath = root.resolve("waypoint.wpt");
    Path lucenePath = root.resolve("lucene");

    List<List<String>> tokenised = new ArrayList<>();
    try (IndexBuilder b = IndexBuilder.create(wpPath, true)) {
      for (LuceneBuild.Doc d : docs) {
        List<String> t = Tokenizer.tokenize(d.text());
        tokenised.add(t);
        b.add(d.id(), t);
      }
    }
    LuceneBuild.buildIndex(lucenePath, docs, false, 256, true);

    // Real n-grams from the corpus, so most queries have something to match.
    List<String[]> grams = new ArrayList<>();
    Random pick = new Random(seed);
    for (List<String> t : tokenised) {
      if (t.size() < 3) {
        continue;
      }
      int at = pick.nextInt(t.size() - 2);
      grams.add(new String[] {t.get(at), t.get(at + 1)});
      grams.add(new String[] {t.get(at), t.get(at + 1), t.get(at + 2)});
    }

    Random rnd = new Random(seed);
    try (Index wp = Index.open(wpPath);
        FSDirectory dir = FSDirectory.open(lucenePath);
        DirectoryReader reader = DirectoryReader.open(dir)) {

      assertTrue(wp.hasPositions(), "index should report positions");
      IndexSearcher searcher = new IndexSearcher(reader);
      searcher.setSimilarity(new BM25Similarity());

      for (int q = 0; q < queryCount; q++) {
        int k = 1 + rnd.nextInt(20);
        String[] terms = grams.get(rnd.nextInt(grams.size()));
        // Every so often, a phrase that should match nothing.
        if (rnd.nextInt(10) == 0) {
          terms = new String[] {terms[0], "zzzabsentzzz" + q};
        }
        comparePhrase(wp, searcher, reader, terms, k);
      }
    }
  }

  private void comparePhrase(
      Index wp, IndexSearcher searcher, DirectoryReader reader, String[] terms, int k)
      throws IOException {

    Hits ours = wp.search(Query.phrase(terms), k);

    PhraseQuery.Builder pb = new PhraseQuery.Builder();
    for (int i = 0; i < terms.length; i++) {
      pb.add(new Term(LuceneBuild.FIELD_BODY, terms[i]), i);
    }
    TopDocs theirs = searcher.search(pb.build(), k);

    String label = "PHRASE" + List.of(terms) + " k=" + k;
    assertEquals(theirs.scoreDocs.length, ours.size(), "result count for " + label);

    if (theirs.totalHits.relation() == TotalHits.Relation.EQUAL_TO) {
      assertEquals(theirs.totalHits.value(), ours.totalHits(), "total hits for " + label);
    }

    var stored = reader.storedFields();
    for (int i = 0; i < theirs.scoreDocs.length; i++) {
      ScoreDoc sd = theirs.scoreDocs[i];
      assertEquals(
          stored.document(sd.doc).get(LuceneBuild.FIELD_ID),
          ours.key(i),
          "rank " + i + " document for " + label);

      float a = sd.score;
      float b = ours.score(i);
      float denom = Math.max(Math.abs(a), 1e-6f);
      double relative = Math.abs(a - b) / denom;
      comparedScores++;
      if (Float.floatToIntBits(a) == Float.floatToIntBits(b)) {
        identicalScores++;
      }
      if (relative > maxRelativeScoreError) {
        maxRelativeScoreError = relative;
      }
      assertTrue(
          relative <= SCORE_TOLERANCE,
          "rank " + i + " score for " + label + ": lucene=" + a + " waypoint=" + b);
    }
  }

  private void runOneCorpus(Path root, List<LuceneBuild.Doc> docs, long seed, int queryCount)
      throws Exception {
    Path wpPath = root.resolve("waypoint.wpt");
    Path lucenePath = root.resolve("lucene");

    try (IndexBuilder b = IndexBuilder.create(wpPath)) {
      for (LuceneBuild.Doc d : docs) {
        b.add(d.id(), Tokenizer.tokenize(d.text()));
      }
    }
    LuceneBuild.buildIndex(lucenePath, docs, false, 256);

    List<String> vocab = Corpus.vocabulary(docs, 0);
    Random rnd = new Random(seed);

    try (Index wp = Index.open(wpPath);
        FSDirectory dir = FSDirectory.open(lucenePath);
        DirectoryReader reader = DirectoryReader.open(dir)) {

      assertEquals(reader.numDocs(), wp.docCount(), "document count");
      IndexSearcher searcher = new IndexSearcher(reader);
      searcher.setSimilarity(new BM25Similarity());

      // Term statistics have to agree before scores can.
      for (String t : vocab) {
        assertEquals(
            reader.docFreq(new Term(LuceneBuild.FIELD_BODY, t)),
            wp.docFrequency(t),
            "docFreq for '" + t + "'");
      }
      assertEquals(
          reader.getSumTotalTermFreq(LuceneBuild.FIELD_BODY),
          wp.totalTokens(),
          "sumTotalTermFreq");

      for (int q = 0; q < queryCount; q++) {
        int k = 1 + rnd.nextInt(20);
        int shape = rnd.nextInt(10);
        List<String> terms = new ArrayList<>();
        boolean and;
        if (shape < 4) {
          terms.add(pick(vocab, rnd));
          and = false;
        } else if (shape < 7) {
          int n = 2 + rnd.nextInt(3);
          for (int i = 0; i < n; i++) {
            terms.add(pick(vocab, rnd));
          }
          and = false;
        } else if (shape < 9) {
          int n = 2 + rnd.nextInt(2);
          for (int i = 0; i < n; i++) {
            terms.add(pick(vocab, rnd));
          }
          and = true;
        } else {
          // at least one term that is guaranteed absent
          terms.add(pick(vocab, rnd));
          terms.add("zzzabsentzzz" + q);
          and = rnd.nextBoolean();
        }
        compare(wp, searcher, reader, terms, and, k);
      }
    }

  }

  private static String pick(List<String> vocab, Random rnd) {
    return vocab.get(rnd.nextInt(vocab.size()));
  }

  private void compare(
      Index wp, IndexSearcher searcher, DirectoryReader reader,
      List<String> terms, boolean and, int k)
      throws IOException {

    Query wpQuery;
    if (terms.size() == 1) {
      wpQuery = Query.term(terms.get(0));
    } else {
      Query[] clauses = new Query[terms.size()];
      for (int i = 0; i < terms.size(); i++) {
        clauses[i] = Query.term(terms.get(i));
      }
      wpQuery = and ? Query.and(clauses) : Query.or(clauses);
    }
    Hits ours = wp.search(wpQuery, k);

    org.apache.lucene.search.Query lq;
    if (terms.size() == 1) {
      lq = new TermQuery(new Term(LuceneBuild.FIELD_BODY, terms.get(0)));
    } else {
      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      for (String t : terms) {
        bq.add(
            new TermQuery(new Term(LuceneBuild.FIELD_BODY, t)),
            and ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
      }
      lq = bq.build();
    }
    TopDocs theirs = searcher.search(lq, k);

    String label = (and ? "AND" : "OR") + terms + " k=" + k;

    assertEquals(theirs.scoreDocs.length, ours.size(), "result count for " + label);

    // Index.count is exact for every query shape, including the disjunctions
    // where MaxScore makes search()'s own tally a lower bound. Checking it
    // against Lucene whenever Lucene is exact keeps the counting guarantee
    // under test rather than dropping it along with the pruning.
    long exact = wp.count(wpQuery);
    if (theirs.totalHits.relation() == TotalHits.Relation.EQUAL_TO) {
      assertEquals(theirs.totalHits.value(), exact, "exact count for " + label);
    } else {
      assertTrue(
          exact >= theirs.totalHits.value(),
          "our exact count " + exact + " is below Lucene's lower bound "
              + theirs.totalHits.value() + " for " + label);
    }

    // What search() itself reported has to be consistent with that: exact when
    // it claims to be, and never an over-count when it does not.
    if (ours.totalHitsExact()) {
      assertEquals(exact, ours.totalHits(), "search() claimed an exact count for " + label);
    } else {
      assertTrue(
          ours.totalHits() <= exact,
          "search() over-counted for " + label + ": " + ours.totalHits() + " > " + exact);
    }

    var stored = reader.storedFields();
    for (int i = 0; i < theirs.scoreDocs.length; i++) {
      ScoreDoc sd = theirs.scoreDocs[i];
      String theirKey = stored.document(sd.doc).get(LuceneBuild.FIELD_ID);
      assertEquals(theirKey, ours.key(i), "rank " + i + " document for " + label);

      float a = sd.score;
      float b = ours.score(i);
      float denom = Math.max(Math.abs(a), 1e-6f);
      double relative = Math.abs(a - b) / denom;
      comparedScores++;
      if (Float.floatToIntBits(a) == Float.floatToIntBits(b)) {
        identicalScores++;
      }
      if (relative > maxRelativeScoreError) {
        maxRelativeScoreError = relative;
      }
      assertTrue(
          relative <= SCORE_TOLERANCE,
          "rank " + i + " score for " + label + ": lucene=" + a + " waypoint=" + b);
    }
  }
}
