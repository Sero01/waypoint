package io.waypoint.bench;

import io.waypoint.core.Tokenizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Corpus loading, and the synthetic corpora the differential tests run on. */
public final class Corpus {

  private Corpus() {}

  /**
   * Reads a TSV corpus, one document per line, {@code id\ttext}. This is the
   * shape MS MARCO's {@code collection.tsv} already has, so no conversion step
   * sits between the published corpus and the benchmark.
   *
   * <p>Documents that tokenise to nothing are dropped, because
   * {@code IndexBuilder} refuses them and Lucene would not index the field
   * either; keeping them would make the two engines disagree about
   * {@code docCount}, and therefore about idf.
   */
  public static List<LuceneBuild.Doc> loadTsv(Path path, int limit) throws IOException {
    ArrayList<LuceneBuild.Doc> docs = new ArrayList<>(limit > 0 ? Math.min(limit, 1 << 20) : 1024);
    try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null && (limit <= 0 || docs.size() < limit)) {
        if (line.isEmpty()) {
          continue;
        }
        int tab = line.indexOf('\t');
        String id = tab < 0 ? Integer.toString(docs.size()) : line.substring(0, tab);
        String text = tab < 0 ? line : line.substring(tab + 1);
        if (Tokenizer.tokenize(text).isEmpty()) {
          continue;
        }
        docs.add(new LuceneBuild.Doc(id, text));
      }
    }
    return docs;
  }

  /** Reads {@code queries.dev.tsv} ({@code qid\tquery}) and returns the query strings. */
  public static List<String> loadQueries(Path path, int limit) throws IOException {
    ArrayList<String> out = new ArrayList<>();
    try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null && (limit <= 0 || out.size() < limit)) {
        if (line.isEmpty()) {
          continue;
        }
        int tab = line.indexOf('\t');
        String q = tab < 0 ? line : line.substring(tab + 1);
        if (!Tokenizer.tokenize(q).isEmpty()) {
          out.add(q);
        }
      }
    }
    return out;
  }

  /**
   * A synthetic corpus with a Zipf-shaped vocabulary.
   *
   * <p>Uniform random words would give every term roughly the same document
   * frequency, which is the one case where idf differences cannot show up and
   * where the rarest-term-first leapfrog is never exercised. Real text is
   * Zipfian, so the differential test uses a Zipfian generator: a handful of
   * terms match most documents, a long tail matches two or three, and near-ties
   * in score are common -- which is exactly where a tie-break bug hides.
   */
  public static List<LuceneBuild.Doc> zipfian(
      long seed, int docCount, int vocabSize, int minLen, int maxLen) {
    Random rnd = new Random(seed);
    String[] vocab = new String[vocabSize];
    for (int i = 0; i < vocabSize; i++) {
      vocab[i] = word(i);
    }
    // Precomputed cumulative Zipf weights, so sampling is a binary search.
    double[] cum = new double[vocabSize];
    double acc = 0;
    for (int i = 0; i < vocabSize; i++) {
      acc += 1.0 / (i + 1);
      cum[i] = acc;
    }
    double total = acc;

    ArrayList<LuceneBuild.Doc> docs = new ArrayList<>(docCount);
    StringBuilder sb = new StringBuilder(256);
    for (int d = 0; d < docCount; d++) {
      int len = minLen + rnd.nextInt(Math.max(1, maxLen - minLen + 1));
      sb.setLength(0);
      for (int i = 0; i < len; i++) {
        if (i > 0) {
          sb.append(' ');
        }
        sb.append(vocab[sample(cum, total, rnd.nextDouble())]);
      }
      docs.add(new LuceneBuild.Doc("d" + d, sb.toString()));
    }
    return docs;
  }

  private static int sample(double[] cum, double total, double u) {
    double target = u * total;
    int lo = 0;
    int hi = cum.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (cum[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** Deterministic pronounceable-ish words, so failures are readable in a diff. */
  private static String word(int i) {
    StringBuilder sb = new StringBuilder(6);
    int v = i + 1;
    while (v > 0) {
      sb.append((char) ('a' + (v % 26)));
      v /= 26;
    }
    return sb.toString();
  }

  /** The distinct terms of a corpus, in first-seen order. Used to build queries. */
  public static List<String> vocabulary(List<LuceneBuild.Doc> docs, int limit) {
    ArrayList<String> out = new ArrayList<>();
    java.util.HashSet<String> seen = new java.util.HashSet<>();
    for (LuceneBuild.Doc d : docs) {
      for (String t : Tokenizer.tokenize(d.text())) {
        if (seen.add(t)) {
          out.add(t);
          if (limit > 0 && out.size() >= limit) {
            return out;
          }
        }
      }
    }
    return out;
  }
}
