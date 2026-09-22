package io.waypoint.bench;

import io.waypoint.core.Tokenizer;
import java.nio.file.Path;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.index.StoredFields;

/**
 * Lucene's side of the cold-start benchmark: the smallest honest program that
 * opens an index and answers one query.
 *
 * <p>This is deliberately written the way {@code io.waypoint.cli.Main} is --
 * no argument library, no logging, no {@code String.format}, results
 * accumulated into a {@code StringBuilder} and printed once, identical output
 * shape. If Lucene's number were measured through a heavier wrapper than
 * Waypoint's, the comparison would be measuring the wrappers.
 *
 * <p>It calls Waypoint's {@link Tokenizer#normalizeTerm} on each query word.
 * That charges Lucene for loading two of our classes, which is noise against a
 * 2,000-class baseline, and it buys certainty that both engines look up
 * byte-identical terms.
 *
 * <p>The JVM flag {@code --add-modules jdk.incubator.vector} is what switches
 * Lucene onto its Panama SIMD postings decoder; it is off in a stock
 * {@code java -jar}, and the teardown measured it worth up to 1.80x on
 * single-term latency. The harness runs Lucene both ways and reports both.
 */
public final class LuceneSearchMain {

  private LuceneSearchMain() {}

  public static void main(String[] args) throws Exception {
    long t0 = System.nanoTime();
    if (args.length < 2) {
      System.err.println("usage: LuceneSearchMain <indexDir> [-k N] [--and] [-t] <term> [term...]");
      System.exit(2);
      return;
    }
    Path dir = Path.of(args[0]);

    int k = 10;
    boolean and = false;
    boolean timing = false;
    int i = 1;
    while (i < args.length && args[i].startsWith("-")) {
      String a = args[i];
      if (a.equals("-k")) {
        k = Integer.parseInt(args[++i]);
      } else if (a.equals("--and")) {
        and = true;
      } else if (a.equals("-t")) {
        timing = true;
      } else {
        System.err.println("unknown option " + a);
        System.exit(2);
        return;
      }
      i++;
    }

    BooleanQuery.Builder bq = new BooleanQuery.Builder();
    Query single = null;
    int n = 0;
    for (int j = i; j < args.length; j++) {
      String t = Tokenizer.normalizeTerm(args[j]);
      if (t == null) {
        continue;
      }
      TermQuery tq = new TermQuery(new Term(LuceneBuild.FIELD_BODY, t));
      single = tq;
      bq.add(tq, and ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
      n++;
    }
    if (n == 0) {
      System.err.println("query contains no indexable terms");
      System.exit(2);
      return;
    }
    Query query = n == 1 ? single : bq.build();

    try (FSDirectory d = FSDirectory.open(dir);
        DirectoryReader reader = DirectoryReader.open(d)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      searcher.setSimilarity(new BM25Similarity());
      TopDocs top = searcher.search(query, k);
      StoredFields stored = reader.storedFields();

      StringBuilder sb = new StringBuilder(64 + top.scoreDocs.length * 48);
      sb.append("hits");
      sb.append(top.totalHits.relation() == TotalHits.Relation.EQUAL_TO ? "=" : ">=");
      sb.append(top.totalHits.value());
      sb.append('\n');
      for (ScoreDoc sd : top.scoreDocs) {
        sb.append(stored.document(sd.doc).get(LuceneBuild.FIELD_ID));
        sb.append('\t');
        sb.append(sd.score);
        sb.append('\n');
      }
      if (timing) {
        sb.append("elapsed_ns=");
        sb.append(System.nanoTime() - t0);
        sb.append('\n');
      }
      System.out.print(sb);
      System.out.flush();
    }
  }
}
