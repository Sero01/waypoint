package io.waypoint.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the zero-dependency rule that the central claim rests on.
 *
 * <p>The claim is "Waypoint loads tens of classes where Lucene loads two
 * thousand". One transitive logging facade, one {@code ServiceLoader} lookup,
 * one annotation-driven anything, and that claim quietly stops being true --
 * and it stops being true in a way that a passing test suite would never
 * notice, because everything would still work, just slower to start.
 *
 * <p>So this test reads the compiled bytecode of every class in {@code core}
 * with the JDK's own class-file API and walks the constant pool, asserting that
 * every type referenced is either the JDK or Waypoint itself. It is a stricter
 * check than reading the POM, because it also catches a dependency that arrives
 * some other way, and it fails at build time rather than at benchmark time.
 */
class DependencyBudgetTest {

  /** Everything {@code core} is permitted to reference. */
  private static final List<String> ALLOWED_PREFIXES =
      List.of("java/", "javax/", "jdk/", "io/waypoint/core/");

  @Test
  void coreReferencesNothingOutsideTheJdkAndItself() throws IOException {
    Path classes = Path.of("target", "classes");
    assertTrue(Files.isDirectory(classes), "compile core before running this test: " + classes);

    TreeMap<String, TreeSet<String>> offenders = new TreeMap<>();
    int scanned = 0;

    try (Stream<Path> files = Files.walk(classes)) {
      List<Path> classFiles =
          files.filter(p -> p.toString().endsWith(".class")).sorted().toList();
      for (Path p : classFiles) {
        scanned++;
        ClassModel model = ClassFile.of().parse(Files.readAllBytes(p));
        String owner = model.thisClass().asInternalName();
        for (String referenced : referencedTypes(model)) {
          if (!allowed(referenced)) {
            offenders.computeIfAbsent(owner, unused -> new TreeSet<>()).add(referenced);
          }
        }
      }
    }

    assertTrue(scanned > 0, "no compiled classes found under " + classes.toAbsolutePath());
    if (!offenders.isEmpty()) {
      StringBuilder sb = new StringBuilder("core must have no dependencies, but found:\n");
      offenders.forEach((owner, refs) -> sb.append("  ").append(owner)
          .append(" -> ").append(refs).append('\n'));
      fail(sb.toString());
    }
  }

  /**
   * A belt-and-braces check on the POM itself, so that an unused-but-declared
   * dependency (which the bytecode scan cannot see) is still caught.
   */
  @Test
  void corePomDeclaresOnlyTestScopedDependencies() throws IOException {
    Path pom = Path.of("pom.xml");
    assertTrue(Files.exists(pom), "expected to run with core as the working directory");
    String xml = Files.readString(pom, StandardCharsets.UTF_8);

    int from = xml.indexOf("<dependencies>");
    int to = xml.indexOf("</dependencies>");
    if (from < 0 || to < 0) {
      return; // no dependencies block at all is the strongest possible pass
    }
    String block = xml.substring(from, to);
    int declared = count(block, "<dependency>");
    int testScoped = count(block, "<scope>test</scope>");
    assertTrue(
        declared == testScoped,
        "core declares " + declared + " dependencies but only " + testScoped
            + " are test-scoped; the zero-dependency rule is load-bearing, see the class javadoc");
  }

  private static int count(String haystack, String needle) {
    int n = 0;
    int i = haystack.indexOf(needle);
    while (i >= 0) {
      n++;
      i = haystack.indexOf(needle, i + needle.length());
    }
    return n;
  }

  private static boolean allowed(String internalName) {
    for (String prefix : ALLOWED_PREFIXES) {
      if (internalName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Class entries plus every object type named in a field or method descriptor. */
  private static Set<String> referencedTypes(ClassModel model) {
    TreeSet<String> out = new TreeSet<>();
    for (PoolEntry entry : model.constantPool()) {
      if (entry instanceof ClassEntry ce) {
        addType(out, ce.asInternalName());
      } else if (entry instanceof NameAndTypeEntry nt) {
        addDescriptorTypes(out, nt.type().stringValue());
      }
    }
    return out;
  }

  private static void addType(Set<String> out, String internalName) {
    String name = internalName;
    while (name.startsWith("[")) {
      name = name.substring(1);
    }
    if (name.startsWith("L") && name.endsWith(";")) {
      name = name.substring(1, name.length() - 1);
    }
    if (name.length() <= 1) {
      return; // a primitive descriptor such as I or [J
    }
    out.add(name);
  }

  private static void addDescriptorTypes(Set<String> out, String descriptor) {
    int i = 0;
    while (i < descriptor.length()) {
      char c = descriptor.charAt(i);
      if (c == 'L') {
        int end = descriptor.indexOf(';', i);
        if (end < 0) {
          return;
        }
        out.add(descriptor.substring(i + 1, end));
        i = end + 1;
      } else {
        i++;
      }
    }
  }

  /** A readable inventory, so the budget is visible rather than merely enforced. */
  @Test
  void reportsTheSizeOfCore() throws IOException {
    Path classes = Path.of("target", "classes");
    List<String> names = new ArrayList<>();
    try (Stream<Path> files = Files.walk(classes)) {
      files.filter(p -> p.toString().endsWith(".class"))
          .forEach(p -> names.add(classes.relativize(p).toString()));
    }
    names.sort(null);
    System.out.println("core compiles to " + names.size() + " class files: " + names);
    assertTrue(
        names.size() <= 40,
        "core has grown to " + names.size() + " class files; the design budget is ~20 source"
            + " classes and every one of them is a class the read path may have to load");
  }
}
