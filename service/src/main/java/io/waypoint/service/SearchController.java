package io.waypoint.service;

import io.waypoint.core.Hits;
import io.waypoint.core.Index;
import io.waypoint.core.Query;
import io.waypoint.core.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP surface: {@code GET /search}, {@code GET /stats}, {@code GET /health}.
 *
 * <p>Errors are mapped deliberately rather than left to default behaviour. A
 * query the engine cannot parse is the caller's problem (400). An index that
 * has not been built is the operator's problem and is retryable (503, naming
 * the file). Nothing returns a stack trace.
 */
@RestController
public class SearchController {

  private final IndexHolder holder;
  private final int maxK;

  public SearchController(IndexHolder holder, WaypointProperties properties) {
    this.holder = holder;
    this.maxK = properties.maxKOrDefault();
  }

  /** One result row. Scores are BM25 and match Lucene's to the last ulp. */
  public record Hit(String id, float score) {}

  /**
   * {@code totalHitsExact} is false when MaxScore pruning stopped a
   * disjunction from enumerating every match, exactly as Lucene's
   * {@code TotalHits.Relation} reports it. Callers who need the true number
   * regardless of cost have {@code Index.count}.
   */
  public record SearchResponse(
      String query,
      String operator,
      long totalHits,
      boolean totalHitsExact,
      int returned,
      List<Hit> hits) {}

  public record ErrorResponse(String error) {}

  @GetMapping("/search")
  public ResponseEntity<?> search(
      @RequestParam("q") String q,
      @RequestParam(name = "k", defaultValue = "10") int k,
      @RequestParam(name = "op", defaultValue = "or") String op) {

    Index index = holder.index();
    if (index == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new ErrorResponse(holder.failure()));
    }
    if (k <= 0 || k > maxK) {
      return ResponseEntity.badRequest()
          .body(new ErrorResponse("k must be between 1 and " + maxK + ", got " + k));
    }
    boolean and = false;
    boolean phrase = false;
    if (op.equalsIgnoreCase("or")) {
      and = false;
    } else if (op.equalsIgnoreCase("and")) {
      and = true;
    } else if (op.equalsIgnoreCase("phrase")) {
      phrase = true;
    } else {
      return ResponseEntity.badRequest()
          .body(new ErrorResponse("op must be 'and', 'or' or 'phrase', got '" + op + "'"));
    }
    if (phrase && !index.hasPositions()) {
      // The operator's problem, not the caller's: the index was built without
      // positions, and no query can fix that from this side.
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new ErrorResponse(
              "this index was built without positions, so phrase search is unavailable"));
    }

    // The service runs the same tokenizer the index was built with; anything
    // else would silently look up terms that cannot exist.
    List<String> terms = Tokenizer.tokenize(q);
    if (terms.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(new ErrorResponse("query contains no indexable terms"));
    }

    Query query;
    if (phrase) {
      query = Query.phrase(terms.toArray(new String[0]));
    } else if (terms.size() == 1) {
      query = Query.term(terms.get(0));
    } else {
      Query[] clauses = new Query[terms.size()];
      for (int i = 0; i < terms.size(); i++) {
        clauses[i] = Query.term(terms.get(i));
      }
      query = and ? Query.and(clauses) : Query.or(clauses);
    }

    Hits hits = index.search(query, k);
    List<Hit> rows = new ArrayList<>(hits.size());
    for (int i = 0; i < hits.size(); i++) {
      rows.add(new Hit(hits.key(i), hits.score(i)));
    }
    String operator = phrase ? "phrase" : and ? "and" : "or";
    return ResponseEntity.ok(
        new SearchResponse(
            q, operator, hits.totalHits(), hits.totalHitsExact(), rows.size(), rows));
  }

  public record Stats(
      int documents, int terms, long tokens, float averageDocumentLength, long fileBytes) {}

  @GetMapping("/stats")
  public ResponseEntity<?> stats() {
    Index index = holder.index();
    if (index == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(new ErrorResponse(holder.failure()));
    }
    return ResponseEntity.ok(new Stats(
        index.docCount(),
        index.termCount(),
        index.totalTokens(),
        index.averageDocumentLength(),
        index.sizeInBytes()));
  }

  @GetMapping("/health")
  public ResponseEntity<?> health() {
    return holder.index() == null
        ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse(holder.failure()))
        : ResponseEntity.ok(new ErrorResponse("ok"));
  }
}
