package io.waypoint.core;

/**
 * The v1 query language: a term, a conjunction, or a disjunction. Nothing else.
 *
 * <p><b>Why this is not a record hierarchy with a pattern-matching switch.</b>
 * It would read better. It would also cost the thing this project exists to
 * measure. A {@code switch} over a sealed type compiles to an
 * {@code invokedynamic} against {@code java.lang.runtime.SwitchBootstraps},
 * and record {@code equals}/{@code hashCode}/{@code toString} compile to an
 * {@code invokedynamic} against {@code ObjectMethods}; the first time either
 * call site links it drags in {@code MethodHandles}, the {@code LambdaForm}
 * machinery and its spun hidden classes. On a warm JVM that is free. On the
 * first query of a fresh JVM -- the only number this engine claims to win --
 * it is dozens of classes and several milliseconds.
 *
 * <p>So: plain final classes, an int tag, and a {@code switch} on the int.
 * The same rule bans lambdas, streams, regexes and {@code String.format} from
 * the read path. This is not premature optimisation; it is the thesis.
 */
public sealed interface Query permits Query.Term, Query.And, Query.Or, Query.Phrase {

  int KIND_TERM = 0;
  int KIND_AND = 1;
  int KIND_OR = 2;
  int KIND_PHRASE = 3;

  /** Discriminator, so query execution can switch on an int rather than a type. */
  int kind();

  /** Matches documents containing {@code text}, scored by BM25. */
  static Term term(String text) {
    return new Term(text);
  }

  /** Conjunction. Scores are the sum of the clause scores, as Lucene's BooleanQuery does. */
  static And and(Query... clauses) {
    return new And(clauses);
  }

  /** Disjunction. Scores are the sum of the matching clause scores. */
  static Or or(Query... clauses) {
    return new Or(clauses);
  }

  /**
   * An exact phrase: these terms, adjacent, in this order.
   *
   * <p>Requires an index built with positions. Scoring follows Lucene's
   * {@code PhraseQuery}, whose weight is the sum of the clause idfs and whose
   * frequency is the number of times the whole phrase occurs in the document.
   */
  static Phrase phrase(String... terms) {
    return new Phrase(terms);
  }

  /** A single term. */
  final class Term implements Query {
    final String text;

    Term(String text) {
      if (text == null || text.isEmpty()) {
        throw new IllegalArgumentException("term must be non-empty");
      }
      this.text = text;
    }

    public String text() {
      return text;
    }

    @Override
    public int kind() {
      return KIND_TERM;
    }
  }

  /** Conjunction over term clauses. */
  final class And implements Query {
    final Query[] clauses;

    And(Query[] clauses) {
      this.clauses = checkClauses(clauses);
    }

    public int size() {
      return clauses.length;
    }

    @Override
    public int kind() {
      return KIND_AND;
    }
  }

  /** Disjunction over term clauses. */
  final class Or implements Query {
    final Query[] clauses;

    Or(Query[] clauses) {
      this.clauses = checkClauses(clauses);
    }

    public int size() {
      return clauses.length;
    }

    @Override
    public int kind() {
      return KIND_OR;
    }
  }

  /** An exact phrase over adjacent terms. */
  final class Phrase implements Query {
    final String[] terms;

    Phrase(String[] terms) {
      if (terms == null || terms.length == 0) {
        throw new IllegalArgumentException("phrase needs at least one term");
      }
      String[] copy = new String[terms.length];
      for (int i = 0; i < terms.length; i++) {
        if (terms[i] == null || terms[i].isEmpty()) {
          throw new IllegalArgumentException("empty phrase term at index " + i);
        }
        copy[i] = terms[i];
      }
      this.terms = copy;
    }

    public int size() {
      return terms.length;
    }

    public String term(int i) {
      return terms[i];
    }

    @Override
    public int kind() {
      return KIND_PHRASE;
    }
  }

  private static Query[] checkClauses(Query[] clauses) {
    if (clauses == null || clauses.length == 0) {
      throw new IllegalArgumentException("boolean query needs at least one clause");
    }
    for (int i = 0; i < clauses.length; i++) {
      if (clauses[i] == null) {
        throw new IllegalArgumentException("null clause at index " + i);
      }
      if (clauses[i].kind() != KIND_TERM) {
        // v1 is flat on purpose: nesting buys nothing for the benchmark and
        // costs a recursive scorer tree, which is exactly the object graph
        // this engine is trying not to build.
        throw new IllegalArgumentException("v1 supports only flat boolean queries over terms");
      }
    }
    return clauses;
  }
}
