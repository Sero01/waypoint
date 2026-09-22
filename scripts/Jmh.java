import java.nio.file.*;

/** Renders JMH's JSON output as the markdown table the README needs. */
public class Jmh {
  public static void main(String[] a) throws Exception {
    String s = Files.readString(Path.of(a[0]));
    System.out.printf("%-22s %10s %10s %10s %10s %s%n",
        "benchmark", "p50 us", "p95 us", "p99 us", "samples", "simd");
    for (String p : s.split("\"benchmark\" :")) {
      int i = p.indexOf('"');
      if (i < 0) continue;
      int j = p.indexOf('"', i + 1);
      if (j < 0) continue;
      String name = p.substring(i + 1, j);
      if (!name.contains("QueryLatency")) continue;
      System.out.printf("%-22s %10s %10s %10s %10s %s%n",
          name.substring(name.lastIndexOf('.') + 1),
          grab(p, "\"50.0\" : "), grab(p, "\"95.0\" : "), grab(p, "\"99.0\" : "),
          grab(p, "\"sampleCount\" : "),
          p.contains("add-modules=jdk.incubator.vector") ? "yes" : "NO");
    }
  }

  static String grab(String p, String key) {
    int i = p.indexOf(key);
    if (i < 0) return "-";
    int j = i + key.length();
    int k = j;
    while (k < p.length() && "0123456789.eE-".indexOf(p.charAt(k)) >= 0) k++;
    try { return String.format("%.1f", Double.parseDouble(p.substring(j, k))); }
    catch (Exception e) { return p.substring(j, k); }
  }
}
