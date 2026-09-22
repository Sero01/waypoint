# Lucene teardown — part 4: measurements

Spike harness: `harness/src/Harness.java`, `harness/src/OpenProbe.java`.
Lucene 10.5.1 jars from Maven Central. Temurin 21.0.12.1, 8 CPUs,
`preferredBitSize=512` (AVX-512), FMA enabled.

Spike corpus: the Lucene source tree chunked into ~30-line documents —
51,339 docs / 55.8 MB. Real text, zero download, reproducible. The real
project should use a standard IR corpus; this is only for relative
magnitudes.

**These numbers are single-run, wall-clock, on a laptop under a desktop
OS. They are directionally sound and not publishable as-is.** The real
benchmark needs JMH, pinned CPU, multiple forks.

## Headline: the source-reading ranking was wrong

| Axis | Predicted (part 3) | Measured | Verdict |
|---|---|---|---|
| Index build throughput | most likely win | **1.26–1.36x ceiling** | **hypothesis dead** |
| Cold open / first query | wins but may not impress | **~292 ms floor, 523 classes** | **now the strongest axis** |
| Query latency | likely loss | confirmed loss | unchanged |

## Build throughput — the win is much smaller than predicted

51,339 docs:

| Build | Time | docs/s | On disk |
|---|---|---|---|
| A: Lucene default TextField (positions+norms, 16 MB buffer, CFS) | 6.47 s | 7,936 | 15.7 MB |
| B: Lucene freqs-only+norms, 1 GB buffer, no CFS | 3.06 s | 16,787 | 9.1 MB |
| C: naive in-memory inverted index, **same analyzer** | 2.43 s | 21,121 | — |

C decomposes into **analysis 1.84 s + index build 0.59 s**.

So:
- analysis is **74% of the naive build** and is a *shared floor* — it is
  Lucene's own `StandardAnalyzer` in both cases;
- Lucene's non-analysis indexing work is ~1.22 s vs the naive 0.59 s,
  i.e. Lucene's data-structure overhead really is ~**2.1x**;
- but end-to-end that is only **1.26x**, because tokenisation dominates
  and cannot be won without writing a faster tokenizer — a different
  project, and one where you would be comparing tokenizers, not indexes.

Positions cost 6.6 MB of 15.7 MB (A vs B) and roughly half the build time.

**This kills build throughput as a headline.** A 1.3x claim is not worth
a README, and the honest version of it ("I am 2.1x faster at the part
that isn't tokenising, which is 26% of the work") is a footnote.

## Cold start — the real finding

`OpenProbe`, repeated open in one JVM, 1-segment index:

```
open #0   217.952 ms      <- first
open #1     5.195 ms
open #2     5.201 ms
...
open #14    2.932 ms
```

**~98% of open cost is one-time JVM cost**, not per-open work. Fresh JVM,
one open + one query, 3 runs each:

| Segments | Cold open | First query |
|---|---|---|
| 1 | 292 / 298 / 327 ms | 38 / 40 / 93 ms |
| 9 | 315 / 315 / 533 ms | 40 / 42 / 41 ms |
| 41 | 539 / 606 / 961 ms | 45 / 56 / 81 ms |

So time-to-first-result is ~**330 ms** on a tidy single-segment index and
**0.6–1.0 s** on a 41-segment one. Two components:

1. **A fixed floor of ~290 ms** that is paid regardless of index size.
   `-Xlog:class+load` on one cold open: **2,068 classes loaded, of which
   523 are `org.apache.lucene`** — top packages `codecs.lucene90` (43),
   `codecs.lucene103` (19), `internal.vectorization` (18), `util.packed`
   (18), `store.MMapDirectory` (18). That is SPI codec resolution plus
   the whole per-format reader stack plus JIT.
2. **A per-segment component** — clearly visible from 1 → 41 segments.

A from-scratch engine with one flat mmap'd file and ~30 classes should
load and answer in tens of ms. **A 5–10x win here is plausible.**

Honest caveats that must be in the README:
- much of this is JVM class loading, not Lucene's file format;
- an interviewer will say "use AppCDS / native-image" — so **test that**.
  Whether AppCDS closes the gap is itself the interesting result, and
  running it is the difference between a benchmark and a blog post.

## The SIMD flag is worth up to 1.8x — confirmed empirically

Same index B, same JVM, only `--add-modules jdk.incubator.vector` differs.
On startup with the flag Lucene logs:
`PanamaVectorizationProvider: Java vector incubator API enabled; uses preferredBitSize=512; FMA enabled`

| Query (p50, µs) | Scalar (stock JVM) | SIMD | Speedup |
|---|---|---|---|
| single-term | 64.5 | 35.9 | **1.80x** |
| OR (3 terms) | 253.6 | 182.9 | **1.39x** |
| AND (3 terms) | 62.6 | 48.2 | **1.30x** |

**Benchmarking against a stock `java -jar` overstates any query-latency
win by up to 1.8x.** This was predicted from
`VectorizationProvider.java:163-168` and is now measured. Every published
number must state the flag.

## Query latency — confirmed loss

Lucene B (1 segment, 51k docs, SIMD on): single-term p50 **35.9 µs**,
AND(3) **48.2 µs**, OR(3) **182.9 µs**. Against `MaxScoreBulkScorer`'s
windowed BlockMax-MaxScore this is not a fight worth picking as a
headline. Report it as a measured loss.

## Incidental confirmation

`TopDocs.totalHits` came back as `hits>=1002` on a corpus where the term
matches far more — `TOTAL_HITS_THRESHOLD = 1000` (`IndexSearcher.java:106`)
behaving exactly as the source said. Any engine that counts all matches
is doing more work than Lucene and must not be compared naively.
