package io.waypoint.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Builds the Lucene index the benchmark compares against, configured to give
 * Lucene its best case rather than a convenient one.
 *
 * <ul>
 *   <li><b>{@code forceMerge(1)}</b> -- a single segment. The teardown measured
 *       41 segments costing 0.6-1.0 s to first result versus ~330 ms for one;
 *       benchmarking against a fragmented index would be rigging the result.
 *   <li><b>Freqs and norms only, no positions.</b> Waypoint has no positions,
 *       so giving Lucene positions would charge it for a capability the
 *       comparison never uses. Positions were 6.6 MB of a 15.7 MB index and
 *       roughly half the build time.
 *   <li><b>Same analyzer, same token stream</b> ({@link WaypointAnalyzer}).
 *   <li><b>The external id is a stored field</b>, because Waypoint stores its
 *       document keys too and both engines print them.
 *   <li><b>Default {@link BM25Similarity}</b>, k1 = 1.2, b = 0.75, which is
 *       exactly what Waypoint replicates.
 * </ul>
 */
public final class LuceneBuild {

  public static final String FIELD_BODY = "body";
  public static final String FIELD_ID = "id";

  private LuceneBuild() {}

  /** A corpus document: an external id and its raw text. */
  public record Doc(String id, String text) {}

  /** Indexed, tokenised, freqs + norms, no positions, not stored. */
  public static FieldType bodyType() {
    return bodyType(false);
  }

  /**
   * The body field type, optionally with positions.
   *
   * <p>The benchmark index stays on {@code DOCS_AND_FREQS} so both engines
   * store the same thing. The positions variant exists for the phrase
   * differential test, where Lucene has to be storing positions too or there
   * is nothing to compare against.
   */
  public static FieldType bodyType(boolean withPositions) {
    FieldType ft = new FieldType();
    ft.setIndexOptions(
        withPositions
            ? IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
            : IndexOptions.DOCS_AND_FREQS);
    ft.setTokenized(true);
    ft.setOmitNorms(false);
    ft.setStored(false);
    ft.freeze();
    return ft;
  }

  /**
   * Writes a single-segment Lucene index of {@code docs} into {@code dir}.
   *
   * @param compoundFile fold the segment into one .cfs bundle. The harness
   *     measures both settings, because file count is one of the things that
   *     moves open time and Lucene should be given whichever wins.
   * @return nanoseconds spent building, for the build-throughput table
   */
  public static long buildIndex(Path dir, List<Doc> docs, boolean compoundFile, int ramBufferMb)
      throws IOException {
    return buildIndex(dir, docs, compoundFile, ramBufferMb, false);
  }

  /** As above, optionally indexing positions so phrase queries can be compared. */
  public static long buildIndex(
      Path dir, List<Doc> docs, boolean compoundFile, int ramBufferMb, boolean withPositions)
      throws IOException {
    FieldType bodyType = bodyType(withPositions);
    long start = System.nanoTime();
    try (WaypointAnalyzer analyzer = new WaypointAnalyzer();
        Directory d = FSDirectory.open(dir)) {
      IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
      cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
      cfg.setRAMBufferSizeMB(ramBufferMb);
      cfg.setSimilarity(new BM25Similarity());
      cfg.setUseCompoundFile(compoundFile);
      cfg.getMergePolicy().setNoCFSRatio(compoundFile ? 1.0 : 0.0);
      try (IndexWriter w = new IndexWriter(d, cfg)) {
        for (Doc doc : docs) {
          Document ld = new Document();
          ld.add(new StoredField(FIELD_ID, doc.id()));
          ld.add(new Field(FIELD_BODY, doc.text(), bodyType));
          w.addDocument(ld);
        }
        w.forceMerge(1);
        w.commit();
      }
    }
    return System.nanoTime() - start;
  }
}
