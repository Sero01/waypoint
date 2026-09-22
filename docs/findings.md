# Secondary findings

Things this project measured on the way to its headline that are worth writing
down on their own — including the experiments that did not work out, which are
the more useful half.

## 1. Lucene's SIMD flag makes cold start worse

`--add-modules jdk.incubator.vector` switches Lucene onto its Panama vectorised
postings decoder (`VectorizationProvider.java:163-168`). The teardown measured
it worth **up to 1.80x on warm single-term query latency**.

It also costs **207 extra classes** to load, and on time-to-first-result it is a
consistent **loss** across every configuration measured:

| | scalar | + SIMD |
|---|---:|---:|
| stock `java -cp` | faster | ~20% slower |
| + AppCDS | faster | slower |
| + AOT cache | faster | slower |

So the flag is a straight trade, not a free win: it buys warm throughput and
sells cold start. Lucene's own best cold-start configuration in this benchmark
is the one with SIMD **off**.

Anyone publishing a Lucene query-latency number has to say which side of that
flag they were on. A stock `java -jar` is on the slow side of it, so a benchmark
that does not mention the flag is overstating any query-latency win against
Lucene by up to 1.8x.

## 2. AppCDS on JDK 25 cannot fully record `--add-modules`

Dumping a dynamic CDS archive from a run that used
`--add-modules jdk.incubator.vector`, then replaying it, produces:

```
[error][cds] Mismatched values for property jdk.module.addmods:
              jdk.incubator.vector specified during runtime but not during dump time
[error][cds] Disabling optimized module handling
```

The archive still helps substantially — it is not ignored — but Lucene with SIMD
does not get the full benefit of AppCDS on this JDK. `-XX:+AutoCreateSharedArchive`
behaves identically. This is disclosed in every generated report rather than
suppressed, because it works against Lucene and a table that hid it would be
understating what Lucene could do.

## 3. Replacing FFM with `MappedByteBuffer` would not help — measured

Waypoint reads its index through `MemorySegment`. Profiling the cold path showed
that opening an `Arena` drags in a large amount of `java.lang.invoke` and
`java.lang.classfile` machinery: `jdk.internal.foreign.MemorySessionImpl` reaches
for `VarHandles`, and from there the whole `LambdaForm` apparatus follows. Of
Waypoint's 566 classes above a bare JVM, roughly **230 are attributable to
FFM** — about 40%.

The obvious optimisation is to drop FFM for `FileChannel.map` into a
`MappedByteBuffer`, whose `get(int)` is a plain intrinsic with no `VarHandle`
behind it. Two minimal probes, each mapping the same 80 MB index and reading
4 KB of it, forked cold:

| | classes loaded | external wall clock |
|---|---:|---:|
| `MemorySegment` (FFM) | 843 | ~139 ms |
| `MappedByteBuffer` (NIO) | 817 | ~148 ms |

**26 classes, and no faster.** `java.nio`'s direct-buffer path reaches the same
method-handle machinery by a different route, so almost nothing is saved. The
change was not made.

That is worth stating because the reasoning was sound and the conclusion was
wrong. FFM keeps its other advantages for free: deterministic unmapping through
`Arena.close()` — which matters on Windows, where an unmapped file stays
locked — and no 2 GB limit per mapping.

## 4. Tokenisation is most of an index build, so build benchmarks mostly measure tokenizers

From the teardown: analysis is **74% of a naive in-memory index build**. Lucene's
non-analysis indexing work is about 2.1x a naive inverted index's, but end to
end that is only a **1.26x** difference, because the shared tokenizer dominates.

This is why build throughput is not a claim in this project. Measured on 1M
MS MARCO passages with both engines running the *same* tokenizer, the build
times come out close, and most of both numbers is identical work done twice. An
index-build benchmark between two engines with different analyzers is largely an
analyzer benchmark wearing a costume.

## 5. Roughly 90 ms of any JVM cold-start measurement is the JVM

A bare JVM that starts, prints one line and exits loads **440 classes**. On an
idle machine that is about 90 ms of wall clock, before any application code
does anything.

This matters for reading any cold-start comparison, including this one. It is a
floor both engines pay and neither controls, so it drags every external ratio
towards 1.0 — a 10x difference in engine work shows up as a 3x difference in
what the user waits for. Reporting external wall clock without the floor
alongside it invites the reader to attribute the JVM's cost to the engine.

Class counts for one cold query on 1M MS MARCO passages:

| | total | above a bare JVM |
|---|---:|---:|
| bare JVM | 440 | — |
| waypoint | 1,000 | 566 |
| lucene + SIMD | 2,511 | 2,076 |

## 6. Score parity with Lucene is achievable to the last ulp, but not to the bit

Replicating BM25 means replicating Lucene's *arithmetic*, not just its formula:
the byte-quantised document length, the 256-entry reciprocal cache, and the
`weight - weight/(1 + freq*normInverse)` rewrite Lucene uses to stay monotonic
without promoting to double.

