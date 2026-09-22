package io.waypoint.bench;

import io.waypoint.core.Index;
import io.waypoint.core.IndexBuilder;
import io.waypoint.core.Tokenizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Entry point for the benchmark suite. Each subcommand is a separate experiment. */
public final class BenchMain {

  private BenchMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(2);
      return;
    }
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    switch (args[0]) {
      case "prepare" -> prepare(rest);
      case "coldstart" -> ColdStartSuite.main(rest);
      case "classload" -> classLoad(rest);
      case "latency" -> latency(rest);
      default -> {
        usage();
        System.exit(2);
      }
    }
  }

  private static void usage() {
    System.out.println("waypoint-bench <command>");
    System.out.println();
    System.out.println("  prepare   --corpus <tsv> [--limit N] --wp-out <file> --lucene-out <dir>");
    System.out.println("            [--cfs] [--ram-mb N]");
    System.out.println("            builds both indexes from one corpus and reports build cost");
    System.out.println();
    System.out.println("  coldstart --java <exe> --wp-cp <cp> --wp-index <file>");
    System.out.println("            --lucene-cp <cp> --lucene-index <dir> --query \"terms\"");
    System.out.println("            forks fresh JVMs and measures time to first result");
    System.out.println();
    System.out.println("  latency   --wp-index <file> --lucene-index <dir> [jmh options]");
    System.out.println("            warm query latency through JMH, p50/p95/p99");
    System.out.println();
    System.out.println("  classload --java <exe> --wp-cp <cp> --wp-index <file>");
    System.out.println("            --lucene-cp <cp> --lucene-index <dir> --query \"terms\"");
    System.out.println("            counts classes loaded by one cold query, per package");
  }

  /**
   * Warm query latency through JMH: the table where Lucene wins.
   *
   * <p>Delegates to JMH's own runner so the standard options work unchanged
   * ({@code -f}, {@code -wi}, {@code -rf json}), and forwards the index
   * locations to the forked JVMs as system properties.
   */
  private static void latency(String[] args) throws Exception {
    String wpIndex = null;
    String luceneIndex = null;
    List<String> passthrough = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--wp-index" -> wpIndex = args[++i];
        case "--lucene-index" -> luceneIndex = args[++i];
        default -> passthrough.add(args[i]);
      }
    }
    if (wpIndex == null || luceneIndex == null) {
      usage();
      System.exit(2);
      return;
    }
    System.setProperty("waypoint.index", Path.of(wpIndex).toAbsolutePath().toString());
    System.setProperty("lucene.index", Path.of(luceneIndex).toAbsolutePath().toString());
    // -jvmArgsAppend on the command line REPLACES the @Fork(jvmArgsAppend=...)
    // in the benchmark class rather than adding to it, so the vector module has
    // to be repeated here. Leaving it out silently runs Lucene on its scalar
    // postings decoder -- worth up to 1.80x -- while the benchmark's own javadoc
    // claims it is vectorised. The forked JVM's `jvmArgs` in the JSON output is
    // the thing to check.
    passthrough.add("-jvmArgsAppend");
    passthrough.add("--add-modules=jdk.incubator.vector"
        + " -Dwaypoint.index=" + System.getProperty("waypoint.index")
        + " -Dlucene.index=" + System.getProperty("lucene.index"));
    if (passthrough.stream().noneMatch(a -> a.equals("QueryLatencyBenchmark"))) {
      passthrough.add("QueryLatencyBenchmark");
    }
    org.openjdk.jmh.Main.main(passthrough.toArray(new String[0]));
  }

  // ---------------------------------------------------------------- prepare

  /**
   * Builds both indexes from the same corpus and reports what each cost.
   *
   * <p>The build-throughput numbers printed here are context, not a claim. The
   * teardown measured tokenisation at 74% of a naive index build, which caps
   * any end-to-end build win at roughly 1.3x -- and since both engines here run
   * the <em>same</em> tokenizer, most of both numbers is the same work done
   * twice. Index size is the more interesting column, and it is one Waypoint
   * expects to lose: no bit-packing, no two-level skip lists, no impacts.
   */
  private static void prepare(String[] args) throws IOException {
    Path corpus = null;
    Path wpOut = null;
    Path luceneOut = null;
    int limit = 0;
    int ramMb = 1024;
    boolean cfs = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--corpus" -> corpus = Path.of(args[++i]);
        case "--wp-out" -> wpOut = Path.of(args[++i]);
        case "--lucene-out" -> luceneOut = Path.of(args[++i]);
        case "--limit" -> limit = Integer.parseInt(args[++i]);
        case "--ram-mb" -> ramMb = Integer.parseInt(args[++i]);
        case "--cfs" -> cfs = true;
        default -> throw new IllegalArgumentException("unknown option " + args[i]);
      }
    }
    if (corpus == null || wpOut == null || luceneOut == null) {
      usage();
      System.exit(2);
      return;
    }

    System.out.println("loading " + corpus + (limit > 0 ? " (limit " + limit + ")" : ""));
    long t = System.nanoTime();
    List<LuceneBuild.Doc> docs = Corpus.loadTsv(corpus, limit);
    System.out.printf(
        "loaded %,d documents in %.1f s%n", docs.size(), (System.nanoTime() - t) / 1e9);

    // Tokenise once, up front, and hand the same token lists to Waypoint.
    // Lucene re-tokenises through WaypointAnalyzer, which is the same code.
    System.out.println("building waypoint index -> " + wpOut);
    long wpNanos = System.nanoTime();
    try (IndexBuilder b = IndexBuilder.create(wpOut)) {
      for (LuceneBuild.Doc d : docs) {
        b.add(d.id(), Tokenizer.tokenize(d.text()));
      }
    }
    wpNanos = System.nanoTime() - wpNanos;

    System.out.println("building lucene index -> " + luceneOut);
    if (Files.exists(luceneOut)) {
      deleteRecursively(luceneOut);
    }
    Files.createDirectories(luceneOut);
    long lucNanos = LuceneBuild.buildIndex(luceneOut, docs, cfs, ramMb);

    long wpBytes = Files.size(wpOut);
    long lucBytes = directorySize(luceneOut);

    try (Index idx = Index.open(wpOut)) {
      System.out.printf(
          "%nwaypoint: %,d docs, %,d terms, %,d tokens, avgdl %.2f%n",
          idx.docCount(), idx.termCount(), idx.totalTokens(), idx.averageDocumentLength());
    }

    System.out.println();
    System.out.printf("%-12s %12s %14s %16s%n", "engine", "build (s)", "docs/s", "index bytes");
    System.out.printf(
        "%-12s %12.2f %14.0f %,16d%n",
        "waypoint", wpNanos / 1e9, docs.size() / (wpNanos / 1e9), wpBytes);
    System.out.printf(
        "%-12s %12.2f %14.0f %,16d%n",
        "lucene", lucNanos / 1e9, docs.size() / (lucNanos / 1e9), lucBytes);
    System.out.printf(
        "%nindex size ratio waypoint/lucene: %.2fx%n", wpBytes / (double) lucBytes);
    System.out.println(
        "(build throughput is context, not a claim: both sides run the same tokenizer,"
            + " which the teardown measured at 74% of a naive build)");
  }

  // -------------------------------------------------------------- classload

  /**
   * Counts the classes a single cold query loads, per top-level package.
   *
   * <p>This is the mechanism behind the whole project. The teardown measured a
   * cold Lucene query at 2,068 classes loaded, 523 of them
   * {@code org.apache.lucene} -- SPI codec resolution, the per-format reader
   * stack, and the packed-ints and directory machinery underneath. A number
   * that large is not incidental to the open-time gap; it very largely is the
   * gap. Printing the per-package breakdown makes the claim inspectable rather
   * than rhetorical.
   */
  private static void classLoad(String[] args) throws Exception {
    String javaExe = null;
    String wpCp = null;
    String wpIndex = null;
    String luceneCp = null;
    String luceneIndex = null;
    String query = null;
    int k = 10;
    Path work = Path.of("target").resolve("classload");

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--java" -> javaExe = args[++i];
        case "--wp-cp" -> wpCp = args[++i];
        case "--wp-index" -> wpIndex = args[++i];
        case "--lucene-cp" -> luceneCp = args[++i];
        case "--lucene-index" -> luceneIndex = args[++i];
        case "--query" -> query = args[++i];
        case "--k" -> k = Integer.parseInt(args[++i]);
        case "--work" -> work = Path.of(args[++i]);
        default -> throw new IllegalArgumentException("unknown option " + args[i]);
      }
    }
    if (javaExe == null || wpCp == null || wpIndex == null || luceneCp == null
        || luceneIndex == null || query == null) {
      usage();
      System.exit(2);
      return;
    }
    Files.createDirectories(work);
    String[] terms = query.trim().split("\\s+");

    List<String> wp = new ArrayList<>(List.of(
        javaExe, "-Xlog:class+load=info:file=" + work.resolve("waypoint.log"),
        "-cp", wpCp, "io.waypoint.cli.Main", "search", wpIndex, "-k", Integer.toString(k)));
    wp.addAll(Arrays.asList(terms));

    List<String> luc = new ArrayList<>(List.of(
        javaExe, "--add-modules", "jdk.incubator.vector",
        "-Xlog:class+load=info:file=" + work.resolve("lucene.log"),
        "-cp", luceneCp, "io.waypoint.bench.LuceneSearchMain", luceneIndex,
        "-k", Integer.toString(k)));
    luc.addAll(Arrays.asList(terms));

    exec(wp);
    exec(luc);

    report("waypoint", work.resolve("waypoint.log"));
    report("lucene (SIMD on)", work.resolve("lucene.log"));
  }

  private static void exec(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (InputStream in = p.getInputStream()) {
      in.readAllBytes();
    }
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IOException("command failed with exit " + exit + ": " + String.join(" ", command));
    }
  }

  private static void report(String label, Path log) throws IOException {
    List<String> classes = new ArrayList<>();
    try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
      lines.forEach(line -> {
        int marker = line.indexOf("[class,load] ");
        if (marker < 0) {
          return;
        }
        String rest = line.substring(marker + "[class,load] ".length()).trim();
        int sp = rest.indexOf(' ');
        classes.add(sp < 0 ? rest : rest.substring(0, sp));
      });
    }
    Map<String, Integer> byPackage = new HashMap<>();
    for (String c : classes) {
      byPackage.merge(topPackage(c), 1, Integer::sum);
    }
    System.out.println();
    System.out.println(label + ": " + classes.size() + " classes loaded");
    byPackage.entrySet().stream()
        .sorted((a, b) -> b.getValue() - a.getValue())
        .limit(10)
        .forEach(e -> System.out.printf("    %-28s %5d%n", e.getKey(), e.getValue()));
  }

  private static String topPackage(String className) {
    int a = className.indexOf('.');
    if (a < 0) {
      return "(default)";
    }
    int b = className.indexOf('.', a + 1);
    if (b < 0) {
      return className.substring(0, a);
    }
    int c = className.indexOf('.', b + 1);
    return className.substring(0, c < 0 ? b : c);
  }

  // ----------------------------------------------------------------- utils

  private static long directorySize(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).mapToLong(p -> {
        try {
          return Files.size(p);
        } catch (IOException e) {
          return 0L;
        }
      }).sum();
    }
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignored) {
          // best effort: a stale index directory is not worth failing a build over
        }
      });
    }
  }
}
