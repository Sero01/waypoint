package io.waypoint.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The headline measurement: time from process start to first result on screen,
 * in a fresh JVM, for Waypoint and for Lucene.
 *
 * <p><b>Why this cannot use JMH.</b> JMH warms up. Warm-up is precisely the
 * cost being measured here -- the teardown found that a Lucene index reopened
 * inside one JVM costs 3-5 ms against 218 ms for the first open, so roughly 98%
 * of what a user waits for is one-time cost that a warmed benchmark deletes.
 * So: fork N real processes and time them from the outside.
 *
 * <p><b>Two numbers per configuration.</b> The in-process number comes from the
 * program itself ({@code elapsed_ns}, first statement of {@code main} to first
 * result printed). The external number is this harness's own wall clock around
 * the whole process, and it includes JVM startup. The external number is the
 * headline, because it is the one a person actually feels, and it is the less
 * flattering of the two: JVM startup is a floor both engines pay, so including
 * it can only shrink the ratio.
 *
 * <p><b>The counter-experiments are the point.</b> Any reader who knows the JVM
 * will immediately say "class loading is your whole result -- use AppCDS, or
 * the Leyden AOT cache". So the suite runs both, on <em>both</em> engines,
 * including the training runs, with every flag visible in this file. If they
 * close the gap, that is the finding and it leads the README. Running the
 * experiment that could falsify the claim is the difference between a
 * measurement and a blog post.
 *
 * <p><b>Lucene is given every advantage:</b> a {@code forceMerge(1)}
 * single-segment index, freqs-and-norms-only index options matching Waypoint's,
 * the same analyzer and token stream, the same {@code k}, and both settings of
 * {@code --add-modules jdk.incubator.vector} -- the flag that switches Lucene
 * onto its Panama SIMD postings decoder, which the teardown measured as worth
 * up to 1.80x on warm query latency and which is <em>off</em> in a stock
 * {@code java -jar}. Every Lucene configuration is run scalar and vectorised,
 * and Lucene is credited with whichever is faster.
 *
 * <p>Before timing anything the suite asserts that both engines return the same
 * ranked documents for the query, with scores agreeing to 1e-6. A benchmark
 * where the two sides answer differently is measuring two different amounts of
 * work.
 */
public final class ColdStartSuite {

  private ColdStartSuite() {}

  /**
   * One measured configuration.
   *
   * @param baseline a floor measurement rather than a search: it is expected to
   *     print no results and to exit non-zero, so it is exempt from the
   *     agreement check. See the {@code floor} configurations in {@code main}.
   */
  private record Config(
      String label, String engine, List<String> command, List<String> training, boolean baseline) {

    Config(String label, String engine, List<String> command, List<String> training) {
      this(label, engine, command, training, false);
    }
  }

  private record Sample(double externalMs, double inProcessMs) {}

  private record Result(
      String label,
      String engine,
      List<String> command,
      double externalP5,
      double externalMedian,
      double externalP95,
      double inProcessP5,
      double inProcessMedian,
      double inProcessP95,
      String diagnostics) {}

