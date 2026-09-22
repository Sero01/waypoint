# Benchmark methodology

Everything here is written so that a hostile reader can check it rather than
take it on trust. Where a choice could plausibly have been made to flatter
Waypoint, this document says which way it was made and why.

## The corpus

MS MARCO passage collection, the first 1,000,000 passages of `collection.tsv`.
Freely downloadable, standard, citable, and already in the `id<TAB>text` shape
both engines read, so no conversion step sits between the published corpus and
the benchmark.

```
1,000,000 documents
  416,287 distinct terms
57,513,825 tokens
    57.51 average document length
```

Queries come from the same distribution: `manhattan` (single term),
`manhattan project physics` (disjunction) and `blood pressure medication`
(conjunction). Real words against real text, not terms chosen to suit either
index format.

## What Lucene is given

Lucene is configured for its best case, not its most convenient one.

| | setting | why |
|---|---|---|
| Segments | `forceMerge(1)` | The teardown measured 41 segments costing 0.6–1.0 s to first result against ~330 ms for one. Benchmarking against a fragmented index would be rigging the result. |
| Index options | `DOCS_AND_FREQS` + norms, no positions | Waypoint can store positions, but the benchmark index is built without them on both sides. Charging Lucene for a section neither engine reads during these queries would inflate its open cost for a capability the comparison never uses. Positions were 6.6 MB of a 15.7 MB index and about half the build time. The phrase differential test builds a separate corpus with positions on both engines. |
| Analyzer | Waypoint's own tokenizer, wrapped in a Lucene `Analyzer` | Both engines consume a byte-identical token stream. See below. |
| Similarity | default `BM25Similarity`, k1 = 1.2, b = 0.75 | The similarity Waypoint replicates. |
| Document ids | stored field, retrieved and printed | Waypoint stores and prints its document keys too, so both engines do the work. |
| SIMD | measured both with and without `--add-modules jdk.incubator.vector` | The flag is off in a stock `java -jar` and the teardown measured it worth up to 1.80x on warm query latency. Lucene is credited with whichever setting is faster for the metric in question. |
| Startup tooling | AppCDS and the JDK 24+ AOT cache, both applied to Lucene as well as Waypoint | These are the counter-experiments that could falsify the headline claim, so they are run rather than mentioned. |

## The shared token stream

`WaypointAnalyzer` wraps `io.waypoint.core.Tokenizer` so Lucene indexes and
searches exactly the tokens Waypoint does.

This is the single most important piece of benchmark hygiene in the repository.
The teardown measured analysis at 74% of a naive index build, so two engines
running two different analyzers are mostly comparing analyzers. Worse, differing
token streams would make every score comparison meaningless, because any
divergence could always be blamed on the text pipeline rather than on the index
or the similarity.

`LuceneSearchMain` also calls Waypoint's `Tokenizer.normalizeTerm` on each query
word. That charges Lucene for loading two Waypoint classes — noise against a
2,500-class baseline — and buys certainty that both engines look up identical
terms.

## Cold start

**Why not JMH.** JMH warms up, and warm-up is precisely what is being measured.
The teardown found a Lucene index reopened inside one JVM costs 3–5 ms against
218 ms for the first open, so roughly 98% of what a user waits for is one-time
cost that a warmed benchmark deletes.

Instead: fork N = 20 real JVMs per configuration, after 3 discarded warm-up
runs, and record two numbers.

- **In-process** — `System.nanoTime` from the first statement of `main` to the
  first result printed. Reported by the program itself as `elapsed_ns`.
- **External** — the harness's own wall clock around the whole process,
  including JVM startup. **This is the headline**, because it is what a person
  actually waits for, and because it is the less flattering of the two: JVM
  startup is a floor both engines pay, so including it can only shrink the
  ratio.

Reported as median with p5/p95.

### The floor row

Every report includes a floor: each engine's own main class invoked with no
arguments, so it prints its usage and exits without opening anything. JVM
startup, that classpath, that main class, nothing else.

Without it the external numbers are not interpretable — roughly 90 ms of every
measurement is the JVM booting, which drags every ratio towards 1.0. Measuring
each engine's floor on its own classpath, rather than picking one floor and
applying it to both, removes the objection that the two classpaths cost
different amounts to scan.

The floor is also the noise meter. It does the least work of anything measured,
so a wide spread in the floor's own samples means the machine was busy and every
absolute number on the page is inflated. The harness detects this and prints a
warning into the report.

### Round-robin ordering

Configurations are measured round-robin — one run of each, repeated — rather
than one configuration to completion before starting the next.

This is not cosmetic. An early run of this suite measured block by block on a
laptop that became busy partway through; the floor tripled between blocks and
Lucene's numbers came out roughly four times too slow. Cycling spreads any drift
across all configurations, so it inflates absolute numbers without distorting
ratios.

### The agreement check

Before timing anything, the harness runs both engines once and requires that
they return the same documents in the same order with scores agreeing to 1e-6
relative. A benchmark where the two sides answer differently is timing two
different amounts of work.

Two deliberate exceptions:

- **Match counts.** Lucene stops counting at `TOTAL_HITS_THRESHOLD = 1000` and
  reports a lower bound. Waypoint reports the true count for terms,
  conjunctions and phrases, because a skipped postings block's header says how
  many postings it held, so pruning them does not lose the count. Disjunctions
  are the exception: MaxScore stops enumerating clauses that can no longer place
  a document in the top k, so a document matching only those is never visited
  and the count becomes a lower bound. `Hits.totalHitsExact()` says which case
  applies, and `Index.count(Query)` returns the exact number for any shape.

  The benchmark harness uses `Index.count` rather than `search().totalHits()`
  when it checks that both engines are answering the same question, so a pruned
  disjunction cannot make the two sides look like they agree when they do not.
- **The last ulp.** Float addition is not associative. For a conjunction Lucene
  sums clause contributions in the order the clauses were written, while
  Waypoint sums them rarest-term-first, so the two occasionally land one ulp
  apart. Demanding byte equality would reject a correct result.

## Correctness: Lucene as an oracle

`DifferentialTest` generates Zipfian corpora and random queries, indexes them in
both engines from the same token stream, and asserts agreement: identical
document frequencies, identical `sumTotalTermFreq`, identical result counts,
identical documents in identical order, and scores within 1e-6 relative.

Measured over 15,941 compared scores: **99.2% bit-identical to Lucene, maximum
relative difference 1.2e-7** — one ulp, from the summation-order effect above.

## Machine disclosure

Every generated report in `results/` records the JDK build, the OS, the
processor count, the exact command line for every configuration, and any JVM
diagnostics that appeared. Nothing is claimed finer than the measured spread.

## Reproducing

```sh
export JAVA_HOME=/path/to/jdk-25
export CORPUS=/path/to/collection-1m.tsv
scripts/bench.sh
```

Works in bash, including Git Bash on Windows, which is where the committed
numbers were produced.
