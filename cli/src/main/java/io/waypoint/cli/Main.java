package io.waypoint.cli;

import io.waypoint.core.Hits;
import io.waypoint.core.Index;
import io.waypoint.core.IndexBuilder;
import io.waypoint.core.Query;
import io.waypoint.core.Tokenizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The command line. Also the instrument: this is the process whose wall clock
 * the cold-start benchmark measures.
 *
 * <p>Everything on the search path is hand-rolled for that reason. There is no
 * argument-parsing library, no logging, no {@code String.format} (which pulls
 * in {@code java.util.Formatter} and its regex), no streams and no lambdas.
 * Loading a class costs roughly as much as the query itself here, so the usual
 * trade -- a little convenience for a little startup -- comes out the other way.
 *
 * <p>{@code -t} prints {@code elapsed_ns}, measured from the first statement of
 * {@code main} to the moment the first result is on screen. That is the
 * in-process number. The external number, which is the one the README leads
 * with, is measured by the harness around the whole process and includes JVM
 * startup, because that is what a person actually waits for.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    long t0 = System.nanoTime();
    PrintStream out = System.out;
    if (args.length == 0) {
      usage(out);
      System.exit(2);
      return;
    }
    try {
      String cmd = args[0];
      if (cmd.equals("search")) {
        System.exit(search(args, t0, out));
      } else if (cmd.equals("index")) {
        System.exit(index(args, out));
      } else if (cmd.equals("verify")) {
        System.exit(verify(args, out));
      } else if (cmd.equals("stats")) {
        System.exit(stats(args, out));
      } else {
        out.println("unknown command: " + cmd);
        usage(out);
        System.exit(2);
      }
    } catch (IllegalArgumentException | IllegalStateException e) {
      // IllegalStateException is how core reports a phrase query against an
      // index built without positions: the caller's problem, not a crash.
      System.err.println("error: " + e.getMessage());
      System.exit(2);
    } catch (IOException e) {
      System.err.println("error: " + e.getMessage());
      System.exit(1);
    }
  }

  // ---------------------------------------------------------------- search

  private static int search(String[] args, long t0, PrintStream out) throws IOException {
    if (args.length < 3) {
      usage(out);
      return 2;
    }
    Path indexPath = Path.of(args[1]);

    int k = 10;
    boolean and = false;
    boolean phrase = false;
    boolean timing = false;
    int i = 2;
    while (i < args.length && args[i].startsWith("-")) {
      String a = args[i];
      if (a.equals("-k")) {
        k = Integer.parseInt(args[++i]);
      } else if (a.equals("--and")) {
        and = true;
      } else if (a.equals("--phrase")) {
        phrase = true;
      } else if (a.equals("-t")) {
        timing = true;
      } else {
        throw new IllegalArgumentException("unknown option " + a);
      }
      i++;
    }
    if (and && phrase) {
      throw new IllegalArgumentException("--and and --phrase are mutually exclusive");
    }

    int nTerms = args.length - i;
    if (nTerms == 0) {
      throw new IllegalArgumentException("no query terms");
    }
    Query[] clauses = new Query[nTerms];
    int n = 0;
    for (int j = i; j < args.length; j++) {
      String t = Tokenizer.normalizeTerm(args[j]);
      if (t != null) {
        clauses[n++] = Query.term(t);
      }
    }
    if (n == 0) {
      throw new IllegalArgumentException("query contains no indexable terms");
    }
    Query query;
    if (phrase) {
      String[] words = new String[n];
      for (int j = 0; j < n; j++) {
        words[j] = ((Query.Term) clauses[j]).text();
      }
      query = Query.phrase(words);
    } else if (n == 1) {
      query = clauses[0];
    } else {
      Query[] exact = new Query[n];
      System.arraycopy(clauses, 0, exact, 0, n);
      query = and ? Query.and(exact) : Query.or(exact);
    }

    try (Index index = Index.open(indexPath)) {
      Hits hits = index.search(query, k);

      StringBuilder sb = new StringBuilder(64 + hits.size() * 48);
      sb.append("hits");
      sb.append(hits.totalHitsExact() ? "=" : ">=");
      sb.append(hits.totalHits());
      sb.append('\n');
      for (int h = 0; h < hits.size(); h++) {
        sb.append(hits.key(h));
        sb.append('\t');
        sb.append(hits.score(h));
        sb.append('\n');
      }
      if (timing) {
        sb.append("elapsed_ns=");
        sb.append(System.nanoTime() - t0);
        sb.append('\n');
      }
      out.print(sb);
      out.flush();
    }
    return 0;
  }

  // ----------------------------------------------------------------- index

  /**
   * Builds an index from a TSV corpus: one document per line, {@code id\ttext}.
   * Lines with no indexable tokens are skipped and counted, because
   * {@link IndexBuilder} refuses them on purpose -- an empty document would
   * still take a document id and shift {@code docCount}, which feeds idf.
   */
  private static int index(String[] args, PrintStream out) throws IOException {
    if (args.length < 3) {
      usage(out);
      return 2;
    }
    Path corpus = Path.of(args[1]);
    Path indexPath = Path.of(args[2]);
    boolean positions = false;
    for (int j = 3; j < args.length; j++) {
      if (args[j].equals("--positions")) {
        positions = true;
      } else {
        throw new IllegalArgumentException("unknown option " + args[j]);
      }
    }

    long start = System.nanoTime();
    int added = 0;
    int skipped = 0;
    try (IndexBuilder b = IndexBuilder.create(indexPath, positions);
        BufferedReader r = Files.newBufferedReader(corpus, StandardCharsets.UTF_8)) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        int tab = line.indexOf('\t');
        String id;
        String text;
        if (tab < 0) {
          id = Integer.toString(added);
          text = line;
        } else {
          id = line.substring(0, tab);
          text = line.substring(tab + 1);
        }
        List<String> tokens = Tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
          skipped++;
          continue;
        }
        b.add(id, tokens);
        added++;
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000L;
    out.println(
        "indexed " + added + " documents (" + skipped + " empty, skipped) in " + ms + " ms");
    out.println("index: " + indexPath.toAbsolutePath() + " (" + Files.size(indexPath) + " bytes)");
    return 0;
  }

  // ------------------------------------------------------------ verify/stats

  private static int verify(String[] args, PrintStream out) throws IOException {
    if (args.length < 2) {
      usage(out);
      return 2;
    }
    try (Index index = Index.open(Path.of(args[1]))) {
      index.verify();
      out.println("ok: header, format version and CRC32 all check out");
    }
    return 0;
  }

  private static int stats(String[] args, PrintStream out) throws IOException {
    if (args.length < 2) {
      usage(out);
      return 2;
    }
    try (Index index = Index.open(Path.of(args[1]))) {
      out.println("documents      " + index.docCount());
      out.println("terms          " + index.termCount());
      out.println("tokens         " + index.totalTokens());
      out.println("avg doc length " + index.averageDocumentLength());
      out.println("file bytes     " + index.sizeInBytes());
    }
    return 0;
  }

  private static void usage(PrintStream out) {
    out.println("waypoint - a static, read-only inverted index");
    out.println();
    out.println("  index  <corpus.tsv> <index.wpt> [--positions]");
    out.println("         one document per line, id<TAB>text");
    out.println("         --positions enables phrase search; roughly doubles the file");
    out.println();
    out.println("  search <index.wpt> [-k N] [--and|--phrase] [-t] <term> [term ...]");
    out.println("         terms are OR-ed unless --and or --phrase is given");
    out.println("         --phrase needs an index built with --positions");
    out.println("         -t prints elapsed_ns");
    out.println();
    out.println("  verify <index.wpt>    recompute and check the CRC32 footer");
    out.println("  stats  <index.wpt>    header statistics");
  }
}
