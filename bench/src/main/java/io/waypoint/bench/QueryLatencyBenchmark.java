package io.waypoint.bench;

import io.waypoint.core.Index;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Warm query latency. This is the benchmark Waypoint is expected to lose, and
 * it is here so that the loss is measured and published rather than omitted.
 *
 * <p>Lucene's advantage is structural, not incidental. Its
 * {@code MaxScoreBulkScorer} implements windowed block-max MaxScore with
 * essential/non-essential clause partitioning, so it can skip whole blocks of
 * postings that cannot enter the top k. Waypoint has no skip data, no impacts
 * and no pruning: every query visits every match. On top of that Lucene decodes
 * postings through the Panama Vector API when {@code --add-modules
 * jdk.incubator.vector} is present, which the teardown measured as worth up to
 * 1.80x on single-term latency.
 *
 * <p>Both of those are given to Lucene here. The fork arguments below enable
 * the incubator module, so these numbers are Lucene at its best and Waypoint at
 * its honest worst.
 *
 * <p>{@link Mode#SampleTime} rather than throughput, reported as p50/p95/p99,
 * because a search engine's tail latency is what anyone actually cares about.
 *
 * <p>Run with {@code -Dwaypoint.index=... -Dlucene.index=...}; see
 * {@link BenchMain}.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx2g"})
@State(Scope.Benchmark)
public class QueryLatencyBenchmark {

  // Query shapes, with the document frequencies they carry on the 1M-passage
  // MS MARCO index. The frequencies are the point: the size of the postings
  // lists a query has to walk is what decides whether Lucene's block-max
  // pruning has anything to prune, and a benchmark that only used rare terms
  // would miss the case Waypoint is worst at by an order of magnitude.
  //
  // An earlier version of this class used AND(the, manhattan, photosynthesis),
  // which matches **no documents at all** -- both engines discovered an empty
  // intersection immediately and the benchmark measured nothing. Conjunction
  // terms are now ones that genuinely co-occur.

  /** df 555: a rare term, a short postings list. */
  private static final String RARE_TERM = "manhattan";

  /** df 866,624: matches most of the corpus. This is where pruning earns its keep. */
  private static final String COMMON_TERM = "the";

  /** OR over df 555 / 6,890 / 1,174, matching 8,590 documents. */
  private static final String[] DISJUNCTION = {"manhattan", "project", "physics"};

  /** AND over df 31,045 / 12,346 / 5,276, matching 276 documents. */
  private static final String[] CONJUNCTION = {"blood", "pressure", "medication"};

  /**
   * AND over df 866,624 / 555, matching 549 documents.
   *
   * <p>The conjunction above has no clause long enough for skip data to skip:
   * its rarest term drives the leapfrog over 5,276 documents and the other two
   * lists are walked almost entirely regardless. This shape is the one where
   * skipping is supposed to pay, because {@code the} has to be jumped over 866k
   * postings at a time rather than decoded. Measuring only the first shape would
   * report that skip data does nothing, which is true of that query and false in
   * general.
   */
  private static final String[] COMMON_CONJUNCTION = {"the", "manhattan"};

  private static final int K = 10;

  private Index waypoint;
  private DirectoryReader reader;
  private FSDirectory directory;
  private IndexSearcher searcher;

  private io.waypoint.core.Query wpRare;
  private io.waypoint.core.Query wpCommon;
  private io.waypoint.core.Query wpOr;
  private io.waypoint.core.Query wpAnd;
  private io.waypoint.core.Query wpCommonAnd;

  private org.apache.lucene.search.Query lucRare;
  private org.apache.lucene.search.Query lucCommon;
  private org.apache.lucene.search.Query lucOr;
  private org.apache.lucene.search.Query lucAnd;
  private org.apache.lucene.search.Query lucCommonAnd;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    String wpPath = System.getProperty("waypoint.index");
    String lucPath = System.getProperty("lucene.index");
    if (wpPath == null || lucPath == null) {
      throw new IllegalStateException(
          "set -Dwaypoint.index=<file> -Dlucene.index=<dir>");
    }
    waypoint = Index.open(Path.of(wpPath));
    directory = FSDirectory.open(Path.of(lucPath));
    reader = DirectoryReader.open(directory);
    searcher = new IndexSearcher(reader);
    searcher.setSimilarity(new BM25Similarity());

    wpRare = io.waypoint.core.Query.term(RARE_TERM);
    wpCommon = io.waypoint.core.Query.term(COMMON_TERM);
    wpOr = io.waypoint.core.Query.or(wpTerms(DISJUNCTION));
    wpAnd = io.waypoint.core.Query.and(wpTerms(CONJUNCTION));
    wpCommonAnd = io.waypoint.core.Query.and(wpTerms(COMMON_CONJUNCTION));

    lucRare = term(RARE_TERM);
    lucCommon = term(COMMON_TERM);
    lucOr = bool(DISJUNCTION, BooleanClause.Occur.SHOULD);
    lucAnd = bool(CONJUNCTION, BooleanClause.Occur.MUST);
    lucCommonAnd = bool(COMMON_CONJUNCTION, BooleanClause.Occur.MUST);

    requireMatches("rare term", wpRare, lucRare);
    requireMatches("common term", wpCommon, lucCommon);
    requireMatches("disjunction", wpOr, lucOr);
    requireMatches("conjunction", wpAnd, lucAnd);
    requireMatches("common conjunction", wpCommonAnd, lucCommonAnd);
  }

  /**
   * Refuses to benchmark a query that matches nothing on this index.
   *
   * <p>An earlier version of this class conjoined three terms that never
   * co-occur. Both engines found the empty intersection immediately, both
   * reported single-digit microseconds, and the resulting table looked like a
   * meaningful conjunction comparison. Nothing about the run said otherwise --
   * a benchmark measuring nothing produces numbers as confidently as one
   * measuring something.
   *
   * <p>It also checks that both engines agree on the result count where Lucene
   * is willing to give an exact one, so a benchmark cannot compare two
   * different amounts of work.
   */
  private void requireMatches(
      String label, io.waypoint.core.Query wp, org.apache.lucene.search.Query luc)
      throws IOException {
    // count() rather than search().totalHits(): MaxScore makes a pruned
    // disjunction's own tally a lower bound, and this check needs the true
    // number to be able to compare the two engines' amounts of work.
    long ours = waypoint.count(wp);
    if (ours == 0) {
      throw new IllegalStateException(
          "the " + label + " query matches no documents on this index; it would benchmark"
              + " nothing. Pick terms that occur together in the corpus.");
    }
    org.apache.lucene.search.TopDocs theirs = searcher.search(luc, K);
    boolean exact =
        theirs.totalHits.relation() == org.apache.lucene.search.TotalHits.Relation.EQUAL_TO;
    if (exact && theirs.totalHits.value() != ours) {
      throw new IllegalStateException(
          "the " + label + " query matches " + ours + " documents in waypoint but "
              + theirs.totalHits.value() + " in lucene; the two indexes disagree");
    }
    System.out.println("  " + label + ": " + ours + " matches");
  }

  private static io.waypoint.core.Query[] wpTerms(String[] terms) {
    io.waypoint.core.Query[] clauses = new io.waypoint.core.Query[terms.length];
    for (int i = 0; i < terms.length; i++) {
      clauses[i] = io.waypoint.core.Query.term(terms[i]);
    }
    return clauses;
  }

  private static TermQuery term(String t) {
    return new TermQuery(new Term(LuceneBuild.FIELD_BODY, t));
  }

  private static org.apache.lucene.search.Query bool(String[] terms, BooleanClause.Occur occur) {
    BooleanQuery.Builder b = new BooleanQuery.Builder();
    for (String t : terms) {
      b.add(term(t), occur);
    }
    return b.build();
  }

  @TearDown(Level.Trial)
  public void tearDown() throws IOException {
    waypoint.close();
    reader.close();
    directory.close();
  }

  @Benchmark
  public void waypointRareTerm(Blackhole bh) {
    bh.consume(waypoint.search(wpRare, K));
  }

  @Benchmark
  public void luceneRareTerm(Blackhole bh) throws IOException {
    TopDocs top = searcher.search(lucRare, K);
    bh.consume(top);
  }

  @Benchmark
  public void waypointCommonTerm(Blackhole bh) {
    bh.consume(waypoint.search(wpCommon, K));
  }

  @Benchmark
  public void luceneCommonTerm(Blackhole bh) throws IOException {
    bh.consume(searcher.search(lucCommon, K));
  }

  @Benchmark
  public void waypointDisjunction(Blackhole bh) {
    bh.consume(waypoint.search(wpOr, K));
  }

  @Benchmark
  public void luceneDisjunction(Blackhole bh) throws IOException {
    bh.consume(searcher.search(lucOr, K));
  }

  @Benchmark
  public void waypointConjunction(Blackhole bh) {
    bh.consume(waypoint.search(wpAnd, K));
  }

  @Benchmark
  public void luceneConjunction(Blackhole bh) throws IOException {
    bh.consume(searcher.search(lucAnd, K));
  }

  @Benchmark
  public void waypointCommonConjunction(Blackhole bh) {
    bh.consume(waypoint.search(wpCommonAnd, K));
  }

  @Benchmark
  public void luceneCommonConjunction(Blackhole bh) throws IOException {
    bh.consume(searcher.search(lucCommonAnd, K));
  }

  /**
   * Reopening an index inside an already-warm JVM.
   *
   * <p>The one latency number Waypoint expects to win, and the mechanism behind
   * the cold-start result stripped of JVM startup: what it costs to go from a
   * file on disk to something you can query. Lucene's side here is
   * {@code DirectoryReader.open}, which reads segment metadata, resolves codecs
   * and constructs the per-format reader stack. Waypoint's is one {@code map()}
   * and a 128-byte header read.
   */
  @Benchmark
  public void waypointReopen(Blackhole bh) throws IOException {
    try (Index index = Index.open(Path.of(System.getProperty("waypoint.index")))) {
      bh.consume(index.docCount());
    }
  }

  @Benchmark
  public void luceneReopen(Blackhole bh) throws IOException {
    try (FSDirectory d = FSDirectory.open(Path.of(System.getProperty("lucene.index")));
        DirectoryReader r = DirectoryReader.open(d)) {
      bh.consume(r.numDocs());
    }
  }
}
