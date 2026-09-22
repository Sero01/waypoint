# Design: a from-scratch search engine, benchmarked against Lucene

Date: 2026-09-06
Status: approved for implementation
Supersedes: board item `h2.spring` ("One public Spring Boot service")

## 1. Why this exists

`parvez-profile.md` rates Java/Spring Boot as the most valuable and least
provable skill for the Malaysia/KL market: two years of production Spring
Boot at a custodian, **zero public Java across 21 repositories**, and the
work itself characterised as *migration and upgrade, not greenfield
design*. The board's `h2.spring` item exists to close that gap.

This project replaces the originally-scoped financial-document service.
The trade is deliberate: it drops the domain signal a KL custodian would
recognise, and buys instead a demonstration of data-structure depth, JVM
performance work, and benchmarking rigour — the pattern already visible
in DocVal's evals, ReconMatch's BenchRec and the 26M-router study.

**Corpus is generic IR, not financial.** Decided 2026-09-06 for
comparability with published numbers.

## 2. Evidence base

Every design decision below traces to the teardown in `notes/`, built
from a shallow clone of `apache/lucene` at tag `releases/lucene/10.5.1`
plus a measurement harness in `harness/`.

- `notes/01-storage-and-postings.md` — codec composition, postings format
- `notes/02-terms-search-indexing.md` — terms dict, query execution, indexing chain
- `notes/03-winnable-axes.md` — hypotheses from source reading
- `notes/04-measurements.md` — what measurement actually showed

Three findings shaped this design:

1. **Lucene's SIMD postings decode is behind `--add-modules
   jdk.incubator.vector`** (`VectorizationProvider.java:163-168`).
   Measured worth: **1.80x on single-term p50**. Benchmarking a stock
   `java -jar` measures Lucene's scalar path and inflates any win.
2. **Build throughput is not winnable.** Measured ceiling **1.26x**,
   because tokenisation is 74% of indexing and is a shared floor.
   This killed the original headline hypothesis.
3. **Cold start is winnable and large.** Fresh JVM, single-segment index:
   **292–327 ms to open, 38–93 ms to first query**. Repeated opens in the
   same JVM cost 3–5 ms, so ~98% is one-time cost — **2,068 classes
   loaded, 523 of them `org.apache.lucene`**.

> **These spike numbers were measured on Temurin 21**, because that is
> Lucene's stated minimum and the spike predated the Java 25 decision
> (§4). They justify the direction; they are not the baseline. The first
> benchmarking task re-establishes every Lucene number on JDK 25 before
> any comparison is published.

## 3. The claim

> A static, read-only, single-file inverted index answers its first query
> in a fresh JVM substantially faster than Lucene, because it has almost
> nothing to load or construct at open time. Here is the measurement,
> here is where Lucene beats it, and here is what the difference costs
> you in generality.

Boundaries stated in the README's first paragraph, not buried:

- the index is **built once and frozen** — no deletes, no updates, no
  incremental indexing, no near-real-time;
- **one segment, one file**, always;
- no positions in v1, therefore no phrase queries;
- Lucene is the right choice for essentially every production workload,
  and the README says so in those words.

The constraint *is* the thesis. Lucene pays for deletes, updates, NRT,
arbitrary schemas and pluggable codecs; this measures that bill.

### Kill criterion

If, with the AOT cache enabled for **both** engines and Lucene given a
`forceMerge(1)` single-segment index, **median external wall clock**
(process start to first result printed, §10) is not at least **3x**
better, the headline claim is withdrawn and the project ships as the
teardown study instead. This is a decision gate in the plan, not an
aspiration.

## 4. Architecture

**Java 25 LTS.** FFM is final since 22, so `MemorySegment` needs none of
the ASM stub-jar hack Lucene uses to compile Panama code on 21
(`gradle/generation/extract-jdk-apis.gradle`); there is no 2 GB mapping
limit; and JDK 24+ ships the Leyden AOT cache, which is the
state-of-the-art rebuttal to "just use class-data sharing" and therefore
must be tested. Lucene 10.5.1 runs on 25, so the comparison is unaffected.

**Maven**, four modules. Maven over Gradle because it is what the target
employers run.

| Module | Depends on | Purpose |
|---|---|---|
| `core` | **nothing** | Index writer, reader, scoring, query execution |
| `cli` | core | Bare `main()`. Where cold start is measured; the demo |
| `service` | core, Spring Boot | REST API — the `h2.spring` deliverable |
| `bench` | core, Lucene 10.5.1, JMH | Benchmarks and parity tests |

`bench` is never depended upon by anything. Lucene appears in exactly one
module's POM.

### The zero-dependency rule

`core` has no dependencies, and this is load-bearing rather than
hygienic. The claim is "we load tens of classes where Lucene loads 523";
one transitive logging facade destroys it. Therefore in `core`:

- no `ServiceLoader`, no reflection, no annotation processing;
- no static initialiser that does real work;
- no logging framework — the read path does not log;
- no enums with bodies, no lambdas in the open path where a method
  reference would load fewer classes (verify, do not assume).

This is enforced by a test, described in §9.

## 5. Index format

One file, memory-mapped, read through `MemorySegment`.

```
+---------------------------------------------------------------+
| header    magic "TSCH", formatVersion, flags,                  |
|           docCount, tokenCount, avgDocLength,                  |
|           offsets: terms, postings, norms, docKeys             |
+---------------------------------------------------------------+
| terms     sorted term bytes, front-coded (shared-prefix len +  |
|           suffix), with a fixed-stride skip table every 64     |
|           terms holding {absolute offset, full term} so a      |
|           lookup binary-searches the table then scans <=64     |
+---------------------------------------------------------------+
| postings  per term: docFreq, then delta-varint docIds,         |
|           then varint freqs                                    |
+---------------------------------------------------------------+
| norms     byte[docCount], SmallFloat-quantised doc lengths     |
+---------------------------------------------------------------+
| docKeys   external document identifiers                        |
+---------------------------------------------------------------+
| footer    CRC32 of everything above                            |
+---------------------------------------------------------------+
```

Deliberately simpler than Lucene: no bit-packing, no two-level skip
lists, no impacts, no positions. **We will lose on index size and on
query latency, and the README reports both losses.** What the format buys
is that opening it is one `map()` call, one header read, and zero object
graph construction — every section is read lazily off the segment.

### The checksum decision

Lucene validates a header and retrieves a checksum for every file it
opens (`Lucene104PostingsReader.java:138-160`). We do **not** verify the
CRC32 on open — only magic and format version. The CRC is checked by an
explicit `verify` command.

This is a real trade-off and the README states it plainly: we are faster
to open partly because we do less checking, and that is exactly the kind
of bill Lucene is paying. Presenting the number without this sentence
would be dishonest.

## 6. Core API

```java
// build
try (IndexBuilder b = IndexBuilder.create(path)) {
    b.add("doc-42", tokens);          // caller supplies tokens
    b.addText("doc-43", "raw text");  // or uses the bundled tokenizer
}