With all of that in place, over 15,941 compared scores on random corpora:
**99.2% bit-identical, maximum relative difference 1.2e-7.**

The remaining 0.8% are not a bug and cannot be fixed. Float addition is not
associative, and for a multi-clause query Lucene sums clause contributions in
its scorer's order while Waypoint sums them rarest-term-first. Same inputs, same
arithmetic, different association, occasionally a different final ulp. A test
demanding byte equality would be a broken test.

## 7. A block-max bound built from the bounding-box corner prunes nothing

Block-max pruning needs an upper bound on every score in a postings block. The
cheapest correct one is the corner of the bounding box: the block's largest
frequency paired with its smallest norm. BM25 rises with frequency and falls
with document length, so scoring that pair bounds the block. Two fields, no
list, easy to write.

It pruned **nothing**. Measured on `the` over 1M MS MARCO passages, the
common-term query took 29,376 us with the corner bound against 27,525 us with
no pruning at all: within noise of the unpruned scan, plus the cost of reading
the bound.

The reason is that the corner describes a document that usually does not exist.
`the` has an idf of 0.14, so every score sits in a narrow band under that
ceiling and the tenth-best is already close to it. Almost any block of 128
documents contains *some* document with a high frequency and *some* short
document, so the corner lands above the tenth-best score even when no single
document in the block comes near it.

Storing the block's pareto frontier instead — the `(freq, norm)` pairs that no
other pair in the block dominates, which is what Lucene's impact lists are —
takes the same query to **1,960 us, a 14.0x improvement**, and moves the gap
against Lucene from 25.6x to 2.2x.

The frontier is cheap to compute. Norms are bytes, so a 256-slot table of
"largest frequency seen at this norm" plus one ascending walk gives it without
sorting anything, and an entry survives only by setting a record frequency as
documents get longer, which keeps the list short.

**The lesson generalises.** A bound that is correct but never tight is
indistinguishable from no bound, and it costs bytes and code to be useless. The
only way to find that out is to measure the pruning, not the correctness.

## 8. Skip data that only skips at block boundaries barely skips

The first working version of `Cursor.advance` tested whether a block could be
jumped only immediately after opening one. A cursor parked halfway through a
block whose last document was still below the target fell through to decoding
the rest of that block one posting at a time.

For a conjunction driven by a rare term against a common one, that is up to 127
wasted decodes per candidate, and it was most of what the query cost. Moving the
test so it applies whenever the cursor is blocked, not only at a fresh block:

| query | boundary-only skip | skip from anywhere |
|---|---:|---:|
| `the AND manhattan` | 1,626 us | **846 us** |
| `blood pressure medication` | 1,399 us | **1,145 us** |

No format change, four lines moved. It is worth stating plainly because the
version with the bug looked correct, passed the differential test against
Lucene, and was measurably faster than no skip data at all. It was simply
leaving half the win on the floor, and only a benchmark of the right query shape
showed it.

**Also worth stating: the benchmark suite did not originally contain that query
shape.** `blood pressure medication` has no clause long enough for block
skipping to matter, so it improved by 1.2x and would have supported the
conclusion that skip data does not help conjunctions. That conclusion is true of
that query and false in general. The suite now measures both.

## 9. What conjunctions still cost, and why

Conjunctions are the remaining loss at 2.5-2.9x behind Lucene, and the cause is
structural rather than a tuning problem.

`Cursor.advance` can skip a whole block, but to find the block containing a
target it walks the block headers one at a time from wherever it is. A term with
866,624 postings is 6,771 blocks, and their headers are scattered through tens
of megabytes of postings, so a conjunction pays a chain walk plus the page
faults that come with it. Lucene's two-level skip data lets it jump.

The fix is a sparse skip table per term — every 32nd block's last document and
byte offset, contiguous so a lookup is a short scan over cache-friendly memory
instead of a pointer chase through the postings. It is not implemented. The gap
it would close is named here rather than left for a reader to discover from the
table.

## 10. A stale shaded jar can make a benchmark measure code you are not running

Twice during this work the benchmark reported numbers from an older build.
`bench` packages an uber jar, and a stale `original-waypoint-bench.jar` left in
`bench/target` still contained an old copy of `waypoint-core`. That jar leads
the benchmark classpath, so it shadowed the freshly installed core, and the
index built by the harness came out byte-for-byte identical to the previous
format revision. Nothing failed. The numbers were simply from the wrong code.

`scripts/bench.sh` now runs `mvn clean install` rather than `mvn install`, and
the comment there says why.

Two things caught it, and both are worth keeping:

- The generated index was **exactly** the same size as the previous run. A
  format change that does not change the file size is not a format change.
- `ColdStartSuite` compares the two engines' output before timing anything, and
  aborted with "the two engines do not agree on this query" when a reader
  change was run against an index built before it. A benchmark that checks
  agreement first catches what a benchmark that only checks the clock cannot.
