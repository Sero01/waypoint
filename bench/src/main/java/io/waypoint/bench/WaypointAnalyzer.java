package io.waypoint.bench;

import java.io.IOException;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Waypoint's own tokenizer, wrapped so Lucene can use it.
 *
 * <p>This class is the single most important piece of benchmark hygiene in the
 * repository. The Lucene teardown measured analysis at 74% of a naive index
 * build, which means two engines running two different analyzers are mostly
 * comparing analyzers. Worse, differing token streams would make every score
 * comparison meaningless: a divergence could always be blamed on the text
 * pipeline rather than on the index or the similarity.
 *
 * <p>With this, both engines consume a byte-identical token stream, so any
 * difference in results is the index format or the scoring -- which is what the
 * differential test is trying to measure.
 */
public final class WaypointAnalyzer extends Analyzer {

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    return new TokenStreamComponents(new WaypointLuceneTokenizer());
  }

  /** Emits exactly what {@link io.waypoint.core.Tokenizer#tokenize} emits. */
  private static final class WaypointLuceneTokenizer extends Tokenizer {

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private List<String> tokens;
    private int next;

    @Override
    public boolean incrementToken() throws IOException {
      if (tokens == null) {
        StringBuilder sb = new StringBuilder(1024);
        char[] buf = new char[4096];
        int n;
        while ((n = input.read(buf)) != -1) {
          sb.append(buf, 0, n);
        }
        tokens = io.waypoint.core.Tokenizer.tokenize(sb.toString());
        next = 0;
      }
      clearAttributes();
      if (next >= tokens.size()) {
        return false;
      }
      termAtt.setEmpty().append(tokens.get(next++));
      return true;
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      tokens = null;
      next = 0;
    }

    @Override
    public void close() throws IOException {
      super.close();
      tokens = null;
    }
  }
}
