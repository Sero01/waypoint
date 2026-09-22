package io.waypoint.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.waypoint.core.IndexBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The most interesting test in the repository.
 *
 * <p>It forks a real JVM with {@code -Xlog:class+load=info}, runs one cold
 * query through the CLI, parses the log, and fails if more classes were loaded
 * than the committed budget allows. That converts the project's central claim
 * from a sentence in a README into a build failure.
 *
 * <p>The technique is borrowed from the measurement that motivated the whole
 * project: a cold Lucene query on a single-segment index loaded <b>2,068
 * classes, 523 of them {@code org.apache.lucene}</b> -- SPI codec resolution,
 * the per-format reader stack, the packed-ints machinery, {@code MMapDirectory}.
 * That is not a detail attached to the open-time gap; it very largely is the
 * gap.
 *
 * <p>The budget therefore guards something specific and fragile. Adding a
 * lambda to the open path, a {@code switch} over a sealed type, a
 * {@code String.format} call, or a regex would each pull in dozens of classes
 * -- invisibly, with every test still green, and only the benchmark getting
 * slower. This test makes that visible at build time.
 *
 * <p>The absolute number is not portable across JDK builds, so the assertion is
 * a ceiling with headroom rather than an equality, and the observed count is
 * printed on every run.
 */
class ClassBudgetTest {

  /**
   * Total classes a single cold query may load, including everything the JVM
   * bootstraps before {@code main} is even entered. Raise this only with a
   * measurement and a reason; every increase is time a user waits for.
   */
  private static final int TOTAL_CLASS_BUDGET = 1_100;

  /** Of those, how many may come from Waypoint itself. */
  private static final int WAYPOINT_CLASS_BUDGET = 25;

  @Test
  void aColdQueryLoadsFewerClassesThanTheBudgetAllows(@TempDir Path tmp) throws Exception {
    Path index = tmp.resolve("budget.wpt");
    try (IndexBuilder b = IndexBuilder.create(index)) {
      for (int i = 0; i < 5_000; i++) {
        b.addText("d" + i, "alpha beta gamma delta token" + i + " shared vocabulary here");
      }
    }

    Path log = tmp.resolve("class-load.log");
    List<String> command = List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xlog:class+load=info:file=" + log,
        "-cp", System.getProperty("java.class.path"),
        "io.waypoint.cli.Main", "search", index.toString(), "-k", "10", "alpha");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output;
    try (InputStream in = p.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertEquals(0, p.waitFor(), "the forked CLI failed:\n" + output);
    assertTrue(output.contains("hits"), "the forked CLI printed no results:\n" + output);

    List<String> loaded = parse(log);
    TreeMap<String, Integer> byPackage = new TreeMap<>();
    int waypointClasses = 0;
    for (String c : loaded) {
      byPackage.merge(topPackage(c), 1, Integer::sum);
      if (c.startsWith("io.waypoint.")) {
        waypointClasses++;
      }
    }

    System.out.println(
        "cold query loaded " + loaded.size() + " classes, "
            + waypointClasses + " of them io.waypoint");
    byPackage.entrySet().stream()
        .sorted((a, b) -> b.getValue() - a.getValue())
        .limit(8)
        .forEach(e -> System.out.println("    " + e.getKey() + " " + e.getValue()));

    assertTrue(
        loaded.size() <= TOTAL_CLASS_BUDGET,
        "a cold query now loads " + loaded.size() + " classes, over the budget of "
            + TOTAL_CLASS_BUDGET + ". Something on the open path started pulling in JDK"
            + " machinery -- a lambda, a sealed-type switch, String.format, or a regex are"
            + " the usual culprits. See the class javadoc.");

    assertTrue(
        waypointClasses <= WAYPOINT_CLASS_BUDGET,
        "the read path now loads " + waypointClasses + " Waypoint classes, over the budget of "
            + WAYPOINT_CLASS_BUDGET);
  }

  /**
   * Nothing outside the JDK and Waypoint may be loaded. This is the check that
   * would catch a dependency sneaking onto the runtime classpath, which is the
   * failure mode the zero-dependency rule exists to prevent.
   */
  @Test
  void aColdQueryLoadsNothingButTheJdkAndWaypoint(@TempDir Path tmp) throws Exception {
    Path index = tmp.resolve("purity.wpt");
    try (IndexBuilder b = IndexBuilder.create(index)) {
      b.addText("only", "alpha beta gamma");
    }
    Path log = tmp.resolve("class-load.log");
    List<String> command = List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xlog:class+load=info:file=" + log,
        "-cp", System.getProperty("java.class.path"),
        "io.waypoint.cli.Main", "search", index.toString(), "-k", "5", "alpha");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (InputStream in = p.getInputStream()) {
      in.readAllBytes();
    }
    assertEquals(0, p.waitFor());

    List<String> foreign = new ArrayList<>();
    for (String c : parse(log)) {
      if (!(c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.")
          || c.startsWith("sun.") || c.startsWith("com.sun.") || c.startsWith("io.waypoint.")
          || c.startsWith("[") || c.indexOf('.') < 0)) {
        foreign.add(c);
      }
    }
    assertTrue(foreign.isEmpty(), "a cold query loaded non-JDK, non-Waypoint classes: " + foreign);
  }

  private static List<String> parse(Path log) throws IOException {
    List<String> out = new ArrayList<>();
    try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
      lines.forEach(line -> {
        int marker = line.indexOf("[class,load] ");
        if (marker < 0) {
          return;
        }
        String rest = line.substring(marker + "[class,load] ".length()).trim();
        int space = rest.indexOf(' ');
        out.add(space < 0 ? rest : rest.substring(0, space));
      });
    }
    return out;
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
}