  public static void main(String[] args) throws Exception {
    String javaExe = null;
    String wpCp = null;
    String wpIndex = null;
    String luceneCp = null;
    String luceneIndex = null;
    String query = null;
    Path outDir = Path.of("results");
    Path work = Path.of("target").resolve("coldstart");
    int k = 10;
    int runs = 20;
    int warmup = 3;
    boolean and = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--java" -> javaExe = args[++i];
        case "--wp-cp" -> wpCp = args[++i];
        case "--wp-index" -> wpIndex = args[++i];
        case "--lucene-cp" -> luceneCp = args[++i];
        case "--lucene-index" -> luceneIndex = args[++i];
        case "--query" -> query = args[++i];
        case "--k" -> k = Integer.parseInt(args[++i]);
        case "--runs" -> runs = Integer.parseInt(args[++i]);
        case "--warmup" -> warmup = Integer.parseInt(args[++i]);
        case "--and" -> and = true;
        case "--out" -> outDir = Path.of(args[++i]);
        case "--work" -> work = Path.of(args[++i]);
        default -> throw new IllegalArgumentException("unknown option " + args[i]);
      }
    }
    if (javaExe == null || wpCp == null || wpIndex == null || luceneCp == null
        || luceneIndex == null || query == null) {
      System.err.println(
          "usage: ColdStartSuite --java <javaExe> --wp-cp <cp> --wp-index <file>"
              + " --lucene-cp <cp> --lucene-index <dir> --query \"terms\""
              + " [--and] [--k N] [--runs N] [--warmup N] [--out dir] [--work dir]");
      System.exit(2);
      return;
    }
    Files.createDirectories(outDir);
    Files.createDirectories(work);

    String[] terms = query.trim().split("\\s+");

    List<String> wpArgs = new ArrayList<>(List.of("io.waypoint.cli.Main", "search", wpIndex));
    wpArgs.addAll(searchOptions(k, and));
    wpArgs.addAll(Arrays.asList(terms));

    List<String> lucArgs =
        new ArrayList<>(List.of("io.waypoint.bench.LuceneSearchMain", luceneIndex));
    lucArgs.addAll(searchOptions(k, and));
    lucArgs.addAll(Arrays.asList(terms));

    // Lucene's SIMD postings decoder lives behind an incubator module, which is
    // off in a stock `java -jar`. It is worth up to 1.80x on warm query
    // latency, so Lucene must be measured with it -- but it is also 200-odd
    // extra classes to load, so it must be measured without it too. Every
    // Lucene configuration below therefore exists in both forms, and Lucene is
    // credited with whichever turns out to be faster.
    List<String> simd = List.of("--add-modules", "jdk.incubator.vector");
    List<String> scalar = List.of();

    Path wpCds = work.resolve("waypoint.jsa");
    Path wpAot = work.resolve("waypoint.aot");

    List<Config> configs = new ArrayList<>();

    // The floor. Each engine's own main class, invoked with no arguments, so it
    // prints its usage and exits without opening anything: JVM startup, this
    // classpath, that main class, nothing else.
    //
    // Without this row the external numbers are not interpretable. Roughly 85 ms
    // of every measurement below is the JVM booting, which both engines pay and
    // neither can do anything about, and which therefore drags every ratio
    // towards 1.0. Measuring each engine's floor on its own classpath -- rather
    // than picking one floor and applying it to both -- removes the obvious
    // objection that the two classpaths cost different amounts to scan.
    configs.add(new Config("(floor) jvm + waypoint classpath", "floor",
        cmd(javaExe, List.of("-cp", wpCp), List.of("io.waypoint.cli.Main")), List.of(), true));
    configs.add(new Config("(floor) jvm + lucene classpath", "floor",
        cmd(javaExe, List.of("-cp", luceneCp), List.of("io.waypoint.bench.LuceneSearchMain")),
        List.of(), true));

    configs.add(new Config("waypoint", "waypoint",
        cmd(javaExe, List.of("-cp", wpCp), wpArgs), List.of()));

    configs.add(new Config("waypoint + AppCDS", "waypoint",
        cmd(javaExe, List.of("-XX:SharedArchiveFile=" + wpCds, "-cp", wpCp), wpArgs),
        cmd(javaExe, List.of("-XX:ArchiveClassesAtExit=" + wpCds, "-cp", wpCp), wpArgs)));

    configs.add(new Config("waypoint + AOT cache", "waypoint",
        cmd(javaExe, List.of("-XX:AOTCache=" + wpAot, "-cp", wpCp), wpArgs),
        cmd(javaExe, List.of("-XX:AOTCacheOutput=" + wpAot, "-cp", wpCp), wpArgs)));

    for (boolean vector : new boolean[] {false, true}) {
      List<String> mod = vector ? simd : scalar;
      String tag = vector ? " + SIMD" : " (scalar)";
      String slug = vector ? "simd" : "scalar";
      Path cds = work.resolve("lucene-" + slug + ".jsa");
      Path aot = work.resolve("lucene-" + slug + ".aot");

      configs.add(new Config("lucene" + tag, "lucene",
          cmd(javaExe, concat(mod, List.of("-cp", luceneCp)), lucArgs), List.of()));

      configs.add(new Config("lucene" + tag + " + AppCDS", "lucene",
          cmd(javaExe, concat(mod, List.of("-XX:SharedArchiveFile=" + cds, "-cp", luceneCp)),
              lucArgs),
          cmd(javaExe, concat(mod, List.of("-XX:ArchiveClassesAtExit=" + cds, "-cp", luceneCp)),
              lucArgs)));

      configs.add(new Config("lucene" + tag + " + AOT cache", "lucene",
          cmd(javaExe, concat(mod, List.of("-XX:AOTCache=" + aot, "-cp", luceneCp)), lucArgs),
          cmd(javaExe, concat(mod, List.of("-XX:AOTCacheOutput=" + aot, "-cp", luceneCp)),
              lucArgs)));
    }

    // ---- integrity check: both engines must answer the query identically ----
    Config wpReference = reference(configs, "waypoint");
    Config lucReference = reference(configs, "lucene");
    Run wpProbe = run(wpReference.command());
    Run lucProbe = run(lucReference.command());
    String wpOut = resultLines(wpProbe.stdout());
    String lucOut = resultLines(lucProbe.stdout());
    if (wpProbe.exit() != 0 || lucProbe.exit() != 0 || !sameResults(wpOut, lucOut) || wpOut.isEmpty()) {
      System.err.println("ABORTING: the two engines do not agree on this query.");
      System.err.println("--- waypoint (exit " + wpProbe.exit() + ") ---\n" + wpProbe.stdout());
      System.err.println("--- lucene   (exit " + lucProbe.exit() + ") ---\n" + lucProbe.stdout());
      System.exit(1);
      return;
    }
    System.out.println("both engines return identical ranked results:");
    System.out.println(wpOut);
    System.out.println("match counts differ by design -- waypoint " + hitsLine(wpProbe.stdout())
        + ", lucene " + hitsLine(lucProbe.stdout())
        + " (lucene stops counting at 1000; see resultLines())");

    // ---- training runs, then one probe per configuration --------------------
    List<Config> live = new ArrayList<>();
    List<String> probeDiagnostics = new ArrayList<>();
    for (Config c : configs) {
      if (!c.training().isEmpty()) {
        Run t = run(c.training());
        if (t.exit() != 0) {
          System.out.println("SKIP " + c.label() + ": training run failed (exit " + t.exit() + ")");
          System.out.println(indent(t.stdout()));
          continue;
        }
      }
      Run probe = run(c.command());
      if (!c.baseline()) {
        if (probe.exit() != 0) {
          System.out.println("SKIP " + c.label() + ": exit " + probe.exit());
          System.out.println(indent(probe.stdout()));
          continue;
        }
        if (!sameResults(resultLines(probe.stdout()), wpOut)) {
          System.out.println("SKIP " + c.label() + ": produced different results");
          System.out.println(indent(resultLines(probe.stdout())));
          continue;
        }
      }
      live.add(c);
      probeDiagnostics.add(diagnostics(probe.stdout()));
    }

    // ---- measure round-robin, not configuration by configuration -----------
    //
    // This ordering is load-bearing on a laptop under a desktop OS. Measuring
    // one configuration to completion before starting the next means any
    // background work that arrives partway through lands entirely on whichever
    // configuration happened to be running, and shows up as a difference
    // between engines rather than as noise. An early run of this suite did
    // exactly that: the floor row tripled between blocks and Lucene's numbers
    // came out four times too slow.
    //
    // Cycling through every configuration on each pass spreads any such drift
    // across all of them, so it inflates the absolute numbers without
    // distorting the ratios -- and the floor rows make it visible when it
    // happens.
    int n = live.size();
    double[][] external = new double[n][runs];
    double[][] inproc = new double[n][runs];

    for (int w = 0; w < warmup; w++) {
      for (Config c : live) {
        run(c.command());
      }
    }
    for (int r = 0; r < runs; r++) {
      for (int i = 0; i < n; i++) {
        Sample s = measure(live.get(i).command());
        external[i][r] = s.externalMs();
        inproc[i][r] = s.inProcessMs();
      }
    }

    List<Result> results = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Config c = live.get(i);
      Arrays.sort(external[i]);
      Arrays.sort(inproc[i]);
      results.add(new Result(
          c.label(), c.engine(), c.command(),
          pct(external[i], 5), pct(external[i], 50), pct(external[i], 95),
          pct(inproc[i], 5), pct(inproc[i], 50), pct(inproc[i], 95),
          probeDiagnostics.get(i)));
      System.out.printf(
          "%-32s external p50 %7.1f ms  (p5 %6.1f, p95 %6.1f)   in-process p50 %7.1f ms%n",
          c.label(), pct(external[i], 50), pct(external[i], 5), pct(external[i], 95),
          pct(inproc[i], 50));
    }

    writeReport(outDir, results, query, k, and, runs, warmup);
  }

  // ------------------------------------------------------------------------

  /**
   * The search options both CLIs accept, in an order both parsers understand:
   * every flag before the first query term, and {@code -k} immediately followed
   * by its value.
   */
  private static List<String> searchOptions(int k, boolean and) {
    List<String> options = new ArrayList<>();
    if (and) {
      options.add("--and");
    }
    options.add("-k");
    options.add(Integer.toString(k));
    options.add("-t");
    return options;
  }

  private static List<String> concat(List<String> a, List<String> b) {
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static List<String> cmd(String javaExe, List<String> flags, List<String> tail) {
    List<String> c = new ArrayList<>();
    c.add(javaExe);
    c.addAll(flags);
    c.addAll(tail);
    return c;
  }

  private record Run(int exit, String stdout) {}

  private static Run run(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out;
    try (InputStream in = p.getInputStream()) {
      out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int exit = p.waitFor();
    return new Run(exit, out);
  }

  private static Sample measure(List<String> command) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    long t0 = System.nanoTime();
    Process p = pb.start();
    String out;
    try (InputStream in = p.getInputStream()) {
      out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    p.waitFor();
    double externalMs = (System.nanoTime() - t0) / 1e6;
    double inProcessMs = Double.NaN;
    for (String line : out.split("\n")) {
      if (line.startsWith("elapsed_ns=")) {
        inProcessMs = Long.parseLong(line.substring("elapsed_ns=".length()).trim()) / 1e6;
      }
    }
    return new Sample(externalMs, inProcessMs);
  }

  /**
   * The ranked rows only -- {@code key\tscore} -- so the two engines can be
   * compared for agreement.
   *
   * <p>The {@code hits} line is excluded on purpose, and this is the one place
   * the two engines legitimately disagree. Lucene stops counting matches at
   * {@code TOTAL_HITS_THRESHOLD = 1000} and prints a lower bound; Waypoint has
   * no pruning, visits every match anyway, and prints the true count. Requiring
   * those lines to match would mean either crippling Waypoint's output or
   * pretending Lucene's number is something it is not. The ranked rows,
   * however, must be byte-identical -- same documents, same order, same scores
   * -- or the benchmark is timing two different amounts of work.
   */
  private static String resultLines(String out) {
    StringBuilder sb = new StringBuilder();
    for (String line : out.split("\n")) {
      String t = line.strip();
      if (t.isEmpty() || t.startsWith("elapsed_ns=") || t.startsWith("hits")
          || t.startsWith("WARNING") || t.contains("VectorizationProvider")
          || t.startsWith("INFO:") || t.endsWith("lookup")
          // JVM unified-logging diagnostics, e.g. the CDS archive warnings
          // reported separately by diagnostics(); they are not results.
          || t.startsWith("[")) {
        continue;
      }
      sb.append(t).append('\n');
    }
    return sb.toString();
  }

  /**
   * Whether two engines returned the same answer: the same documents, in the
   * same order, with scores agreeing to 1e-6 relative.
   *
   * <p>Not a string comparison, and the reason is the same one the differential
   * test documents. Float addition is not associative. For a conjunction Lucene
   * sums clause contributions in the order the clauses were written, while
   * Waypoint sums them rarest-term-first, so the two can land one ulp apart --
   * {@code 9.123993} against {@code 9.123994}. Demanding byte equality would
   * reject a correct result; the tolerance here is the same 1e-6 the
   * differential test holds, measured across 15,941 scores of which 99.2% are
   * bit-identical.
   */
  private static boolean sameResults(String a, String b) {
    String[] ra = a.split("\n");
    String[] rb = b.split("\n");
    if (ra.length != rb.length) {
      return false;
    }
    for (int i = 0; i < ra.length; i++) {
      int ta = ra[i].lastIndexOf('\t');
      int tb = rb[i].lastIndexOf('\t');
      if (ta < 0 || tb < 0) {
        if (!ra[i].equals(rb[i])) {
          return false;
        }
        continue;
      }
      if (!ra[i].substring(0, ta).equals(rb[i].substring(0, tb))) {
        return false;
      }
      float sa = Float.parseFloat(ra[i].substring(ta + 1));
      float sb = Float.parseFloat(rb[i].substring(tb + 1));
      if (Math.abs(sa - sb) / Math.max(Math.abs(sa), 1e-6f) > 1e-6f) {
        return false;
      }
    }
    return true;
  }

  /** The {@code hits} line, reported alongside the results for disclosure. */
  private static String hitsLine(String out) {
    for (String line : out.split("\n")) {
      String t = line.strip();
      if (t.startsWith("hits")) {
        return t;
      }
    }
    return "(none)";
  }

  /**
   * JVM unified-logging lines emitted by a configuration, deduplicated.
   *
   * <p>These are reported rather than swallowed. On JDK 25 a dynamic CDS dump
   * does not record {@code --add-modules}, so replaying a Lucene + SIMD archive
   * logs a mismatch on {@code jdk.module.addmods} and falls back to unoptimised
   * module handling. The archive still helps, but Lucene is not getting the
   * full benefit of AppCDS in that configuration, and a table that hid the
   * warning would be quietly understating what Lucene could do.
   */
  private static String diagnostics(String out) {
    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
    for (String line : out.split("\n")) {
      String t = line.strip();
      int close = t.indexOf(']');
      if (t.startsWith("[") && close > 0) {
        seen.add(t.substring(close + 1).strip());
      }
    }
    return String.join("; ", seen);
  }

  private static String indent(String s) {
    StringBuilder sb = new StringBuilder();
    for (String line : s.split("\n")) {
      sb.append("    ").append(line).append('\n');
    }
    return sb.toString();
  }

  /** Nearest-rank percentile of an already-sorted array. */
  private static double pct(double[] sorted, int p) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
  }

  /** The untrained, non-baseline configuration of an engine: the one to compare with. */
  private static Config reference(List<Config> configs, String engine) {
    for (Config c : configs) {
      if (c.engine().equals(engine) && !c.baseline() && c.training().isEmpty()) {
        return c;
      }
    }
    throw new IllegalStateException("no reference configuration for " + engine);
  }

  /**
   * Total and free physical memory, when the platform will tell us.
   *
   * <p>Disclosed because it moves the numbers. Forking hundreds of JVMs that
   * each map a 70-80 MB index depends on the page cache holding those files; on
   * a machine short of free memory the floor row alone can triple. Reporting it
   * lets a reader see whether the absolute milliseconds on the page were
   * measured under memory pressure, without having to guess from the spread.
   */
  private static String physicalMemory() {
    // com.sun.management.OperatingSystemMXBean is exported by the jdk.management
    // module, which is in the default module graph. Reaching the same numbers
    // reflectively through the platform bean's implementation class fails with
    // InaccessibleObjectException instead, and fails silently if the exception
    // is swallowed -- which is how this method first shipped returning "".
    java.lang.management.OperatingSystemMXBean os =
        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
    if (!(os instanceof com.sun.management.OperatingSystemMXBean sun)) {
      return "";
    }
    return ", " + (sun.getTotalMemorySize() >> 20) + " MB physical memory ("
        + (sun.getFreeMemorySize() >> 20) + " MB free at the start of this run)";
  }

  /** The floor row for an engine's classpath, or NaN if it was not measured. */
  private static double floorFor(List<Result> results, String engine) {
    String want = engine.equals("lucene") ? "lucene classpath" : "waypoint classpath";
    for (Result r : results) {
      if (r.engine().equals("floor") && r.label().contains(want)) {
        return r.externalMedian();
      }
    }
    return Double.NaN;
  }

  /** The fastest configuration of an engine, by external median. */
  private static Result best(List<Result> results, String engine) {
    Result found = null;
    for (Result r : results) {
      if (r.engine().equals(engine)
          && (found == null || r.externalMedian() < found.externalMedian())) {
        found = r;
      }
    }
    return found;
  }

  /** The untuned configuration of an engine: no AOT cache, no AppCDS. */
  private static Result named(List<Result> results, String engine) {
    for (Result r : results) {
      if (r.engine().equals(engine) && !r.label().contains("AOT")
          && !r.label().contains("AppCDS")) {
        return r;
      }
    }
    return null;
  }

  private static void writeReport(
      Path outDir, List<Result> results, String query, int k, boolean and, int runs, int warmup)
      throws IOException {

    StringBuilder md = new StringBuilder();
    md.append("# Cold start: time to first result\n\n");
    md.append("Query: `").append(query).append("` (")
        .append(and ? "AND" : "OR").append("), k=").append(k)
        .append(", ").append(runs).append(" forked JVMs per configuration after ")
        .append(warmup).append(" discarded warm-up runs.\n\n");
    md.append("`java.version` ").append(System.getProperty("java.version"))
        .append(" / ").append(System.getProperty("java.vm.name"))
        .append(" ").append(System.getProperty("java.vm.version")).append("\n\n");
    md.append("`os` ").append(System.getProperty("os.name"))
        .append(" ").append(System.getProperty("os.version"))
        .append(" ").append(System.getProperty("os.arch"))
        .append(", ").append(Runtime.getRuntime().availableProcessors())
        .append(" available processors").append(physicalMemory()).append("\n\n");

    double wpFloor = floorFor(results, "waypoint");
    double lucFloor = floorFor(results, "lucene");

    md.append("`floor` ").append(fmt(wpFloor))
        .append(" ms on the waypoint classpath, ").append(fmt(lucFloor))
        .append(" ms on the lucene classpath -- JVM startup that both engines pay"
            + " and neither can avoid.\n\n");

    // The floor is also the noise meter. It does the least work of anything
    // here, so if its own spread is wide, the machine was busy and every
    // absolute number on this page is inflated. Saying so is cheaper than
    // quietly publishing a laptop's background activity as a result.
    Result floorRow = null;
    for (Result r : results) {
      if (r.engine().equals("floor") && r.label().contains("waypoint")) {
        floorRow = r;
      }
    }
    if (floorRow != null && floorRow.externalP95() > 1.4 * floorRow.externalP5()) {
      String warning = "**Noisy run.** The floor configuration, which does nothing but start a"
          + " JVM, varied from " + fmt(floorRow.externalP5()) + " ms to "
          + fmt(floorRow.externalP95()) + " ms across its own samples. The machine was not"
          + " quiet. Configurations are measured round-robin so drift lands on all of them"
          + " alike and the ratios survive, but treat the absolute milliseconds here as an"
          + " upper bound and re-run on an idle machine before quoting them.\n\n";
      md.append(warning);
      System.out.println();
      System.out.println(warning.replace("**", ""));
    }

    md.append("| configuration | external p50 (ms) | p5 | p95 | above floor (ms)"
        + " | in-process p50 (ms) |\n");
    md.append("|---|---:|---:|---:|---:|---:|\n");
    for (Result r : results) {
      double floor = r.engine().equals("lucene") ? lucFloor : wpFloor;
      String above = r.engine().equals("floor")
          ? "-" : fmt(r.externalMedian() - floor);
      md.append("| ").append(r.label())
          .append(" | ").append(fmt(r.externalMedian()))
          .append(" | ").append(fmt(r.externalP5()))
          .append(" | ").append(fmt(r.externalP95()))
          .append(" | ").append(above)
          .append(" | ").append(fmt(r.inProcessMedian()))
          .append(" |\n");
    }

    Result bestWaypoint = best(results, "waypoint");
    Result bestLucene = best(results, "lucene");
    Result plainWaypoint = named(results, "waypoint");
    Result plainLucene = null;
    for (Result r : results) {
      if (r.engine().equals("lucene") && !r.label().contains("AOT")
          && !r.label().contains("AppCDS")
          && (plainLucene == null || r.externalMedian() < plainLucene.externalMedian())) {
        plainLucene = r;
      }
    }

    if (plainWaypoint != null && plainLucene != null) {
      md.append("\n## Result\n\n");
      md.append("**Stock `java -cp`, which is what almost everyone runs: ")
          .append(fmt(plainWaypoint.externalMedian())).append(" ms vs ")
          .append(fmt(plainLucene.externalMedian())).append(" ms (")
          .append(plainLucene.label()).append(") = **")
          .append(fmt(plainLucene.externalMedian() / plainWaypoint.externalMedian()))
          .append("x**.\n\n");
    }
    if (bestWaypoint != null && bestLucene != null) {
      double ratio = bestLucene.externalMedian() / bestWaypoint.externalMedian();
      md.append("**Both engines tuned as hard as JDK 25 allows: ")
          .append(bestWaypoint.label()).append(" ").append(fmt(bestWaypoint.externalMedian()))
          .append(" ms vs ")
          .append(bestLucene.label()).append(" ").append(fmt(bestLucene.externalMedian()))
          .append(" ms = ").append(fmt(ratio)).append("x.**\n\n");

      // The pre-registered kill criterion from the design document, evaluated
      // here rather than in prose, so it cannot be quietly softened after the
      // fact.
      md.append("Pre-registered kill criterion (design doc section 3): with the AOT cache"
          + " enabled for **both** engines and Lucene given a `forceMerge(1)` single-segment"
          + " index, the external median must be at least **3x** better or the headline claim"
          + " is withdrawn. Measured: **").append(fmt(ratio)).append("x** -- ")
          .append(ratio >= 3.0 ? "**met**" : "**not met**").append(".\n\n");

      if (bestWaypoint.externalMedian() > wpFloor && bestLucene.externalMedian() > lucFloor) {
        double wpAbove = bestWaypoint.externalMedian() - wpFloor;
        double lucAbove = bestLucene.externalMedian() - lucFloor;
        md.append("Discounting JVM startup, which is a floor neither engine controls, the work"
            + " actually attributable to the engine is ")
            .append(fmt(wpAbove)).append(" ms vs ").append(fmt(lucAbove))
            .append(" ms = **").append(fmt(lucAbove / wpAbove)).append("x**.\n");
      }
    }

    boolean anyDiagnostics = false;
    for (Result r : results) {
      if (!r.diagnostics().isEmpty()) {
        anyDiagnostics = true;
      }
    }
    if (anyDiagnostics) {
      md.append("\n## JVM diagnostics\n\n");
      md.append("Reported rather than suppressed. Where a configuration did not get the full"
          + " benefit of what it was given, that favours the other engine and has to be"
          + " visible.\n\n");
      for (Result r : results) {
        if (!r.diagnostics().isEmpty()) {
          md.append("- **").append(r.label()).append("**: ").append(r.diagnostics()).append("\n");
        }
      }
    }

    md.append("\n## Exact commands\n\n");
    for (Result r : results) {
      md.append("- **").append(r.label()).append("**\n  ```\n  ")
          .append(String.join(" ", r.command())).append("\n  ```\n");
    }

    Path mdPath = outDir.resolve("coldstart.md");
    Files.writeString(mdPath, md.toString(), StandardCharsets.UTF_8);
    System.out.println("\nwrote " + mdPath.toAbsolutePath());
  }

  private static String fmt(double v) {
    return Double.isNaN(v) ? "-" : String.valueOf(Math.round(v * 10) / 10.0);
  }
}
