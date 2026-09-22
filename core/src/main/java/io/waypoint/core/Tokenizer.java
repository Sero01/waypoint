package io.waypoint.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The bundled analyzer: lowercase, split on anything that is not a letter or a
 * digit. No stemming, no stop words, no regex.
 *
 * <p>It is deliberately trivial, because tokenisation is the one part of the
 * pipeline this project must <em>not</em> compete on. The Lucene teardown
 * measured analysis at 74% of a naive index build, which means an
 * "index build throughput" benchmark against a different analyzer is mostly a
 * tokenizer benchmark wearing a costume. {@code bench} wraps this exact class
 * in a Lucene {@code Analyzer}, so both engines consume a byte-identical token
 * stream and any divergence in results is the index or the scoring -- never the
 * text pipeline.
 *
 * <p>{@code MAX_TOKEN_LENGTH} mirrors Lucene's {@code StandardTokenizer}
 * default of 255: an over-long run is split rather than dropped.
 */
public final class Tokenizer {

  private Tokenizer() {}

  public static final int MAX_TOKEN_LENGTH = 255;

  /** Tokenises {@code text} into lowercased alphanumeric runs, in order. */
  public static List<String> tokenize(String text) {
    ArrayList<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    StringBuilder sb = new StringBuilder(32);
    int n = text.length();
    int i = 0;
    while (i < n) {
      int cp = text.codePointAt(i);
      int cc = Character.charCount(cp);
      i += cc;
      if (Character.isLetter(cp) || Character.isDigit(cp)) {
        sb.appendCodePoint(Character.toLowerCase(cp));
        if (sb.length() >= MAX_TOKEN_LENGTH) {
          out.add(sb.toString());
          sb.setLength(0);
        }
      } else if (sb.length() > 0) {
        out.add(sb.toString());
        sb.setLength(0);
      }
    }
    if (sb.length() > 0) {
      out.add(sb.toString());
    }
    return out;
  }

  /**
   * Normalises a single query term the way {@link #tokenize} would.
   * Returns null if the term contains nothing indexable.
   */
  public static String normalizeTerm(String term) {
    List<String> t = tokenize(term);
    return t.isEmpty() ? null : t.get(0);
  }
}
