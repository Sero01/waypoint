# Waypoint

A static, read-only, single-file inverted index that answers its first query in
a fresh JVM 3.3x faster than Apache Lucene, matches Lucene's ranking to the last
ulp, and is 2 to 3x behind it on warm query latency.

**Read this paragraph before the numbers.** The index is built once and frozen.
There are no deletes, no updates, no incremental or near-real-time indexing, one
segment, one file, one field, no faceting, no highlighting, no sorting by
anything but score, no sharding, no vector search. Lucene pays for every one of
those on every open. This project measures that bill. It is not a replacement
for paying it, and [§6](#6-use-lucene) says so in those words.

Terms, conjunctions, disjunctions and exact phrases are supported. Phrases need
an index built with `--positions`, which is off by default.

The constraint *is* the thesis. Waypoint is fast to open because it does less.

Ranking is not a place it does less: over 20,266 scores compared against Lucene
on random corpora, covering terms, conjunctions, disjunctions and phrases,
**98.0% are bit-identical and the maximum relative difference is 2.3e-7**.

---

## 1. What was measured

1,000,000 MS MARCO passages — 416,287 terms, 57.5M tokens — indexed by both
engines from a byte-identical token stream. Time from process start to the first
result printed on screen, 12 forked JVMs per configuration after 3 discarded
warm-up runs, median reported.

| | external wall clock, p50 | vs Lucene |
|---|---:|---:|
| **waypoint** | **116.8 ms** | — |
| lucene, stock `java -cp` | 379.9 ms | **3.3x slower** |
| lucene, best configuration | 191.8 ms | **2.0x slower** |
| waypoint, best configuration | 96.3 ms | — |

Three query shapes, all three reports in [`results/`](results/):

| query | stock `java -cp` | both engines tuned |
|---|---:|---:|
| `manhattan` | 3.3x | 2.0x |
| `manhattan project physics` (OR) | 3.3x | 2.1x |
| `blood pressure medication` (AND) | 3.3x | 2.0x |

Lucene was given every advantage: a `forceMerge(1)` single-segment index,
freqs-and-norms-only index options matching Waypoint's, the same analyzer and
the same token stream, the same `k`, its document ids as a stored field because
Waypoint stores and prints keys too, and both settings of `--add-modules
jdk.incubator.vector`, credited with whichever was faster. Full disclosure of
every choice is in [`docs/methodology.md`](docs/methodology.md).

### The pre-registered kill criterion was not met

The [design document](docs/design.md)
committed, before any of this was built, to withdrawing the headline claim if —
**with the AOT cache enabled for both engines** — the external median were not
at least **3x** better.

Measured: **2.0x – 2.1x. The 3x gate is not met, and the 3x claim is
withdrawn.**

That is the honest result and it is stated here rather than in a footnote. What
survives it: on a stock JVM, which is what almost everyone actually runs,
Waypoint is 3.3x faster to first result; with both engines tuned as hard as
JDK 25 allows, it is still about 2x faster; and on the work actually
attributable to the engine rather than to the JVM, it is 10.1–12.3x faster.
Those are real wins. They are not the 3x-under-AOT win the design document set
out to claim.

The project ships as an engine rather than as a study because a 2x
worst-case win, held while matching Lucene's ranking to the last ulp, is worth
publishing. Readers who disagree have the criterion, the measurement and the
harness in front of them.

### Why the external number understates the difference

Roughly 90 ms of any JVM cold start is the JVM: a bare program that prints one
line and exits loads **440 classes** before reaching `main`. That floor is paid
by both engines and controlled by neither, so it drags every external ratio
towards 1.0.

Every report therefore includes a floor row — each engine's own main class
invoked with no arguments — measured on that engine's own classpath. Subtracting
it isolates the engine:

| | in-process, p50 | above its own floor |
|---|---:|---:|
| waypoint | 29.1 ms | 26.6 ms |
| lucene (scalar) | 271.9 ms | 262.7 ms |
| waypoint + AOT cache | 13.8 ms | 6.1 ms |
| lucene (scalar) + AOT cache | 93.5 ms | 74.7 ms |

**On engine work, tuned against tuned: 12.3x** for the single-term query, 11.8x
and 10.1x for the disjunction and conjunction.

### About the absolute milliseconds on this page

This run was made on an idle machine: the floor row, a program that prints one
line and exits, reads **90.2 ms**, which is what that floor costs when nothing
else is competing. An earlier published run was made with 1.6 GB of 7.9 GB free
and its floor read 291 ms, inflating every absolute number on the page by
roughly 3x while leaving the ratios intact.

Configurations are measured round-robin rather than one at a time precisely so
that drift lands on all of them alike. **Read the ratios first.**
`scripts/bench.sh` regenerates everything.

---

## 2. Where the difference comes from

Classes loaded by one cold query:

| | total | above a bare JVM | of which the engine's own |
|---|---:|---:|---:|
| bare JVM | 440 | — | — |
| **waypoint** | **1,000** | **560** | 12 |
| lucene + SIMD | 2,512 | 2,072 | 604 `org.apache.lucene` |

Opening a Waypoint index is one `FileChannel.map`, one 128-byte header read and
a bounds check. No codec is resolved through `ServiceLoader`, no per-format
reader stack is constructed, no term index is decoded into objects, and every
section of the file is read lazily off the mapped segment when a query first
needs it.

That is why adding skip data, block-max impacts and positions to the format cost
this table nothing: they all live inside sections `open` never touches. The
pruning was originally left out for simplicity, not because it was expensive to
open. Undoing that turned out to be much cheaper than the README used to imply.

Three things enforce that this stays true:

- **`core` has no dependencies at all** — not a logging facade, not a
  collections library. `DependencyBudgetTest` reads the compiled bytecode of
  every class in `core` with the JDK class-file API and fails the build if any
  referenced type is outside `java.*` and `io.waypoint.*`.
- **`ClassBudgetTest` forks a JVM** with `-Xlog:class+load=info`, runs one cold
  query through the CLI, and fails if the count exceeds a committed budget. It
  converts the central claim from a sentence into a build failure.
- **The read path avoids anything that spins method handles.** No lambdas, no
  streams, no regex, no `String.format`, and — deliberately — no
  pattern-matching `switch` over the sealed `Query` type, because that compiles
  to an `invokedynamic` against `SwitchBootstraps` and drags in the
  `LambdaForm` machinery on first use. `Query` carries an int tag instead. The
  code is worse to read and that is the trade.

### What Lucene does that Waypoint does not

Every line below is in Lucene 10.5.1 (`releases/lucene/10.5.1`). This is the
teardown the design was built from; the full notes are in
[`docs/teardown/`](docs/teardown/).

| Lucene | Waypoint |
|---|---|
| `Lucene104PostingsReader.java:138-140` — `checkIndexHeader` and `retrieveChecksum` on every file opened | magic and version only; the CRC32 is checked by `waypoint verify`, on demand |
| `IndexSearcher.java:106` — `TOTAL_HITS_THRESHOLD = 1000`, so counting stops and a lower bound is returned | exact for terms, conjunctions and phrases, because a skipped block's header says how many postings it held. A pruned disjunction returns a lower bound and says so; `Index.count` recovers the exact number on demand |
| `MaxScoreBulkScorer.java:26-38` — windowed block-max MaxScore, essential / non-essential clause partitioning | block-max pruning on terms, MaxScore on disjunctions, skip data on conjunctions. Simpler and looser than Lucene's, and still 2.4x behind it on the common-term query |
| `Weight.java:252-294` — a live-docs `Bits` check threaded through every scoring loop | one immutable segment, no deletes, so no check exists to make |
| `VectorizationProvider.java:163-168` — SIMD postings decode behind `jdk.incubator.vector`, off by default | scalar varints; 207 fewer classes and, on cold start, faster |
| `Similarity.java:153-162` — `computeNorm` → `SmallFloat.intToByte4(length)` | replicated exactly, asserted against a committed 256-value golden table |
| `BM25Similarity.java:116,183,231` — `LENGTH_TABLE`, the reciprocal cache, and `weight - weight/(1 + freq*normInverse)` | replicated exactly, including the algebraic form |

The first four rows are Lucene doing work Waypoint has defined away. The last
two are Lucene doing work Waypoint copies verbatim, because that is what makes
the rankings match.

---

## 3. The counter-experiment

The obvious objection is that this is a class-loading result, not an engine
result, and that AppCDS or the JDK 24+ Leyden AOT cache erases it. So both were
run, on **both** engines, including their training runs, with every flag
committed.

They do not erase it. They halve it.

| | stock | + AppCDS | + AOT cache |
|---|---:|---:|---:|
| waypoint | 116.8 ms | 101.6 ms | **96.3 ms** |
| lucene (scalar) | 379.9 ms | 226.0 ms | **191.8 ms** |
| lucene + SIMD | 448.2 ms | 263.2 ms | 302.7 ms |
| **ratio** | **3.3x** | 2.2x | **2.0x** |

Lucene gains far more from both than Waypoint does, which is exactly what you
would expect: it has ten times as much to cache. That is the finding, it is the
reason the 3x gate fails, and running the experiment that could falsify the
claim is the difference between a measurement and a blog post.

Two things fell out of it:

- **Lucene's SIMD flag makes cold start worse.** `--add-modules
  jdk.incubator.vector` is worth up to 1.80x on warm query latency and costs 207
  extra classes; it loses on time-to-first-result in every configuration
  measured. Lucene's own best cold-start setup has SIMD **off**.
- **AppCDS on JDK 25 cannot fully record `--add-modules`.** Replaying a Lucene +
  SIMD archive logs `Mismatched values for property jdk.module.addmods` and
  disables optimised module handling. That works against Lucene, so it is
  printed into every report rather than suppressed.

Details in [`docs/findings.md`](docs/findings.md).

---

## 4. Where Lucene wins

Not a footnote. Warm query latency, JMH, same 1M passages, **Lucene with its
SIMD postings decoder enabled**, both engines returning identical top-10
documents. Full table in [`results/latency.md`](results/latency.md).

| query shape | matches | waypoint p50 | lucene p50 | |
|---|---:|---:|---:|---|
| conjunction `blood pressure medication` | 276 | 1,144.8 µs | **401.2 µs** | **lucene 2.9x** |
| conjunction `the AND manhattan` | 549 | 845.8 µs | **333.3 µs** | **lucene 2.5x** |
| common term `the` | 866,624 | 1,959.9 µs | **901.1 µs** | **lucene 2.2x** |
| disjunction `manhattan project physics` | 8,590 | **408.6 µs** | 515.1 µs | waypoint 1.3x |
| rare term `manhattan` | 555 | **18.6 µs** | 26.7 µs | waypoint 1.4x |
| reopen the index, warm JVM | — | **369.2 µs** | 4,599.8 µs | waypoint 12.5x |

**The common-term row used to be a rout at 25.6x and is now 2.2x.** Each
postings block carries the pareto frontier of the `(freq, norm)` pairs it
actually contains, so once the heap is full a block whose best possible document
still loses is skipped without decoding a posting. Same mechanism as Lucene's
`MaxScoreBulkScorer`, implemented more simply and with a looser bound. On the
same machine and corpus that query went from 27,525 µs to 1,959.9 µs, a **14.0x
improvement**.

Getting there was not free of dead ends. The first version bounded each block by
the corner of its bounding box, the block's largest frequency paired with its
smallest norm, which is correct and pruned **nothing** — for a term whose idf is
0.14 that corner describes a document that does not exist and sits above the
tenth-best score anyway. [`docs/findings.md`](docs/findings.md) §7 has the
measurement.

**Conjunctions are now the worst shape, and the cause is nameable.**
`Cursor.advance` skips whole blocks, but to find the block holding a target it
walks every block header from where it is. A term with 866,624 postings is 6,771
block headers scattered across the file. Lucene's two-level skip data jumps
instead of walking. A sparse skip table over blocks is the obvious next change
and is not implemented.

| | waypoint | lucene | |
|---|---:|---:|---|
| index size | 85.1 MB | 70.6 MB | **1.21x larger** |
| match counting | exact except pruned disjunctions | stops at 1,000 | mixed |

**Index size got worse, from 1.13x to 1.21x.** Skip data and impact frontiers
are bytes, and Waypoint still has no bit-packing and no two-level skip lists. It
stores delta varints with the frequency folded into the low bit of the document
gap, front-coded terms in blocks of 64, and now a header per 128 postings. That
is 6.3% more file than before the pruning work and 21% more than Lucene.
Positions, when enabled, roughly double it again.

**Match counting.** Lucene stops at `TOTAL_HITS_THRESHOLD = 1000` and returns a
lower bound. Waypoint stays exact for terms, conjunctions and phrases, because a
skipped block's header says how many postings it held, so they are counted
without being read. Disjunctions are the exception: MaxScore stops enumerating
clauses that can no longer place a document in the top k, so a document matching
only those is never seen. `Hits.totalHitsExact()` reports which case you are in,
and `Index.count(Query)` returns the true number for any query shape by merging
the postings with no heap and no BM25 at all.

**Build throughput** is not claimed either way. The teardown measured
tokenisation at 74% of a naive index build, so with both engines running the
*same* tokenizer most of both numbers is identical work done twice: 12.3 s
against 15.6 s, which is context, not a result.

---

## 5. Correctness: Lucene as the oracle

The strongest tool available when building something that already has a mature
reference implementation is to assert *agreement* rather than hand-computed
expectations.

`DifferentialTest` generates Zipfian corpora and random queries, indexes them in
both engines from the same token stream, and asserts identical document
frequencies, identical `sumTotalTermFreq`, identical result counts, identical
documents in identical order, and scores within 1e-6 relative — including a
corpus whose document lengths sweep the entire one-byte norm quantisation range.

> compared 20,266 scores against Lucene, 19,856 bit-identical (98.0%),
> max relative difference 2.333690645173192E-7

Getting there meant replicating Lucene's *arithmetic*, not just BM25's formula:
the byte-quantised document length (`SmallFloat.intToByte4`, asserted against a
committed golden table for all 256 values), the 256-entry reciprocal cache, and
the `weight - weight/(1 + freq*normInverse)` rewrite Lucene uses to stay
monotonic without promoting to double.

Phrases are held to the same standard against Lucene's `PhraseQuery`, on a
corpus indexed with positions on both sides, with queries drawn from bigrams and
trigrams that actually occur so most of them match something. Phrase scoring is
the one shape that is not a sum of per-term scores: Lucene weights the whole
phrase with the **sum** of the clause idfs and feeds BM25 the number of phrase
occurrences as the frequency. That is easy to get subtly wrong and impossible to
notice without an oracle.

Two guarantees are load-bearing here and worth stating. Pruning is **exact**,
not approximate: `TopK` admits only on a strictly greater score, so a block
bounded at or below the current worst hit cannot contain an admissible document,
and the top-k is bit-identical to what the unpruned scan returns. That is why
adding block-max pruning and MaxScore did not move the agreement numbers at all.
And `Index.count` is checked against Lucene's exact count on every comparison,
so the counting guarantee stays under test rather than being dropped along with
the pruning.

The remaining 2.0% cannot be fixed and a test demanding byte equality would be a
broken test: float addition is not associative, and for a multi-clause query
Lucene sums clauses in its scorer's order while Waypoint sums them
rarest-term-first.

---

## 6. Use Lucene

For essentially every production workload, use Lucene.

It is still faster at conjunctions and common terms, smaller on disk, and it
handles deletes, updates, near-real-time indexing, multiple fields, faceting,
sorting and highlighting, none of which exist here. It is maintained by people
who have been doing this for twenty years. Waypoint wins on one shape of
workload — a small, static, build-once index queried from short-lived processes,
where the JVM starts, one query runs, and the process exits.

What changed is that it is no longer *only* that. Query latency used to have a
25.6x hole in it that made the engine unusable the moment a query contained a
common word, and phrases did not exist at all. Both are fixed, and both are
verified against Lucene rather than against expectations written by the same
person who wrote the code. The engine is now behind Lucene by a factor of two to
three on its worst query shape instead of by a factor of twenty-five.

That is a different sentence from "use this instead of Lucene", and it is
deliberately not that sentence. If a static build-once index is not your
workload, this repository is a teardown of why Lucene costs what it costs, and
you should read [`docs/findings.md`](docs/findings.md) and then go use Lucene.

### What is still missing, named rather than implied

- **Deletes, updates, incremental indexing.** Out of scope by design, not an
  oversight: the frozen single segment is where the open-time win comes from.
- **A second-level skip index.** The named cause of the remaining conjunction
  gap. `docs/findings.md` §9.
- **Multiple fields, faceting, sorting, highlighting, vectors.** None planned.
- **Nested boolean queries.** `Query` is flat over terms on purpose.
- **A build that is not RAM-bound.** `IndexBuilder` holds every posting list on
  the heap until `close()`, so corpus size is bounded by memory.

---

## Using it

```sh
# build a frozen index from a TSV of id<TAB>text
java -jar cli/target/waypoint-cli.jar index corpus.tsv my.wpt

# ...or with positions, which is what phrase queries need
java -jar cli/target/waypoint-cli.jar index corpus.tsv my.wpt --positions

# query it
java -jar cli/target/waypoint-cli.jar search my.wpt -k 10 manhattan project
java -jar cli/target/waypoint-cli.jar search my.wpt -k 10 --and blood pressure
java -jar cli/target/waypoint-cli.jar search my.wpt -k 10 --phrase manhattan project

# the CRC32 that `search` deliberately does not check on open
java -jar cli/target/waypoint-cli.jar verify my.wpt
```

```java
try (IndexBuilder b = IndexBuilder.create(path, /* positions */ true)) {
    b.addText("doc-42", "the quick brown fox");
}

try (Index index = Index.open(path)) {
    Hits hits = index.search(
        Query.or(Query.term("quick"), Query.term("fox")), 10);
    for (int i = 0; i < hits.size(); i++) {
        System.out.println(hits.key(i) + "\t" + hits.score(i));
    }

    // adjacent, in order
    index.search(Query.phrase("quick", "brown"), 10);

    // the exact match count, even where search() returns a lower bound
    long exact = index.count(Query.or(Query.term("quick"), Query.term("fox")));
}
```

There is also a Spring Boot service (`GET /search?q=&k=&op=`, where `op` is
`or`, `and` or `phrase`) that opens the index once at startup and shares it —
`Index` is immutable after construction and safe for concurrent searching.

And a React front end over that service, in [`web/`](web/):

```sh
cd web && npm install && npm run build   # builds into the service's static resources
cd .. && mvn -pl service -am package -DskipTests
java -jar service/target/waypoint-service.jar   # app and API on one port
```

It builds into `service/src/main/resources/static`, so it ships inside
`waypoint-service.jar` and is served from the context root — one process, one
port, no CORS. `npm run dev` in `web/` proxies to a service on 8080 instead.

Because the index stores keys and scores and no document text, the results are
ids and scores rather than snippets. What the UI does with that is show the
things that are real: the score as a fraction of the best on screen, the round
trip, and — where a disjunction was pruned before it counted every match — the
match count labelled as the lower bound it is rather than as a total. Phrase
search disables itself, with a reason, on an index built without positions.

## Layout

| module | depends on | purpose |
|---|---|---|
| `core` | **nothing** | index writer, reader, scoring, query execution — 17 class files |
| `cli` | core | bare `main()`; where cold start is measured |
| `service` | core, Spring Boot | REST API, and the built front end as static resources |
| `bench` | core, Lucene, JMH | benchmarks and the differential tests |
| `web` | the service's HTTP API | React front end; not a Maven module |

`bench` is depended upon by nothing, and Lucene appears in exactly one module's
POM.

## Building and reproducing

Needs JDK 25 (final FFM, `java.lang.classfile`, the AOT cache) and Maven.

```sh
mvn verify                       # full test suite, including the budget tests

export JAVA_HOME=/path/to/jdk-25
export CORPUS=/path/to/collection-1m.tsv   # MS MARCO, head -n 1000000
scripts/bench.sh                 # regenerates everything in results/
```

The one deliberate omission from `verify`: opening an index does **not** check
its CRC32. Lucene validates a header and retrieves a checksum for every file it
opens; part of the open-time win is bought by not doing that. `waypoint verify`
does it on demand, and `IndexFormatTest` pins the consequence — a corrupted body
opens cleanly and is caught only by `verify`.

## Reading further

- [`docs/methodology.md`](docs/methodology.md) — how everything is measured and
  what was disclosed
- [`docs/design.md`](docs/design.md) — the design document, written before any
  of this existed, including the kill criterion it failed
- [`docs/teardown/`](docs/teardown/) — the Lucene 10.5.1 source teardown this
  was built from, with `file:line` citations
- [`docs/findings.md`](docs/findings.md) — secondary results, including the
  optimisation that was measured and rejected
- [`results/`](results/) — generated reports, with the exact command line for
  every configuration, and [`results/latency.md`](results/latency.md) for the
  query-latency table