// read
try (Index index = Index.open(path)) {
    Hits hits = index.search(Query.or(Query.term("foo"), Query.term("bar")), 10);
}
```

`Query` is a sealed interface with three implementations in v1 —
`Term`, `And`, `Or`. Sealed rather than open so query execution can
switch exhaustively without a visitor and without virtual dispatch
through an extension point nobody uses.

Estimated `core` surface: roughly 20 classes. The budget test holds the
line.

## 7. Query execution

Single segment, so document ids are already global and there is no
per-leaf iteration, no `LeafReaderContext`, and no live-docs `Bits` check
per document (`Weight.java:297,311,334`).

- **Term** — walk the postings cursor, score, offer to a bounded min-heap.
- **And** — leapfrog intersection, driven by the rarest term.
- **Or** — a document-at-a-time merge over a small cursor heap.

No WAND, no MaxScore, no block-max pruning in v1. Lucene's
`MaxScoreBulkScorer` is windowed BlockMax-MaxScore with essential /
non-essential partitioning (`MaxScoreBulkScorer.java:28-51,119-152`); we
will not beat it and will not pretend to.

**Total-hit counting must match.** Lucene stops counting at
`TOTAL_HITS_THRESHOLD = 1000` (`IndexSearcher.java:106`) and returns a
lower bound — observed directly in the spike as `hits>=1002`. An engine
that counts every match does strictly more work, so v1 adopts the same
threshold and reports totals the same way.

## 8. Scoring and parity

BM25 with Lucene's defaults, `k1 = 1.2`, `b = 0.75`.

Critically, **document length is quantised to one byte exactly as Lucene
does** (`BM25Similarity.java:112-116`, `SmallFloat`). Lucene does not
compute textbook BM25: it builds a 256-entry length table and precomputes
`cache[i] = 1f / (k1 * ((1-b) + b * LENGTH_TABLE[i] / avgdl))`, then
evaluates `weight - weight / (1 + freq * normInverse)` rather than
`freq / (freq + norm)`, to stay monotonic in float without promoting to
double.

We replicate the quantisation and the algebraic form. Without this,
scores differ and top-k diverges on near-ties, and a "matches Lucene"
claim would be false.

## 9. Testing

TDD throughout. Tests are the artifact as much as the engine is.

**Unit.** Varint round-trip; front-coding round-trip; skip-table binary
search boundaries; `SmallFloat` quantisation asserted equal to Lucene's
own `SmallFloat` for all 256 values; BM25 against hand-computed values.

**Differential, with Lucene as oracle.** The strongest tool available:
Lucene is a mature reference implementation of the thing we are building.
Property-based tests generate random corpora and random queries, index
both, and assert identical top-k document ids and scores within `1e-4`
relative. Both engines are driven from **the same token stream** — our
tokenizer wrapped in a Lucene `Analyzer` — so any divergence is the index
or the scoring, never tokenisation. Ties broken by document id on both
sides.

**Class budget.** A test forks a JVM with `-Xlog:class+load=info`, runs
one cold query through the CLI, parses the output, and fails if the
loaded-class count exceeds a committed budget. This is the same technique
that produced the 523 figure and it converts the central claim from
marketing into a build failure. It is the most interesting test in the
repository.

**Integration.** CLI end-to-end against a fixture index; `service`
endpoints via MockMvc.

**CI.** GitHub Actions on JDK 25: full test suite plus a short benchmark
smoke run so the harness cannot rot.

## 10. Benchmark methodology

Everything here is designed so a hostile reader cannot dismiss it.

**Corpus.** MS MARCO passage collection — freely downloadable, standard,
citable — with a 1M-passage subset for laptop-scale runs and the real dev
queries rather than invented ones. A tiny fixture corpus is committed for
tests; the large corpus is fetched by script.

**Query latency and build throughput: JMH.** Forked, warmed, multiple
iterations. Reported as p50/p95/p99.

**Cold start cannot use JMH** — JMH warms up, which is precisely what we
are measuring. Instead: fork N=20 fresh JVMs per configuration and record
two numbers.

- *In-process*: `main()` entry to first result, via `System.nanoTime`.
- *External*: wall clock of the whole process, including JVM startup.
  This is what a person actually feels, and it is the headline.

Report median with p5/p95. Machine spec, JDK build, and every flag are
committed alongside the numbers.

**Lucene gets every advantage.**

- `--add-modules jdk.incubator.vector` **on** for the headline, with a
  second column showing it off, because the 1.80x delta is itself a
  finding worth publishing.
- `forceMerge(1)` — a single segment, Lucene's best case for open time.
- Freqs-only + norms index options, matching ours.
- The same analyzer and the same token stream.
- Same `TOTAL_HITS_THRESHOLD`.

**Counter-experiments — these are the point, not an appendix.**

- **JDK 24+ AOT cache** (JEP 483, `-XX:AOTCache` / `-XX:AOTMode`) applied
  to *both* engines. Exact flag spelling is to be confirmed against the
  installed JDK 25 during implementation, not taken from this document.
- **AppCDS** (`-XX:SharedArchiveFile`) applied to both.
- Optionally GraalVM native-image, as a follow-up section only.

If the AOT cache closes the gap, that is the result and the README leads
with it. Running the experiment that could falsify the claim is what
separates this from a blog post.

## 11. Error handling

`core` throws, and does not log. Behaviour on a bad index:

| Condition | Behaviour |
|---|---|
| Bad magic / unknown format version | `IndexFormatException` naming both expected and found |
| Truncated file (header offsets exceed size) | `IndexFormatException` at open |
| CRC mismatch | only from `verify` — not checked on open, by design (§5) |
| Term not in dictionary | empty result, never an exception |
| `k <= 0`, empty query | `IllegalArgumentException` |

`service` maps these: malformed query → 400; index not yet built or
absent → 503 with a message saying which; anything else → 500. The
service never returns a stack trace.

## 12. Non-goals

Explicit, so the README can point at this list: deletes, updates,
incremental or near-real-time indexing, multiple segments, phrase and
proximity queries, faceting, highlighting, sorting by anything but score,
multi-field schemas, distribution or sharding, and any form of vector or
hybrid search.

## 13. Risks

| Risk | Response |
|---|---|
| Cold-start gap smaller than hoped | Kill criterion in §3; the teardown is the fallback artifact and is already largely written |
| AOT cache erases the advantage | Then that is the finding and it leads the README. Either outcome is publishable; only failing to test it is not |
| "You only won because you do less" | Correct, and it is the thesis. Stated in the first paragraph, with the checksum decision as a worked example |
| Benchmark called rigged | Lucene given every advantage (§10); all flags and configs committed; the raw harness is in the repo |
| Scope creep into a real search engine | §12 is fixed for v1 |
| Laptop-quality numbers | N=20 forks, medians with spread, full machine disclosure; no claim finer than the noise |

## 14. What gets published

README order, chosen so the constraint is read before the number:

1. What this is, and what it deliberately is not (§3 boundaries).
2. The measurement: time-to-first-result, ours vs Lucene, with flags.
3. The counter-experiment: AOT cache and AppCDS, both engines.
4. Where Lucene wins — query latency with SIMD on, index size — as a
   table, not a footnote.
5. Why: the teardown, with `file:line` citations into Lucene 10.5.1.
6. "Use Lucene." Said plainly.

Two findings are publishable on their own and should become posts: the
`jdk.incubator.vector` flag being worth 1.80x, and tokenisation being 74%
of indexing time so index-build benchmarks mostly measure tokenizers.

## 15. Open decisions for implementation

- **Repository name.** Working name `tinysearch` — rhymes with the
  existing `tiny-router-poc`. Everything currently sits under
  `Projects/lucene-study/`; the `lucene/` clone and `work/` must be
  git-ignored, as they are reference material and not part of the
  artifact.
- **Whether `harness/` ships.** It is spike-quality. Either clean it up
  into `bench` or delete it once `bench` supersedes it — do not publish
  it as-is.
